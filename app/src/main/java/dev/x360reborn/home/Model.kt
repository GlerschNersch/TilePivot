package dev.x360reborn.home

import dev.x360reborn.Apps

enum class Category { GAME, VIDEO, MUSIC, OTHER }

/** A launchable activity, reduced to what layout needs. [key] is the flattened component. */
data class AppInfo(val key: String, val label: String, val category: Category = Category.OTHER)

/** A user-created folder: an ordered set of app keys, shown as its own channel. */
data class FolderInfo(val id: String, val name: String, val members: List<String>)

/**
 * An Xbox 360 game file on shared storage, launched through the Xendroid+ emulator.
 * [cover] is an image file the user placed beside the game; [embeddedArt] means the game's
 * own STFS header carries a thumbnail (Games on Demand / XBLA). Either gives the tile art.
 */
data class GameInfo(
    val path: String,
    val name: String,
    val format: String,
    val cover: String? = null,
    val embeddedArt: Boolean = false,
) {
    val hasArt get() = cover != null || embeddedArt
}

/** Icon-key prefix for game cover art (app icon keys are component names, so no clash). */
const val GAME_ART = "game:"

sealed interface Target {
    data class App(val key: String) : Target
    /** [external] actions open another activity (and play the leave transition); internal ones don't. */
    data class Action(val name: String, val external: Boolean = true) : Target
    data class Channel(val index: Int) : Target
    /** An Xbox 360 game; [path] is the game_uri handed to Xendroid+ (the default.xex path for folder games). */
    data class Game(val path: String) : Target
}

enum class Kind { TILE, HERO, WIDE }

data class Tile(
    val label: String,
    val target: Target,
    val kind: Kind = Kind.TILE,
    val sub: String = "",
    /** App whose icon the tile shows, or null for a drawn glyph. */
    val iconKey: String? = (target as? Target.App)?.key,
    val glyph: Glyph = Glyph.NONE,
    /** Folder this tile belongs to (a folder's member tile) or opens (a folder tile), else null. */
    val folderId: String? = null,
)

enum class Glyph { NONE, ALL_APPS, SETTINGS, TILE_SIZE, SEARCH, FOLDER, GAMEPAD, THEME, BACKUP, RESTORE }

/** One template instance on the horizon. Tiles line up with [Template.slots]; extra slots stay empty. */
data class Page(val channel: Int, val template: Template, val tiles: List<Tile>)

data class Channel(val title: String, val firstPage: Int, val pageCount: Int)

class Hub(val channels: List<Channel>, val pages: List<Page>, val layout: Layout) {
    fun channelOf(page: Int) = pages[page].channel
}

data class SettingsEntry(
    val label: String,
    val action: String,
    val iconKey: String? = null,
    val glyph: Glyph = Glyph.SETTINGS,
    val external: Boolean = true,
)

object HubBuilder {
    /**
     * Mirrors dashhome-offline.xml: home, then the content hubs, then apps and settings.
     * Content hubs with nothing in them are dropped, as the dash hides channels it has no content for.
     */
    fun build(
        apps: List<AppInfo>,
        recent: List<String>,
        pins: List<String>,
        settings: List<SettingsEntry>,
        layout: Layout = Layout.LANDSCAPE,
        folders: List<FolderInfo> = emptyList(),
        games360: List<GameInfo> = emptyList(),
        /** Media channels the user hid in settings; games/home/apps/settings always stay. */
        hiddenChannels: Set<String> = emptySet(),
    ): Hub {
        val byKey = apps.associateBy { it.key }
        val alpha = apps.sortedBy { it.label.lowercase() }
        // Folders keep only installed members, in stored order; an all-uninstalled folder vanishes.
        val liveFolders = folders
            .map { f -> f.copy(members = f.members.filter { it in byKey }) }
            .filter { it.members.isNotEmpty() }

        val titles = ArrayList<String>()
        // Channel content as ready-made tiles; home, apps, folder and settings pages are built
        // by their own branches below and keep an empty entry here.
        val content = ArrayList<List<Tile>>()
        fun add(title: String, tiles: List<Tile>) { titles += title; content += tiles }

        val homeOrder = LinkedHashSet<String>().apply {
            pins.filter { it in byKey }.forEach(::add)
            recent.filter { it in byKey }.forEach(::add)
            alpha.forEach { add(it.key) }
        }.map { byKey.getValue(it) }

        val heroKey = homeOrder.firstOrNull()?.key
        val heroSub = when {
            heroKey == null -> ""
            heroKey in pins -> "pinned"
            heroKey in recent -> "recent"
            else -> ""
        }
        add("home", emptyList())
        // Xbox 360 games ride in the games channel, after the Android game apps.
        val xboxTiles = games360.sortedBy { it.name.lowercase() }.map {
            // Games with art show it; the rest keep the drawn controller glyph.
            Tile(it.name, Target.Game(it.path), sub = it.format, glyph = Glyph.GAMEPAD,
                iconKey = if (it.hasArt) GAME_ART + it.path else null)
        }
        for ((title, cat) in listOf("games" to Category.GAME, "tv & movies" to Category.VIDEO, "music" to Category.MUSIC)) {
            if (title in hiddenChannels) continue
            val list = alpha.filter { it.category == cat }.map(::appTile)
            val tiles = if (cat == Category.GAME) list + xboxTiles else list
            // A channel with only Xbox 360 games still appears; other empty hubs are dropped.
            if (tiles.isNotEmpty()) add(title, tiles)
        }
        add("apps", emptyList())
        val appsIndex = titles.size - 1
        // Each live folder becomes its own channel, after apps and before settings.
        val folderByChannel = HashMap<Int, FolderInfo>()
        for (f in liveFolders) {
            folderByChannel[titles.size] = f
            add(f.name, emptyList())
        }
        add("settings", emptyList())
        val settingsIndex = titles.size - 1

        val pages = ArrayList<Page>()
        val channels = ArrayList<Channel>()
        for ((i, title) in titles.withIndex()) {
            val start = pages.size
            when (i) {
                0 -> pages += homePage(layout.home, homeOrder, appsIndex, settingsIndex, heroSub)
                appsIndex -> pages += gridPages(layout.grid, i, appsTiles(liveFolders, folderByChannel, alpha))
                settingsIndex -> pages += gridPages(layout.grid, i, settings.map {
                    Tile(it.label, Target.Action(it.action, it.external), iconKey = it.iconKey,
                        glyph = if (it.iconKey == null) it.glyph else Glyph.NONE)
                })
                in folderByChannel -> {
                    val f = folderByChannel.getValue(i)
                    pages += gridPages(layout.grid, i, f.members.map { appTile(byKey.getValue(it), f.id) })
                }
                else -> pages += contentPages(layout, i, content[i])
            }
            channels += Channel(title, start, pages.size - start)
        }
        return Hub(channels, pages, layout)
    }

    /** Apps channel: the search tile, one tile per live folder, then every app alphabetically. */
    private fun appsTiles(liveFolders: List<FolderInfo>, folderByChannel: Map<Int, FolderInfo>, alpha: List<AppInfo>): List<Tile> {
        val tiles = ArrayList<Tile>()
        tiles += Tile("search", Target.Action(Apps.ACTION_SEARCH, external = false), glyph = Glyph.SEARCH, iconKey = null)
        val channelOf = folderByChannel.entries.associate { (index, f) -> f.id to index }
        for (f in liveFolders) {
            tiles += Tile(
                f.name, Target.Channel(channelOf.getValue(f.id)),
                sub = if (f.members.size == 1) "1 app" else "${f.members.size} apps",
                glyph = Glyph.FOLDER, iconKey = null, folderId = f.id,
            )
        }
        tiles += alpha.map { appTile(it) }
        return tiles
    }

    private fun appTile(a: AppInfo, folderId: String? = null) = Tile(a.label, Target.App(a.key), folderId = folderId)

    private fun homePage(t: Template, order: List<AppInfo>, appsIndex: Int, settingsIndex: Int, heroSub: String): Page {
        val tiles = arrayOfNulls<Tile>(t.slots.size)
        val shortcuts = listOf(
            Tile("all apps", Target.Channel(appsIndex), Kind.WIDE, glyph = Glyph.ALL_APPS),
            Tile("settings", Target.Channel(settingsIndex), Kind.WIDE, glyph = Glyph.SETTINGS),
        )
        if (t.compact) {
            // Keep the last cells for the shortcuts, fill apps, then drop the shortcuts into the
            // first free cells: the reserved ones on a full page, right after the last app otherwise.
            fillTemplate(t, tiles, order, heroSub, skip = t.wide.toSet())
            val free = readingOrder(t).filter { tiles[it] == null }
            shortcuts.forEachIndexed { i, s -> free.getOrNull(i)?.let { tiles[it] = s } }
        } else {
            t.wide.getOrNull(0)?.let { tiles[it] = shortcuts[0] }
            t.wide.getOrNull(1)?.let { tiles[it] = shortcuts[1] }
            fillTemplate(t, tiles, order, heroSub)
        }
        return Page(0, t, tiles.toList().filterNotNullPreservingSlots())
    }

    private fun contentPages(layout: Layout, channel: Int, tiles: List<Tile>): List<Page> {
        val t = layout.home
        val first = arrayOfNulls<Tile>(t.slots.size)
        val used = fillTiles(t, first, tiles)
        val page = Page(channel, t, first.toList().filterNotNullPreservingSlots())
        return listOf(page) + gridPages(layout.grid, channel, tiles.drop(used), allowEmpty = false)
    }

    /** Hero slot takes the first tile (promoted to HERO), the open slots take the rest in reading order. */
    private fun fillTiles(t: Template, tiles: Array<Tile?>, items: List<Tile>): Int {
        var n = 0
        if (items.isNotEmpty() && t.hero >= 0) {
            tiles[t.hero] = items[n++].copy(kind = Kind.HERO)
        }
        val open = readingOrder(t).filter { tiles[it] == null }
        for (slot in open) {
            if (n >= items.size) break
            tiles[slot] = items[n++]
        }
        return n
    }

    /** Hero slot takes the first app, the remaining open slots take the rest in reading order. */
    private fun readingOrder(t: Template) =
        t.slots.indices.sortedWith(compareBy({ t.slots[it].y }, { t.slots[it].x }))

    private fun fillTemplate(t: Template, tiles: Array<Tile?>, apps: List<AppInfo>, heroSub: String, skip: Set<Int> = emptySet()): Int {
        var n = 0
        if (apps.isNotEmpty() && t.hero >= 0) {
            val a = apps[n++]
            tiles[t.hero] = Tile(a.label, Target.App(a.key), Kind.HERO, sub = heroSub)
        }
        // Reading order, with the reserved cells taken off the end of the page.
        val open = readingOrder(t).filter { tiles[it] == null && it !in skip }
        for (slot in open) {
            if (n >= apps.size) break
            tiles[slot] = appTile(apps[n++])
        }
        return n
    }

    private fun gridPages(t: Template, channel: Int, tiles: List<Tile>, allowEmpty: Boolean = true): List<Page> {
        val per = t.slots.size
        if (tiles.isEmpty()) return if (allowEmpty) listOf(Page(channel, t, emptyList())) else emptyList()
        return tiles.chunked(per).map { Page(channel, t, it) }
    }

    private fun List<Tile?>.filterNotNullPreservingSlots(): List<Tile> =
        map { it ?: EMPTY }

    /** Placeholder for template slots with nothing to show; never focusable. */
    val EMPTY = Tile("", Target.Action(""))
}

fun Page.focusable(slot: Int) = slot in tiles.indices && tiles[slot] !== HubBuilder.EMPTY
