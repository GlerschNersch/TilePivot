package dev.x360reborn.avatar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.x360reborn.home.Themes
import java.util.concurrent.Executors

/**
 * The avatar screen: the 3D avatar over the launcher's background, with a row of buttons that
 * cycle each choice; every change is saved straight away. TilePivot's own avatar always works.
 * When a local build includes an avatar-pack decoder ([AvatarPacks.decoder]), a "Console pack"
 * mode renders items from a pack file the user picks once (copied into app-private storage).
 */
class AvatarActivity : Activity() {
    private lateinit var view: AvatarView
    private lateinit var row: LinearLayout
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("hub", Context.MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var pack: AvatarPack? = null
    private var outfit = PackOutfit.decode(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val look = AvatarLook.decode(prefs.getString(PREF, null))
        outfit = PackOutfit.decode(prefs.getString(PREF_PACK, null))
        view = AvatarView(this, look, Themes.active.bgMid)
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(32, 32, 32, 32) }
        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 16, 16, 16) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Themes.active.bgMid)
            addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 64 })
            addView(HorizontalScrollView(context).apply { addView(row) },
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 48 })
        }
        setContentView(root)
        if (outfit.active && AvatarPacks.decoder != null && AvatarPacks.file(filesDir).isFile) openPack() else ownButtons()
    }

    // ---- TilePivot's own avatar -------------------------------------------------------------

    private fun ownButtons() {
        row.removeAllViews(); status.text = ""
        button("Skin") { saveLook(view.look.copy(skin = (view.look.skin + 1) % AvatarLook.SKIN.size)) }
        button("Hair") { saveLook(view.look.copy(hair = (view.look.hair + 1) % HairStyle.entries.size)) }
        button("Hair colour") { saveLook(view.look.copy(hairColor = (view.look.hairColor + 1) % AvatarLook.HAIR_COLORS.size)) }
        button("Top") { saveLook(view.look.copy(top = (view.look.top + 1) % AvatarLook.CLOTHES.size)) }
        button("Bottoms") { saveLook(view.look.copy(bottoms = (view.look.bottoms + 1) % AvatarLook.CLOTHES.size)) }
        button("Shoes") { saveLook(view.look.copy(shoes = (view.look.shoes + 1) % AvatarLook.SHOES.size)) }
        button("Wave") { view.renderer.emote(Animator.Emote.WAVE) }
        button("Cheer") { view.renderer.emote(Animator.Emote.CHEER) }
        if (AvatarPacks.decoder != null) button("Console pack…") { openPack() }
        button("Done") { finish() }
    }

    private fun saveLook(l: AvatarLook) {
        if (l == view.look) return
        view.look = l
        prefs.edit().putString(PREF, l.encode()).apply()
    }

    // ---- console pack (local builds only) ---------------------------------------------------

    private fun openPack() {
        val f = AvatarPacks.file(filesDir)
        if (!f.isFile) {
            Toast.makeText(this, "Pick your avatar pack file", Toast.LENGTH_LONG).show()
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PACK)
            return
        }
        status.text = "Loading pack…"
        worker.execute {
            val p = runCatching { AvatarPacks.decoder!!.open(f) }.onFailure { Log.w(TAG, "pack open failed", it) }.getOrNull()
            main.post {
                if (isDestroyed) return@post
                if (p == null) {
                    f.delete(); status.text = ""
                    Toast.makeText(this, "That file isn't an avatar pack this build can read", Toast.LENGTH_LONG).show()
                    ownButtons(); return@post
                }
                pack = p; outfit = outfit.withDefaults(p).copy(active = true); savePack(); packButtons(); rebuild()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (requestCode != REQ_PACK || resultCode != RESULT_OK) return
        status.text = "Copying pack…"
        worker.execute {
            val ok = runCatching {
                contentResolver.openInputStream(uri)!!.use { i -> AvatarPacks.file(filesDir).outputStream().use { i.copyTo(it) } }
            }.isSuccess
            main.post { if (ok) openPack() else { status.text = ""; Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_LONG).show() } }
        }
    }

    private fun packButtons() {
        row.removeAllViews()
        button("Body") { outfit = outfit.copy(male = !outfit.male).withDefaults(pack!!, reset = true); changed() }
        for (slot in listOf("hair", "top", "bottoms", "shoes", "hat", "glasses")) {
            button(slot.replaceFirstChar { it.uppercase() }) { outfit = outfit.next(pack!!, slot); changed() }
        }
        button("Skin") { saveLook(view.look.copy(skin = (view.look.skin + 1) % AvatarLook.SKIN.size)); rebuild() }
        button("Hair colour") { saveLook(view.look.copy(hairColor = (view.look.hairColor + 1) % AvatarLook.HAIR_COLORS.size)); rebuild() }
        button("Wave") { view.renderer.emote(Animator.Emote.WAVE) }
        button("Cheer") { view.renderer.emote(Animator.Emote.CHEER) }
        button("TilePivot avatar") { outfit = outfit.copy(active = false); savePack(); pack = null; view.showPack(null); ownButtons() }
        button("Done") { finish() }
    }

    private fun changed() { savePack(); rebuild() }
    private fun savePack() { prefs.edit().putString(PREF_PACK, outfit.encode()).apply() }

    private fun rebuild() {
        val p = pack ?: return
        val items = outfit.items(p)
        val skin = view.look.skinRgb(); val hair = view.look.hairRgb()
        status.text = items.joinToString(" · ") { it.name }
        worker.execute {
            val model = runCatching { p.outfit(items, skin, hair) }.onFailure { Log.w(TAG, "outfit failed", it) }.getOrNull()
            main.post { if (!isDestroyed && pack === p && model != null) view.showPack(model) }
        }
    }

    private fun button(label: String, onClick: () -> Unit) {
        row.addView(Button(this).apply {
            text = label; isAllCaps = false; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0xCC1E1E1E.toInt()); cornerRadius = 12f }
            setPadding(36, 12, 36, 12)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 12 })
    }

    override fun onResume() { super.onResume(); view.onResume() }
    override fun onPause() { view.onPause(); super.onPause() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    companion object {
        /** Hub prefs key holding [AvatarLook.encode]; part of settings backups. */
        const val PREF = "avatarLook"
        /** Hub prefs key for the pack outfit (item names only; the pack itself is never backed up). */
        const val PREF_PACK = "avatarPackOutfit"
        private const val REQ_PACK = 1
        private const val TAG = "AvatarActivity"
    }
}

/**
 * The chosen pack items, by name (names survive pack re-imports). [active] says whether the
 * avatar screen shows the pack or TilePivot's own avatar. Empty string = nothing in that slot.
 */
data class PackOutfit(
    val active: Boolean = false,
    val male: Boolean = true,
    val slots: Map<String, String> = emptyMap(),
) {
    fun encode() = (listOf(if (active) "1" else "0", if (male) "m" else "f") + SLOTS.map { slots[it] ?: "" }).joinToString("\u001f")

    private fun fits(i: PackItem) = if (male) i.male else i.female
    private fun choices(p: AvatarPack, slot: String): List<String> {
        val kinds = if (slot == "top") listOf("top", "outfit") else listOf(slot)
        val names = p.items.filter { it.slot in kinds && fits(it) }.map { it.name }.distinct()
        return if (slot in OPTIONAL) listOf("") + names else names
    }

    fun next(p: AvatarPack, slot: String): PackOutfit {
        val c = choices(p, slot); if (c.isEmpty()) return this
        val i = c.indexOf(slots[slot] ?: "")
        return copy(slots = slots + (slot to c[(i + 1) % c.size]))
    }

    /** Fills empty required slots with the first fitting item ([reset] re-picks every slot). */
    fun withDefaults(p: AvatarPack, reset: Boolean = false): PackOutfit {
        val s = slots.toMutableMap()
        for (slot in SLOTS) {
            val c = choices(p, slot)
            val cur = s[slot]
            if (reset || cur == null || (cur.isNotEmpty() && cur !in c)) {
                s[slot] = if (slot in OPTIONAL) "" else c.firstOrNull() ?: ""
            }
        }
        return copy(slots = s)
    }

    /** The items to draw: body, head, hair (hat cut if a hat is worn), clothes, accessories. */
    fun items(p: AvatarPack): List<PackItem> {
        val byName = p.items.associateBy { it.name }
        val out = ArrayList<PackItem>()
        p.items.firstOrNull { it.slot == "body" && fits(it) }?.let { out += it }
        p.items.firstOrNull { it.slot == "head" && fits(it) }?.let { out += it }
        val hat = slots["hat"].orEmpty()
        byName[slots["hair"].orEmpty()]?.let { h ->
            out += if (hat.isNotEmpty()) h.hatVariant?.let { v -> p.items.firstOrNull { it.index == v } } ?: h else h
        }
        val top = byName[slots["top"].orEmpty()]
        top?.let { out += it }
        if (top?.slot != "outfit") byName[slots["bottoms"].orEmpty()]?.let { out += it }
        for (s in listOf("shoes", "hat", "glasses")) byName[slots[s].orEmpty()]?.let { out += it }
        return out
    }

    companion object {
        val SLOTS = listOf("hair", "top", "bottoms", "shoes", "hat", "glasses")
        private val OPTIONAL = setOf("hat", "glasses")
        fun decode(s: String?): PackOutfit {
            val v = s?.split('\u001f') ?: return PackOutfit()
            if (v.size != 2 + SLOTS.size) return PackOutfit()
            return PackOutfit(v[0] == "1", v[1] != "f", SLOTS.zip(v.drop(2)).toMap())
        }
    }
}
