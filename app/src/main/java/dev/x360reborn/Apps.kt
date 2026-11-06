package dev.x360reborn

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import dev.x360reborn.home.AppInfo
import dev.x360reborn.home.BgStyle
import dev.x360reborn.home.Category
import dev.x360reborn.home.FolderInfo
import dev.x360reborn.home.Glyph
import dev.x360reborn.home.TileSize
import dev.x360reborn.home.Themes
import dev.x360reborn.home.SettingsEntry
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Installed apps, their icons, and the launcher's own recents/pins (kept in prefs, no usage-stats permission). */
class Apps(
    private val context: Context,
    /** An icon that missed the cache has finished rendering (called off the main thread). */
    private val onIconReady: () -> Unit = {},
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE)
    /** Icon raster edge in px; follows the on-screen tile size once [fitIconCache] knows it. */
    @Volatile private var px = defaultIconPx()
    /**
     * Rendered icons, bounded by bytes. Icons are up to 448 px square (~0.8 MB each, in
     * native memory), so caching every installed app grew without limit: ~300 MB for the
     * 388 launchable activities on Matt's phone. The budget is sized by [fitIconCache] to
     * the most tiles one frame can draw, so the screen never thrashes it; anything evicted
     * re-renders on demand.
     */
    private val icons = object : LruCache<String, Bitmap>(MIN_ICONS * iconKb()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }
    /**
     * The hero draws one icon much larger than the grid does (~400 px vs ~280 px at small
     * tiles), so it gets its own few full-size rasters instead of sizing every icon for it.
     */
    @Volatile private var heroPx = px
    private val heroIcons = LruCache<String, Bitmap>(HERO_ICONS)
    /** Keys queued for an on-demand render, so a missed icon is only rendered once. */
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val iconWorker = Executors.newSingleThreadExecutor()
    /** Work-profile badge art per key, kept apart from the icons (see renderBadge). */
    private val badges = ConcurrentHashMap<String, Bitmap>()
    @Volatile private var activities: Map<String, LauncherActivityInfo> = emptyMap()

    fun load(): List<AppInfo> {
        val me = Process.myUserHandle()
        activities = launcherApps.listedProfiles.flatMap { user ->
            launcherApps.getActivityList(null, user)
                .filter { it.componentName.packageName != context.packageName }
                .map { key(it, me) to it }
        }.toMap()
        icons.snapshot().keys.filter { it !in activities }.forEach { icons.remove(it) }
        heroIcons.snapshot().keys.filter { it !in activities }.forEach { heroIcons.remove(it) }
        badges.keys.retainAll(activities.keys)
        return activities.map { (key, info) -> AppInfo(key, info.label.toString(), category(info.applicationInfo)) }
    }

    /**
     * Icon raster edge in pixels. 0.4.3 fills whole grid tiles with the icon (and the
     * hero keeps its 0.4.1 size), so rasterise at density x 160: a filled LARGE portrait
     * tile is ~450 device px on the emulator and ~530 on Matt's phone. The 448 cap
     * bounds the all-apps cache at 448 x 448 x 4 ≈ 0.8 MB per icon.
     */
    private fun iconPx() = px

    /** Before the first layout: density x 160, which covers a filled LARGE portrait tile. */
    private fun defaultIconPx() = (context.resources.displayMetrics.density * 160).toInt().coerceIn(MIN_PX, MAX_PX)

    /**
     * Renders missing icons, [first] keys before the rest; calls [progress] every few icons.
     * [first] (the visible tiles) always renders; [then] (the hub's later pages, in order) is
     * prefetched only while the cache has room, so prefetching never evicts the icons on screen.
     */
    fun loadIcons(first: List<String>, then: List<String>, progress: () -> Unit) {
        val iconPx = iconPx()
        val firstSet = first.toSet()
        val order = LinkedHashSet(first).apply { addAll(then) }
        val prefetchLimit = icons.maxSize() * 3 / 4
        var n = 0
        for (key in order) {
            val info = activities[key] ?: continue
            if (icons.get(key) != null) continue
            if (key !in firstSet && icons.size() >= prefetchLimit) break
            icons.put(key, render(info, iconPx))
            if (info.user != Process.myUserHandle()) badges[key] = renderBadge(info, iconPx)
            if (++n % 8 == 0) progress()
        }
        progress()
    }

    /** Queues a background render of [key]'s icon (once), then reports it via onIconReady. */
    private fun requestIcon(key: String, hero: Boolean = false) {
        val info = activities[key] ?: return
        if (!pending.add(if (hero) "hero:$key" else key)) return
        val cache = if (hero) heroIcons else icons
        try {
            iconWorker.execute {
                try {
                    if (cache.get(key) == null) cache.put(key, render(info, if (hero) heroPx else iconPx()))
                } catch (e: Exception) {
                    // A package removed mid-render: the next reload drops its tile anyway.
                } finally {
                    pending.remove(if (hero) "hero:$key" else key)
                }
                onIconReady()
            }
        } catch (e: RejectedExecutionException) {
            pending.remove(if (hero) "hero:$key" else key) // shutting down
        }
    }

    fun close() = iconWorker.shutdownNow()

    /** One rendered icon's size in KB (the cache's unit). */
    private fun iconKb() = iconPx().let { it * it * 4 / 1024 }

    /**
     * Fits the icon cache to a layout: icons render at [tilePx], the largest on-screen icon
     * edge (no point rasterising a 300 px small tile at 448), and the budget holds
     * [tilesPerFrame] icons (the most one frame can draw) plus a third for paging back.
     * A size change drops the old rasters so tiles re-render sharp at the new size.
     */
    fun fitIconCache(tilesPerFrame: Int, tilePx: Int, heroIconPx: Int) {
        // 0 means the view has no size yet: keep the current rasters until it does.
        val nextHero = if (heroIconPx > 0) heroIconPx.coerceIn(MIN_PX, MAX_PX) else heroPx
        if (nextHero != heroPx) {
            heroPx = nextHero
            heroIcons.evictAll()
        }
        val nextPx = if (tilePx > 0) tilePx.coerceIn(MIN_PX, MAX_PX) else px
        if (nextPx != px) {
            px = nextPx
            icons.evictAll()
            badges.clear()
        }
        val want = maxOf(MIN_ICONS, tilesPerFrame + tilesPerFrame / 3) * iconKb()
        if (want != icons.maxSize()) icons.resize(want)
    }

    /** Personal apps keep the bare component key (so saved pins/recents survive); work apps add the profile serial. */
    private fun key(info: LauncherActivityInfo, me: UserHandle): String {
        val cn = info.componentName.flattenToShortString()
        return if (info.user == me) cn else "$cn#${users.getSerialNumberForUser(info.user)}"
    }

    private fun category(ai: ApplicationInfo) = when {
        ai.category == ApplicationInfo.CATEGORY_GAME || ai.flags and ApplicationInfo.FLAG_IS_GAME != 0 -> Category.GAME
        ai.category == ApplicationInfo.CATEGORY_VIDEO -> Category.VIDEO
        ai.category == ApplicationInfo.CATEGORY_AUDIO -> Category.MUSIC
        else -> Category.OTHER
    }

    private fun render(info: LauncherActivityInfo, px: Int): Bitmap {
        // Load the raw icon through PackageManager, NOT LauncherActivityInfo.getIcon(),
        // then draw its art full-square (see drawFullSquare): the source swap alone is
        // not enough, because an AdaptiveIconDrawable clips ITSELF to the system icon
        // shape (a circle on Matt's phone) when drawn, whichever API handed it over.
        val pm = context.packageManager
        val raw = try {
            pm.getApplicationIcon(info.applicationInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            // Package not visible to this user (e.g. installed only in the work profile):
            // fall back to the LauncherApps icon (unwrapped the same way below).
            info.getIcon(0)
        }
        val art = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawFullSquare(raw, Canvas(art), px)
        // The work-profile badge is NOT baked in (it was up to 0.4.2): grid tiles now
        // centre-crop the art to fill, which would slice a baked badge. It is rendered
        // separately (renderBadge) and HomeView overlays it at the tile's corner.
        return art
    }

    /**
     * The work-profile badge for [info] on its own transparent canvas, positioned exactly
     * where the system would draw it on the icon (its bottom-right corner), so HomeView
     * can overlay it on the hero icon or on a filled grid tile.
     */
    private fun renderBadge(info: LauncherActivityInfo, px: Int): Bitmap {
        val empty = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val badged = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, empty), info.user)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        badged.setBounds(0, 0, px, px)
        badged.draw(Canvas(out))
        return out
    }

    /** Work-profile badge art for [key], or null for personal apps. */
    fun badge(key: String): Bitmap? {
        if (!isWork(key)) return null
        badges[key]?.let { return it }
        val info = activities[key] ?: return null
        return renderBadge(info, iconPx()).also { badges[key] = it }
    }

    /** Draws [d] as the full square its designer made, with no system icon-shape mask. */
    private fun drawFullSquare(d: Drawable, canvas: Canvas, px: Int) {
        if (d is AdaptiveIconDrawable) {
            // AdaptiveIconDrawable.draw() clips both layers to the system mask path;
            // drawing the background/foreground layers directly skips the mask and
            // reproduces the designed full-bleed square (layers are drawn at the same
            // full viewport bounds the adaptive drawable itself would use).
            d.background?.let { it.setBounds(0, 0, px, px); it.draw(canvas) }
            d.foreground?.let { it.setBounds(0, 0, px, px); it.draw(canvas) }
        } else {
            d.setBounds(0, 0, px, px)
            d.draw(canvas)
        }
    }

    /** The cached icon, or null while it renders (the tile shows its plain body meanwhile). */
    fun icon(key: String): Bitmap? = icons.get(key) ?: run { requestIcon(key); null }

    /** The hero's full-size icon; until it renders, the grid-size one stands in (no blank flash). */
    fun heroIcon(key: String): Bitmap? {
        if (heroPx <= px) return icon(key)
        heroIcons.get(key)?.let { return it }
        requestIcon(key, hero = true)
        return icon(key)
    }

    fun component(key: String): ComponentName? = activities[key]?.componentName

    fun user(key: String): UserHandle? = activities[key]?.user

    fun isWork(key: String): Boolean = activities[key]?.let { it.user != Process.myUserHandle() } == true

    fun recent(): List<String> = prefs.all.entries
        .filter { it.key.startsWith(RECENT) }
        .sortedByDescending { it.value as? Long ?: 0L }
        .map { it.key.removePrefix(RECENT) }

    fun pins(): List<String> = prefs.getString(PINS, "")!!.split('\n').filter { it.isNotEmpty() }

    fun markLaunched(key: String) = prefs.edit().putLong(RECENT + key, System.currentTimeMillis()).apply()

    fun togglePin(key: String) {
        val now = pins().toMutableList()
        if (!now.remove(key)) now.add(0, key)
        prefs.edit().putString(PINS, now.joinToString("\n")).apply()
    }

    fun forget(key: String) = prefs.edit().remove(RECENT + key).apply()

    // ---- folders ---------------------------------------------------------------------------
    // "folders" holds the folder ids in creation order; each folder keeps its name and its
    // member app keys (stored order) under "folder:<id>:name" / "folder:<id>:members".

    fun folders(): List<FolderInfo> =
        prefs.getString(FOLDERS, "")!!.split('\n').filter { it.isNotEmpty() }.map { id ->
            FolderInfo(
                id,
                prefs.getString(FOLDER + id + NAME, "")!!,
                prefs.getString(FOLDER + id + MEMBERS, "")!!.split('\n').filter { it.isNotEmpty() },
            )
        }

    private fun saveFolders(list: List<FolderInfo>) {
        val e = prefs.edit()
        e.putString(FOLDERS, list.joinToString("\n") { it.id })
        // Drop the name/members keys of folders that no longer exist.
        prefs.all.keys.filter { it.startsWith(FOLDER) }.forEach { k ->
            val id = k.removePrefix(FOLDER).substringBefore(':')
            if (list.none { it.id == id }) e.remove(k)
        }
        for (f in list) {
            e.putString(FOLDER + f.id + NAME, f.name)
            e.putString(FOLDER + f.id + MEMBERS, f.members.joinToString("\n"))
        }
        e.apply()
    }

    fun createFolder(name: String, firstKey: String): FolderInfo {
        val seq = prefs.getInt(FOLDER_SEQ, 0) + 1
        prefs.edit().putInt(FOLDER_SEQ, seq).apply()
        // Millis base36 plus a stored counter: unique even for two folders in the same millisecond.
        val folder = FolderInfo("f" + System.currentTimeMillis().toString(36) + seq.toString(36), name, listOf(firstKey))
        saveFolders(folders() + folder)
        return folder
    }

    fun addToFolder(id: String, key: String) =
        saveFolders(folders().map { if (it.id == id && key !in it.members) it.copy(members = it.members + key) else it })

    /** Removing the last member deletes the folder. */
    fun removeFromFolder(id: String, key: String) =
        saveFolders(folders().mapNotNull {
            if (it.id != id) it else it.copy(members = it.members - key).takeIf { f -> f.members.isNotEmpty() }
        })

    fun renameFolder(id: String, name: String) =
        saveFolders(folders().map { if (it.id == id) it.copy(name = name) else it })

    fun deleteFolder(id: String) = saveFolders(folders().filter { it.id != id })

    var tileSize: TileSize
        get() = TileSize.entries.firstOrNull { it.name == prefs.getString(TILE_SIZE, null) } ?: TileSize.LARGE
        set(v) = prefs.edit().putString(TILE_SIZE, v.name).apply()

    var themeId: String
        get() = prefs.getString(THEME, null) ?: Themes.default.id
        set(v) = prefs.edit().putString(THEME, v).apply()

    var bgStyle: BgStyle
        get() = BgStyle.byName(prefs.getString(BG_STYLE, null))
        set(v) = prefs.edit().putString(BG_STYLE, v.name).apply()

    var clock24h: Boolean
        get() = prefs.getBoolean(CLOCK_24H, false)
        set(v) = prefs.edit().putBoolean(CLOCK_24H, v).apply()

    /** Every launcher setting as a [Backup] document. */
    fun exportSettings(): String = Backup.encode(prefs.all)

    /**
     * Replaces every launcher setting with [values] (from [Backup.decode]). Written with
     * commit(), not apply(): the activity is recreated right after and must read them back.
     */
    fun importSettings(values: Map<String, Any>): Boolean {
        val e = prefs.edit().clear()
        for ((k, v) in values) when (v) {
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
        }
        return e.commit()
    }

    fun channelHidden(title: String) = prefs.getBoolean(HIDE + title, false)

    fun setChannelHidden(title: String, hidden: Boolean) =
        prefs.edit().putBoolean(HIDE + title, hidden).apply()

    fun settingsEntries(xboxGranted: Boolean = false, xboxCount: Int = 0): List<SettingsEntry> {
        val settingsApp = activities.keys.firstOrNull { it.startsWith("com.android.settings/") }
        return listOf(
            // Xbox 360 games live on shared storage: without all-files access the entry is the
            // grant prompt; with it, the entry reports the scan and re-scans when tapped.
            if (xboxGranted) {
                SettingsEntry("xbox 360 games: $xboxCount found", ACTION_XBOX, glyph = Glyph.GAMEPAD, external = false)
            } else {
                SettingsEntry("xbox 360 games: grant folder access", ACTION_XBOX, glyph = Glyph.GAMEPAD, external = false)
            },
            SettingsEntry("theme: ${Themes.byId(themeId).name.lowercase()}", ACTION_THEME, glyph = Glyph.THEME, external = false),
            SettingsEntry("tile size: ${tileSize.label}", ACTION_TILE_SIZE, glyph = Glyph.TILE_SIZE, external = false),
            SettingsEntry("background: ${bgStyle.label}", ACTION_BG, external = false),
            SettingsEntry("clock: ${if (clock24h) "24-hour" else "12-hour"}", ACTION_CLOCK, external = false),
            SettingsEntry("tv & movies: ${if (channelHidden("tv & movies")) "hidden" else "shown"}", ACTION_TV, external = false),
            SettingsEntry("music: ${if (channelHidden("music")) "hidden" else "shown"}", ACTION_MUSIC, external = false),
            SettingsEntry("back up settings", ACTION_BACKUP, glyph = Glyph.BACKUP, external = false),
            SettingsEntry("restore settings", ACTION_RESTORE, glyph = Glyph.RESTORE, external = false),
            SettingsEntry("system settings", Settings.ACTION_SETTINGS, settingsApp),
            SettingsEntry("wi-fi", Settings.ACTION_WIFI_SETTINGS),
            SettingsEntry("bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
            SettingsEntry("display", Settings.ACTION_DISPLAY_SETTINGS),
            SettingsEntry("sound", Settings.ACTION_SOUND_SETTINGS),
            SettingsEntry("battery", "android.intent.action.POWER_USAGE_SUMMARY"),
            SettingsEntry("storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            SettingsEntry("apps", Settings.ACTION_APPLICATION_SETTINGS),
            SettingsEntry("home app", Settings.ACTION_HOME_SETTINGS),
            SettingsEntry("about", Settings.ACTION_DEVICE_INFO_SETTINGS),
        )
    }

    companion object {
        /** Launcher-internal action: cycle the tile size. */
        const val ACTION_TILE_SIZE = "dev.x360reborn.TILE_SIZE"
        /** Launcher-internal action: open the app search dialog. */
        const val ACTION_SEARCH = "dev.x360reborn.SEARCH"
        /** Launcher-internal action: grant all-files access for Xbox 360 games, or re-scan. */
        const val ACTION_XBOX = "dev.x360reborn.XBOX"
        /** Launcher-internal action: open the theme picker. */
        const val ACTION_THEME = "dev.x360reborn.THEME"
        /** Launcher-internal action: cycle the background style. */
        const val ACTION_BG = "dev.x360reborn.BACKGROUND"
        /** Launcher-internal action: toggle the hub clock between 12-hour and 24-hour. */
        const val ACTION_CLOCK = "dev.x360reborn.CLOCK"
        /** Launcher-internal action: show/hide the tv & movies channel. */
        const val ACTION_TV = "dev.x360reborn.TOGGLE_TV"
        /** Launcher-internal action: show/hide the music channel. */
        const val ACTION_MUSIC = "dev.x360reborn.TOGGLE_MUSIC"
        /** Launcher-internal action: save every launcher setting to a file the user picks. */
        const val ACTION_BACKUP = "dev.x360reborn.BACKUP"
        /** Launcher-internal action: replace every launcher setting from a backup file. */
        const val ACTION_RESTORE = "dev.x360reborn.RESTORE"
        /** Icon-cache floor, in icons. */
        private const val MIN_ICONS = 48
        /** Full-size hero rasters kept (the current hero plus a few recent ones). */
        private const val HERO_ICONS = 3
        /** Icon raster bounds: 448 caps one icon at ~0.8 MB. */
        private const val MIN_PX = 96
        private const val MAX_PX = 448
        private const val RECENT = "recent:"
        private const val PINS = "pins"
        private const val TILE_SIZE = "tileSize"
        private const val THEME = "theme"
        private const val BG_STYLE = "bgStyle"
        private const val CLOCK_24H = "clock24h"
        private const val HIDE = "hide:"
        private const val FOLDERS = "folders"
        private const val FOLDER = "folder:"
        private const val NAME = ":name"
        private const val MEMBERS = ":members"
        private const val FOLDER_SEQ = "folderSeq"
    }
}
