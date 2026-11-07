package dev.x360reborn.home

import dev.x360reborn.Apps
import kotlin.math.max
import kotlin.math.min

enum class Category { GAME, VIDEO, MUSIC, OTHER }

/** A launchable activity, reduced to what layout needs. [key] is the flattened component. */
data class AppInfo(val key: String, val label: String, val category: Category = Category.OTHER)

/** A user-created folder: an ordered set of app keys, shown as its own channel. */
data class FolderInfo(val id: String, val name: String, val members: List<String>)

/**
 * An Xbox 360 game found in a picked folder, launched through the Xendroid+ emulator.
 * [uri] is the game's document URI (the default.xex's for folder games) and its identity
 * in the app. [hostPath] is the absolute path Xendroid+ is handed, or null when the folder's
 * storage cannot provide one (the tile still shows; launching explains why it can't).
 * [cover] is the document URI of an image the user placed beside the game; [embeddedArt]
 * means the game's own STFS header carries a thumbnail (Games on Demand / XBLA).
 */
data class GameInfo(
    val uri: String,
    val name: String,
    val format: String,
    val cover: String? = null,
    val embeddedArt: Boolean = false,
    val hostPath: String? = null,
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
    /**
     * An Xbox 360 game: [uri] identifies it; [hostPath] is the game_uri handed to Xendroid+
     * (the default.xex path for folder games), null when its folder has no direct file path.
     */
    data class Game(val uri: String, val hostPath: String? = null) : Target
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
            Tile(it.name, Target.Game(it.uri, it.hostPath), sub = it.format, glyph = Glyph.GAMEPAD,
                iconKey = if (it.hasArt) GAME_ART + it.uri else null)
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
                0 -> pages += homeChannel(layout, homeOrder, appsIndex, settingsIndex, heroSub)
                appsIndex -> pages += gridChannel(layout.grid, i, appsTiles(liveFolders, folderByChannel, alpha))
                settingsIndex -> pages += gridChannel(layout.grid, i, settings.map {
                    Tile(it.label, Target.Action(it.action, it.external), iconKey = it.iconKey,
                        glyph = if (it.iconKey == null) it.glyph else Glyph.NONE)
                })
                in folderByChannel -> {
                    val f = folderByChannel.getValue(i)
                    val (home, grid) = channelTemplates(layout, f.members.size)
                    pages += heroChannel(home, grid, i, f.members.map { appTile(byKey.getValue(it), f.id) })
                }
                else -> {
                    val (home, grid) = channelTemplates(layout, content[i].size)
                    pages += heroChannel(home, grid, i, content[i])
                }
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

    /**
     * The home channel (0.9.0): the first app rides the hero slot, every other app
     * follows in the scroll grid, and the two wide shortcuts close the grid. The old
     * portrait rule (shortcuts follow the last app) now holds in both orientations;
     * the landscape mosaic's fixed wide cells are gone.
     */
    private fun homeChannel(layout: Layout, order: List<AppInfo>, appsIndex: Int, settingsIndex: Int, heroSub: String): Page {
        val shortcuts = listOf(
            Tile("all apps", Target.Channel(appsIndex), Kind.WIDE, glyph = Glyph.ALL_APPS),
            Tile("settings", Target.Channel(settingsIndex), Kind.WIDE, glyph = Glyph.SETTINGS),
        )
        if (order.isEmpty()) {
            // No apps at all: the shortcuts alone, in a plain hero-less grid.
            val (t, _) = gridTemplate(layout.grid, shortcuts.size)
            return Page(0, t.copy(wide = listOf(0, 1)), shortcuts)
        }
        val gridTiles = order.drop(1).map(::appTile) + shortcuts
        val (t, heroIndex) = scrollTemplate(layout.home, layout.grid, gridTiles.size)
        val tiles = ArrayList<Tile>(gridTiles.size + 1)
        tiles += gridTiles
        tiles.add(heroIndex, Tile(order[0].label, Target.App(order[0].key), Kind.HERO, sub = heroSub))
        // The shortcuts are the grid's last two tiles; record their page slots as wide.
        val wide = (gridTiles.size - 2 until gridTiles.size).map { if (it >= heroIndex) it + 1 else it }
        return Page(0, t.copy(wide = wide), tiles)
    }

    /** A content or folder channel: the first tile is promoted to the hero, the rest scroll. */
    private fun heroChannel(home: Template, grid: Template, channel: Int, tiles: List<Tile>): Page {
        if (tiles.isEmpty()) return Page(channel, grid, emptyList())
        val (t, heroIndex) = scrollTemplate(home, grid, tiles.size - 1)
        val ordered = ArrayList<Tile>(tiles.size)
        ordered += tiles.drop(1)
        ordered.add(heroIndex, tiles[0].copy(kind = Kind.HERO))
        return Page(channel, t, ordered)
    }

    /** A hero-less channel (apps, settings): every tile in the one scroll grid. */
    private fun gridChannel(grid: Template, channel: Int, tiles: List<Tile>): Page {
        val (t, _) = gridTemplate(grid, tiles.size)
        return Page(channel, t, tiles)
    }

    /**
     * The one scroll page of a hero channel (0.9.0): [home] supplies the hero slot,
     * [grid] the grid's tile size and column count (both already carry the channel's
     * boost). The hero keeps its template box, centred in the page, and the grid
     * hangs under it centred the same way, so every channel's hero lands on the
     * layout's canonical rect and the page is exactly as wide as the wider of the
     * two. The hero slot keeps its legacy index in the slot list, so slot indices
     * mean what they meant on the old first page. Returns the template and the
     * hero's slot index.
     */
    private fun scrollTemplate(home: Template, grid: Template, gridCount: Int): Pair<Template, Int> {
        val heroBox = home.slots[home.hero]
        val cell = grid.slots.first()
        val k = cell.w / Metrics.SMALL_W
        val cols = grid.slots.map { it.x }.distinct().size.coerceAtLeast(1)
        val gutter = Metrics.GUTTER * k
        val gridW = cols * cell.w + (cols - 1) * gutter
        val pageW = max(gridW, heroBox.w)
        val gridTop = heroBox.bottom + gutter
        val slots = ArrayList<Box>(gridCount + 1)
        val gx = (pageW - gridW) / 2f
        for (i in 0 until gridCount) {
            slots += Box(gx + (i % cols) * (cell.w + gutter), gridTop + (i / cols) * (cell.h + gutter), cell.w, cell.h)
        }
        val heroIndex = min(home.hero, gridCount)
        slots.add(heroIndex, Box((pageW - heroBox.w) / 2f, heroBox.y, heroBox.w, heroBox.h))
        val h = if (gridCount > 0) gridTop + Scroll.contentHeight(Scroll.rowsFor(gridCount, cols), cell.h, gutter)
            else heroBox.bottom
        return Template(pageW, h, slots, hero = heroIndex, gridTop = gridTop) to heroIndex
    }

    /** The one scroll page of a hero-less channel: [count] tiles of [grid]'s size and columns. */
    private fun gridTemplate(grid: Template, count: Int): Pair<Template, Int> {
        val cell = grid.slots.firstOrNull()
            ?: return Template(0f, 0f, emptyList(), hero = -1) to -1
        val k = cell.w / Metrics.SMALL_W
        val cols = grid.slots.map { it.x }.distinct().size.coerceAtLeast(1)
        val gutter = Metrics.GUTTER * k
        val slots = ArrayList<Box>(count)
        for (i in 0 until count) {
            slots += Box((i % cols) * (cell.w + gutter), (i / cols) * (cell.h + gutter), cell.w, cell.h)
        }
        val w = cols * cell.w + (cols - 1) * gutter
        val h = Scroll.contentHeight(Scroll.rowsFor(count, cols), cell.h, gutter)
        return Template(w, h, slots, hero = -1) to -1
    }

    /**
     * Templates for a content or folder channel: a sparse channel renders at a
     * boosted effective size (see [Metrics.effectiveSize]); home, apps and
     * settings always use the layout's own templates.
     */
    private fun channelTemplates(layout: Layout, tileCount: Int): Pair<Template, Template> =
        layout.channelTemplates(Metrics.effectiveSize(layout.size, tileCount))

    /** Placeholder for template slots with nothing to show; never focusable. */
    val EMPTY = Tile("", Target.Action(""))
}

fun Page.focusable(slot: Int) = slot in tiles.indices && tiles[slot] !== HubBuilder.EMPTY
