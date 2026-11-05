package dev.x360reborn

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.util.concurrent.ConcurrentHashMap

/** Installed apps, their icons, and the launcher's own recents/pins (kept in prefs, no usage-stats permission). */
class Apps(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE)
    private val icons = ConcurrentHashMap<String, Bitmap>()
    @Volatile private var activities: Map<String, LauncherActivityInfo> = emptyMap()

    fun load(): List<AppInfo> {
        val me = Process.myUserHandle()
        activities = launcherApps.listedProfiles.flatMap { user ->
            launcherApps.getActivityList(null, user)
                .filter { it.componentName.packageName != context.packageName }
                .map { key(it, me) to it }
        }.toMap()
        icons.keys.retainAll(activities.keys)
        return activities.map { (key, info) -> AppInfo(key, info.label.toString(), category(info.applicationInfo)) }
    }

    /** Renders missing icons, [first] keys before the rest; calls [progress] every few icons. */
    fun loadIcons(first: List<String>, progress: () -> Unit) {
        val iconPx = (context.resources.displayMetrics.density * 64).toInt().coerceAtLeast(96)
        val order = LinkedHashSet(first).apply { addAll(activities.keys) }
        var n = 0
        for (key in order) {
            val info = activities[key] ?: continue
            if (icons.containsKey(key)) continue
            icons[key] = render(info, iconPx)
            if (++n % 8 == 0) progress()
        }
        progress()
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
        // Work apps get the system's briefcase badge, so they can be told apart from personal copies.
        val d = context.packageManager.getUserBadgedIcon(info.getIcon(0), info.user)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        return bmp
    }

    fun icon(key: String): Bitmap? = icons[key]

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
