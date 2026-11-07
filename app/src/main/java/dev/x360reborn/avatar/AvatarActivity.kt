package dev.x360reborn.avatar

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import dev.x360reborn.home.Themes

/**
 * The avatar screen: the 3D avatar over the launcher's background, with a row of buttons that
 * cycle skin, hair, hair colour, top, bottoms and shoes. Every change is saved straight away.
 */
class AvatarActivity : Activity() {
    private lateinit var view: AvatarView
    private val prefs by lazy { getSharedPreferences("hub", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val look = AvatarLook.decode(prefs.getString(PREF, null))
        view = AvatarView(this, look, Themes.active.bgMid)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Themes.active.bgMid)
            addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 16, 16, 16) }
        fun button(label: String, change: (AvatarLook) -> AvatarLook) {
            row.addView(Button(this).apply {
                text = label; isAllCaps = false; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(0xCC1E1E1E.toInt()); cornerRadius = 12f }
                setPadding(36, 12, 36, 12)
                setOnClickListener { save(change(view.look)) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 12 })
        }
        button("Skin") { it.copy(skin = (it.skin + 1) % AvatarLook.SKIN.size) }
        button("Hair") { it.copy(hair = (it.hair + 1) % HairStyle.entries.size) }
        button("Hair colour") { it.copy(hairColor = (it.hairColor + 1) % AvatarLook.HAIR_COLORS.size) }
        button("Top") { it.copy(top = (it.top + 1) % AvatarLook.CLOTHES.size) }
        button("Bottoms") { it.copy(bottoms = (it.bottoms + 1) % AvatarLook.CLOTHES.size) }
        button("Shoes") { it.copy(shoes = (it.shoes + 1) % AvatarLook.SHOES.size) }
        button("Done") { finish(); it }
        root.addView(HorizontalScrollView(this).apply { addView(row) },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 48 })
        setContentView(root)
    }

    private fun save(l: AvatarLook) {
        if (l == view.look) return
        view.look = l
        prefs.edit().putString(PREF, l.encode()).apply()
    }

    override fun onResume() { super.onResume(); view.onResume() }
    override fun onPause() { view.onPause(); super.onPause() }

    companion object {
        /** Hub prefs key holding [AvatarLook.encode]; part of settings backups. */
        const val PREF = "avatarLook"
    }
}
