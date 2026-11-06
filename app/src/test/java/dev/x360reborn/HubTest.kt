package dev.x360reborn

import dev.x360reborn.home.Anim
import dev.x360reborn.home.AppInfo
import dev.x360reborn.home.BgStyle
import dev.x360reborn.home.Box
import dev.x360reborn.home.Category
import dev.x360reborn.home.FolderInfo
import dev.x360reborn.home.Glyph
import dev.x360reborn.home.HubBuilder
import dev.x360reborn.home.Kind
import dev.x360reborn.home.Layout
import dev.x360reborn.home.TileSize
import dev.x360reborn.home.Metrics
import dev.x360reborn.home.Motion
import dev.x360reborn.home.Nav
import dev.x360reborn.home.Page
import dev.x360reborn.home.Search
import dev.x360reborn.home.SettingsEntry
import dev.x360reborn.home.Target
import dev.x360reborn.home.Themes
import dev.x360reborn.home.focusable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubTest {
    private val apps = (1..30).map { AppInfo("p$it/A", "App %02d".format(it), if (it <= 3) Category.GAME else Category.OTHER) }
    private val settings = listOf(SettingsEntry("wi-fi", "x"))

    @Test fun template1hdKeepsFourPixelGutters() {
        val s = Metrics.HOME.slots
        assertEquals(178f, s[1].x, 0f)
        assertEquals(s[1].right + Metrics.GUTTER, s[2].x, 0f)
        assertEquals(Metrics.HOME.h, s[8].bottom, 0f)
        assertEquals(Metrics.HOME.w, s[8].right, 0f)
    }

    @Test fun channelsFollowDashOrderAndDropEmptyHubs() {
        val hub = HubBuilder.build(apps, emptyList(), emptyList(), settings)
        assertEquals(listOf("home", "games", "apps", "settings"), hub.channels.map { it.title })
    }

    @Test fun heroIsPinThenMostRecent() {
        val hub = HubBuilder.build(apps, recent = listOf("p7/A", "p9/A"), pins = listOf("p20/A"), settings = settings)
        val home = hub.pages[0]
        assertEquals(Kind.HERO, home.tiles[home.template.hero].kind)
        assertEquals(Target.App("p20/A"), home.tiles[home.template.hero].target)
        assertEquals(Target.App("p7/A"), home.tiles[0].target)
    }

    @Test fun appsChannelPagesHoldFifteen() {
        val hub = HubBuilder.build(apps, emptyList(), emptyList(), settings)
        val ch = hub.channels.first { it.title == "apps" }
        // 30 apps + the search tile at the head of the channel = 31 tiles, so 3 pages of 15.
        assertEquals(3, ch.pageCount)
        assertEquals(15, hub.pages[ch.firstPage].tiles.size)
    }

    @Test fun searchReturnsAllAlphabeticallyForEmptyQuery() {
        val list = listOf(AppInfo("b/B", "beta"), AppInfo("a/A", "Alpha"), AppInfo("c/C", "camera"))
        assertEquals(listOf("Alpha", "beta", "camera"), Search.filter(list, "  ").map { it.label })
    }

    @Test fun searchRanksStartsWithBeforeContains() {
        val list = listOf(
            AppInfo("w/W", "Webcam"),
            AppInfo("c/C", "Camera"),
            AppInfo("s/S", "Cam Scanner"),
            AppInfo("m/M", "Maps"),
        )
        // Trimmed, case-insensitive; starts-with group first, alphabetical within each group.
        assertEquals(listOf("Cam Scanner", "Camera", "Webcam"), Search.filter(list, " cam ").map { it.label })
        assertEquals(emptyList<AppInfo>(), Search.filter(list, "zzz"))
    }

    private val folders = listOf(
        FolderInfo("f1", "My Folder", listOf("p2/A", "p1/A")),
        FolderInfo("f2", "Games Bin", listOf("p3/A")),
    )

    @Test fun folderChannelsLandAfterAppsAndBeforeSettings() {
        val hub = HubBuilder.build(apps, emptyList(), emptyList(), settings, folders = folders)
        assertEquals(listOf("home", "games", "apps", "My Folder", "Games Bin", "settings"), hub.channels.map { it.title })
    }

    @Test fun appsPageStartsWithSearchThenFolderTiles() {
        val hub = HubBuilder.build(apps, emptyList(), emptyList(), settings, folders = folders)
        val ch = hub.channels.first { it.title == "apps" }
        val tiles = hub.pages[ch.firstPage].tiles
        assertEquals("search", tiles[0].label)
        assertEquals(Glyph.SEARCH, tiles[0].glyph)
        assertEquals(Target.Action(dev.x360reborn.Apps.ACTION_SEARCH, false), tiles[0].target)
        val folderTile = tiles[1]
        assertEquals("My Folder", folderTile.label)
        assertEquals(Glyph.FOLDER, folderTile.glyph)
        assertEquals("f1", folderTile.folderId)
        assertEquals("2 apps", folderTile.sub)
        assertEquals(Target.Channel(hub.channels.indexOfFirst { it.title == "My Folder" }), folderTile.target)
        assertEquals("1 app", tiles[2].sub)
        // Then the apps themselves, alphabetically.
        assertEquals(Target.App("p1/A"), tiles[3].target)
    }

    @Test fun folderMemberTilesCarryFolderIdInStoredOrder() {
        val hub = HubBuilder.build(apps, emptyList(), emptyList(), settings, Layout.portrait(1080, 2400), folders = folders)
        val ch = hub.channels.first { it.title == "My Folder" }
        // The first member rides the hero slot now; the app tiles keep stored order.
        val tiles = hub.pages[ch.firstPage].tiles.filter { it.target is Target.App }
        assertEquals(listOf("p2/A", "p1/A"), tiles.map { (it.target as Target.App).key })
        assertTrue(tiles.all { it.folderId == "f1" })
    }

    // ---- canonical hero (0.6.1) ----------------------------------------------

    /** The hero rect of [page] in layout space, placed the way HomeView places a page. */
    private fun heroRectOf(layout: Layout, page: Page): Box {
        val t = page.template
        val s = t.slots[t.hero]
        return Box(
            (layout.w - t.w) / 2f + s.x,
            layout.panelsY + (layout.panelsH - t.h) / 2f + s.y,
            s.w, s.h,
        )
    }

    @Test fun heroRectIsCanonicalAcrossChannels() {
        val mixed = (1..30).map { AppInfo("p$it/A", "App %02d".format(it), Category.OTHER) } +
            gameApps(13) +
            (1..3).map { AppInfo("v$it/A", "Video $it", Category.VIDEO) } +
            (1..4).map { AppInfo("m$it/A", "Music $it", Category.MUSIC) }
        val folder = FolderInfo("f9", "My Folder", listOf("p2/A", "p1/A"))
        for (size in TileSize.entries) {
            val layout = Layout.portrait(1080, 2400, size)
            val hub = HubBuilder.build(mixed, emptyList(), emptyList(), settings, layout, folders = listOf(folder))
            val rects = listOf("home", "games", "tv & movies", "music", "My Folder").map { title ->
                val ch = hub.channels.first { it.title == title }
                heroRectOf(layout, hub.pages[ch.firstPage])
            }
            for (r in rects.drop(1)) {
                assertEquals("hero x at $size", rects[0].x, r.x, 0.01f)
                assertEquals("hero y at $size", rects[0].y, r.y, 0.01f)
                assertEquals("hero w at $size", rects[0].w, r.w, 0.01f)
                assertEquals("hero h at $size", rects[0].h, r.h, 0.01f)
            }
            // The shared rect is the layout's canonical hero, at the panels' top.
            assertEquals(layout.panelsY + layout.heroBox.y, rects[0].y, 0.01f)
            assertEquals(layout.heroBox.w, rects[0].w, 0.01f)
            assertEquals(layout.heroBox.h, rects[0].h, 0.01f)
        }
    }

    @Test fun canonicalHeroTakesTheBoostedGamesHeight() {
        // At SMALL the games channel's +1 boost step renders its hero at
        // 264 * (708/530); the canonical hero is exactly that, at full width.
        val small = Layout.portrait(1080, 2400, TileSize.SMALL)
        assertEquals(708f, small.heroBox.w, 0.01f)
        assertEquals(264f * 708f / 530f, small.heroBox.h, 0.5f)
        // At LARGE nothing boosts past it: the hero is the portrait template's own.
        assertEquals(Box(0f, 0f, 352f, 198f), Layout.portrait(1080, 2400, TileSize.LARGE).heroBox)
    }

    @Test fun appsAndSettingsChannelsStayHeroLess() {
        val layout = Layout.portrait(1080, 2400, TileSize.SMALL)
        val hub = HubBuilder.build(apps, emptyList(), emptyList(), settings, layout)
        for (title in listOf("apps", "settings")) {
            val ch = hub.channels.first { it.title == title }
            val page = hub.pages[ch.firstPage]
            assertEquals(-1, page.template.hero)
            assertTrue(page.tiles.none { it.kind == Kind.HERO })
        }
    }

    @Test fun folderWithOnlyUninstalledMembersIsDropped() {
        val ghost = listOf(FolderInfo("f9", "Ghost", listOf("nope/X", "gone/Y")))
        val hub = HubBuilder.build(apps, emptyList(), emptyList(), settings, folders = ghost)
        assertEquals(listOf("home", "games", "apps", "settings"), hub.channels.map { it.title })
        val ch = hub.channels.first { it.title == "apps" }
        assertTrue(hub.pages[ch.firstPage].tiles.none { it.folderId == "f9" })
    }

    @Test fun navigationMovesAcrossTheHeroTile() {
        val s = Metrics.HOME.slots
        val all = { _: Int -> true }
        assertEquals(1, Nav.move(s, all, 0, 1, 0))
        assertEquals(2, Nav.move(s, all, 1, 1, 0))
        assertEquals(-1, Nav.move(s, all, 0, 0, -1))
        assertEquals(6, Nav.move(s, all, 1, 0, 1))
        assertEquals(1, Nav.move(s, all, 4, -1, 0))
    }

    @Test fun emptySlotsAreSkipped() {
        val hub = HubBuilder.build(apps.take(2), emptyList(), emptyList(), settings)
        val home = hub.pages[0]
        assertTrue(home.focusable(home.template.hero))
        assertTrue(!home.focusable(8))
    }

    @Test fun portraitTemplatesFitAndDoNotOverlap() {
        for ((w, h) in listOf(1272 to 2772, 1080 to 2400, 1080 to 1920)) for (size in TileSize.entries) {
            val l = Layout.portrait(w, h, size)
            assertEquals(size.portraitCols * Metrics.SMALL_W + (size.portraitCols - 1) * Metrics.GUTTER + 68f, l.w, 0.01f)
            for (t in listOf(l.home, l.grid)) {
                assertTrue("$w x $h $size", l.panelsY + t.h <= l.h)
                assertTrue(t.w <= l.w)
                for (a in t.slots.indices) for (b in t.slots.indices) if (a < b) {
                    val p = t.slots[a]; val q = t.slots[b]
                    assertTrue(p.right <= q.x || q.right <= p.x || p.bottom <= q.y || q.bottom <= p.y)
                }
            }
            assertTrue(l.twistY >= 0f)
        }
    }

    @Test fun smallerSizesFitMoreTiles() {
        val counts = TileSize.entries.map { Layout.portrait(1272, 2772, it).grid.slots.size }
        assertTrue(counts == counts.sorted() && counts.distinct().size == counts.size)
        val land = TileSize.entries.map { Layout.landscape(2772, 1272, it).grid.slots.size }
        assertEquals(15, land[0])
        assertTrue(land == land.sorted() && land.last() > land.first())
        assertEquals(TileSize.SMALL, TileSize.SMALL.smaller())
        assertEquals(TileSize.LARGE, TileSize.SMALL.next())
    }

    @Test fun shortcutsFollowTheLastAppOnSparsePages() {
        val l = Layout.portrait(1080, 2400, TileSize.SMALL)
        val hub = HubBuilder.build(apps.take(5), emptyList(), emptyList(), settings, l)
        val home = hub.pages[0]
        // Hero + 4 apps, then the two shortcuts in the next cells, nothing after them.
        val labels = home.tiles.map { it.label }
        assertEquals(listOf("App 01", "App 02", "App 03", "App 04", "App 05", "all apps", "settings"), labels.take(7))
        assertTrue(labels.drop(7).all { it.isEmpty() })
        // The hero slot is the layout's canonical hero (0.6.1) -- the boosted games
        // height, taller than the two tile rows the old home banner was capped at.
        assertEquals(l.heroBox, home.template.slots[home.template.hero])
    }

    @Test fun portraitHubKeepsHeroAndShortcuts() {
        val l = Layout.portrait(1272, 2772)
        val hub = HubBuilder.build(apps, emptyList(), emptyList(), settings, l)
        val home = hub.pages[0]
        assertEquals(Kind.HERO, home.tiles[home.template.hero].kind)
        assertEquals("all apps", home.tiles[home.template.wide[0]].label)
        assertEquals(10, hub.pages[hub.channels.first { it.title == "apps" }.firstPage].tiles.size)
        // Down from the hero lands on the left tile of the first row.
        assertEquals(1, Nav.move(l.home.slots, { true }, home.template.hero, 0, 1))
    }

    @Test fun sceneTransitionsMatchHubsceneTimeline() {
        val f = Metrics.FRAME_MS
        // TransTo: held at 0.8/transparent for 4 frames, then linear to 1.0/opaque by frame 45.
        assertEquals(Pair(0.8f, 0f), dev.x360reborn.home.Trans.TO.at(3 * f))
        val mid = dev.x360reborn.home.Trans.TO.at((4 + 20.5f) * f)
        assertEquals(0.9f, mid.first, 1e-4f)
        assertEquals(0.5f, mid.second, 1e-4f)
        assertEquals(Pair(1.5f, 0f), dev.x360reborn.home.Trans.FROM.at(28 * f))
        assertEquals(Pair(1f, 1f), dev.x360reborn.home.Trans.BACK_TO.at(50 * f))
        assertEquals(750f, dev.x360reborn.home.Trans.TO.totalMs, 0.01f)
    }

    @Test fun themeRegistryIsUniqueWithXboxDefault() {
        assertEquals(Themes.all.size, Themes.all.map { it.id }.toSet().size)
        assertEquals(5, Themes.all.size)
        assertEquals("xbox", Themes.default.id)
        // The default theme carries the measured pre-theme constants, so the look is unchanged.
        assertEquals(0xFF008A00.toInt(), Themes.default.tile)
        assertEquals(0xFF76777B.toInt(), Themes.default.bgTop)
        assertEquals(0xFFCDCDCD.toInt(), Themes.default.bgBottom)
        assertEquals(Themes.default, Themes.byId(null))
        assertEquals(Themes.default, Themes.byId("no-such-theme"))
        assertEquals(Themes.MIDNIGHT, Themes.byId("midnight"))
        assertEquals(Themes.CARBON, Themes.byId("carbon"))
    }

    @Test fun bgStyleNamesRoundTripAndCycle() {
        for (style in BgStyle.entries) assertEquals(style, BgStyle.byName(style.name))
        assertEquals(BgStyle.GRADIENT, BgStyle.byName(null))
        assertEquals(BgStyle.GRADIENT, BgStyle.byName("bogus"))
        assertEquals(BgStyle.SOLID, BgStyle.GRADIENT.next())
        assertEquals(BgStyle.DIMMED, BgStyle.SOLID.next())
        assertEquals(BgStyle.GRADIENT, BgStyle.DIMMED.next())
    }

    @Test fun hiddenMediaChannelsAreDropped() {
        val mixed = listOf(
            AppInfo("g/G", "Game", Category.GAME),
            AppInfo("v/V", "Video", Category.VIDEO),
            AppInfo("m/M", "Music", Category.MUSIC),
        )
        val both = HubBuilder.build(mixed, emptyList(), emptyList(), settings, hiddenChannels = setOf("tv & movies", "music"))
        assertEquals(listOf("home", "games", "apps", "settings"), both.channels.map { it.title })
        val one = HubBuilder.build(mixed, emptyList(), emptyList(), settings, hiddenChannels = setOf("music"))
        assertEquals(listOf("home", "games", "tv & movies", "apps", "settings"), one.channels.map { it.title })
        // Games is not a media channel: hiding it is not offered and has no effect here.
        val games = HubBuilder.build(mixed, emptyList(), emptyList(), settings, hiddenChannels = setOf("tv & movies"))
        assertEquals(listOf("home", "games", "music", "apps", "settings"), games.channels.map { it.title })
    }

    @Test fun gridIconsFillTheirTile() {
        // The standard 174x130 tile: 56 design px before 0.4.1, 96 (55% of width) after.
        assertEquals(96f, Metrics.gridIconSize(174f, 130f, hasSub = false), 0f)
        // Two-line tiles keep room for the sub: height-capped at 80 (46% of width).
        assertEquals(80f, Metrics.gridIconSize(174f, 130f, hasSub = true), 0f)
        // Wide home slots share the grid tiles' height cap, so a row sits evenly.
        assertEquals(96f, Metrics.gridIconSize(231f, 130f, hasSub = false), 0f)
        // Narrower tiles follow the width fraction instead.
        assertEquals(58f, Metrics.gridIconSize(100f, 130f, hasSub = false), 0f)
    }

    @Test fun fitSizeFramesTheWholeImage() {
        // Portrait box art into a wide hero area: the height is the binding edge.
        val (w, h) = Metrics.fitSize(600f, 800f, 668f, 200f)
        assertEquals(200f, h, 0.01f)
        assertEquals(150f, w, 0.01f)
        // A narrow area binds on width instead.
        val (w2, h2) = Metrics.fitSize(600f, 800f, 90f, 200f)
        assertEquals(90f, w2, 0.01f)
        assertEquals(120f, h2, 0.01f)
        // A degenerate source fits to nothing rather than dividing by zero.
        assertEquals(0f to 0f, Metrics.fitSize(0f, 800f, 90f, 200f))
    }

    @Test fun coverSrcCropsSquareIconToTile() {
        // Square 288px icon into the standard 174x130 tile: full width is kept, and the
        // middle 215 of 288 rows (74.7% of the height) -- the rest is centre-cropped.
        val c = Metrics.coverSrc(288f, 288f, 174f, 130f)
        assertEquals(0f, c.left, 0.01f)
        assertEquals(288f, c.width, 0.01f)
        assertEquals(215.17f, c.height, 0.01f)
        assertEquals(36.41f, c.top, 0.01f)
        assertEquals(288f, c.right, 0.01f)
        assertEquals(251.59f, c.bottom, 0.01f)
    }

    @Test fun coverSrcCropsTallAndWideSources() {
        // Tall source: full width kept, height cropped symmetrically.
        val tall = Metrics.coverSrc(200f, 400f, 174f, 130f)
        assertEquals(0f, tall.left, 0.01f)
        assertEquals(200f, tall.width, 0.01f)
        assertEquals(149.43f, tall.height, 0.01f)
        assertEquals(125.29f, tall.top, 0.01f)
        // Wide source: full height kept, width cropped symmetrically.
        val wide = Metrics.coverSrc(400f, 200f, 174f, 130f)
        assertEquals(0f, wide.top, 0.01f)
        assertEquals(200f, wide.height, 0.01f)
        assertEquals(267.69f, wide.width, 0.01f)
        assertEquals(66.15f, wide.left, 0.01f)
        // Same aspect as the tile: the whole source is used, nothing cropped.
        val same = Metrics.coverSrc(348f, 260f, 174f, 130f)
        assertEquals(0f, same.left, 0.01f)
        assertEquals(0f, same.top, 0.01f)
        assertEquals(348f, same.width, 0.01f)
        assertEquals(260f, same.height, 0.01f)
    }

    @Test fun hoverChromeMatchesMeasuredLandscapePositions() {
        // HubSceneStandard: back 55,27 / home 610,27 / search 1165,27, all 60x60 at 1280 wide.
        val boxes = Metrics.hoverChromeBoxes(1280f, portrait = false, twistX = 153f)
        assertEquals(Box(55f, 27f, 60f, 60f), boxes[0])
        assertEquals(Box(610f, 27f, 60f, 60f), boxes[1])
        assertEquals(Box(1165f, 27f, 60f, 60f), boxes[2])
    }

    @Test fun hoverChromePortraitStaysOnScreen() {
        val boxes = Metrics.hoverChromeBoxes(420f, portrait = true, twistX = 34f)
        assertEquals(3, boxes.size)
        assertEquals(48f, boxes[0].w, 0.001f)
        assertTrue(boxes.all { it.x >= 0f && it.right <= 420f && it.y >= 0f })
        assertTrue(boxes[0].right <= boxes[1].x && boxes[1].right <= boxes[2].x)
    }

    @Test fun gridGlyphsDrawLargeOnFilledTiles() {
        // 85% of the short side, regardless of how wide the tile is.
        assertEquals(110.5f, Metrics.gridGlyphSize(174f, 130f), 0.01f)
        assertEquals(110.5f, Metrics.gridGlyphSize(231f, 130f), 0.01f)
    }

    @Test fun heroIconsGrowButKeepTheLabelClear() {
        // Matt's phone portrait hero (708x264, with a sub): 121 before, 194 after.
        assertEquals(194f, Metrics.heroIconSize(708f, 264f, hasSub = true), 0f)
        assertEquals(205.92f, Metrics.heroIconSize(708f, 264f, hasSub = false), 0.01f)
        // The icon always fits above the hero's label block.
        val h = 198f
        val icon = Metrics.heroIconSize(352f, h, hasSub = true)
        assertTrue(icon <= Metrics.heroIconAreaH(h, hasSub = true))
    }

    @Test fun labelScaleFitsOrFloors() {
        // Already fits: no shrink. Barely over: shrinks by exactly the ratio.
        assertEquals(1f, Metrics.labelScale(100f, 154f), 0f)
        assertEquals(0.77f, Metrics.labelScale(200f, 154f), 0.01f)
        // Far too wide: stops at the floor so callers wrap instead of shrinking further.
        assertEquals(Metrics.LABEL_MIN_SCALE, Metrics.labelScale(400f, 154f), 0f)
        assertEquals(1f, Metrics.labelScale(0f, 154f), 0f)
    }

    @Test fun splitLabelPrefersTheStateColon() {
        assertEquals(
            "xbox 360 games:" to "grant folder access",
            Metrics.splitLabel("xbox 360 games: grant folder access"),
        )
        assertEquals("theme:" to "xbox green", Metrics.splitLabel("theme: xbox green"))
        // No separator: split at the space nearest the middle; no space at all: no split.
        assertEquals("hello wide" to "world", Metrics.splitLabel("hello wide world"))
        assertEquals(null, Metrics.splitLabel("oneword"))
    }

    @Test fun focusCurveMatchesTimeline() {
        assertEquals(1.08f, Motion.scale(1f, -1f), 1e-5f)
        assertEquals(1f, Motion.scale(1f, Metrics.PRESS_DOWN_FRAMES * Metrics.FRAME_MS), 1e-4f)
        assertEquals(Metrics.FLASH_PEAK, Motion.flash(Metrics.FLASH_PEAK_AT, true), 1e-5f)
    }

    // ---- sparse-channel boost ---------------------------------------------------

    private fun gameApps(n: Int) = (1..n).map { AppInfo("g$it/A", "Game %02d".format(it), Category.GAME) }

    @Test fun boostStepsFollowTheCountBands() {
        assertEquals(2, Metrics.boostStepsFor(1))
        assertEquals(2, Metrics.boostStepsFor(6))
        assertEquals(1, Metrics.boostStepsFor(7))
        assertEquals(1, Metrics.boostStepsFor(18))
        assertEquals(0, Metrics.boostStepsFor(19))
        assertEquals(0, Metrics.boostStepsFor(60))
    }

    @Test fun effectiveSizeStepsUpButNeverPastLarge() {
        assertEquals(TileSize.LARGE, Metrics.effectiveSize(TileSize.SMALL, 4))
        assertEquals(TileSize.MEDIUM, Metrics.effectiveSize(TileSize.SMALL, 13))
        assertEquals(TileSize.LARGE, Metrics.effectiveSize(TileSize.MEDIUM, 13))
        assertEquals(TileSize.LARGE, Metrics.effectiveSize(TileSize.LARGE, 1))
        assertEquals(TileSize.MEDIUM, Metrics.effectiveSize(TileSize.MEDIUM, 40))
    }

    @Test fun sparseGamesChannelFillsThePanelAtMedium() {
        val layout = Layout.portrait(1080, 2400, TileSize.MEDIUM)
        val hub = HubBuilder.build(gameApps(13), emptyList(), emptyList(), settings, layout)
        val ch = hub.channels.first { it.title == "games" }
        val first = hub.pages[ch.firstPage]
        // Boosted tiles are bigger than the layout's own small tiles...
        assertTrue(first.template.slots[2].w > layout.home.slots[2].w)
        // ...the boosted template still fits the panel height...
        assertTrue(first.template.h <= layout.panelsH + 0.01f)
        // ...and the 13 tiles' first page fills at least 75% of the panel height.
        val usedBottom = first.template.slots.filterIndexed { i, _ -> first.focusable(i) }.maxOf { it.bottom }
        assertTrue("fill ${usedBottom / layout.panelsH}", usedBottom / layout.panelsH >= 0.75f)
    }

    @Test fun denseGamesChannelKeepsTheLayoutTemplates() {
        val layout = Layout.portrait(1080, 2400, TileSize.MEDIUM)
        val hub = HubBuilder.build(gameApps(30), emptyList(), emptyList(), settings, layout)
        val ch = hub.channels.first { it.title == "games" }
        assertEquals(layout.home, hub.pages[ch.firstPage].template)
        assertEquals(layout.grid, hub.pages[ch.firstPage + 1].template)
    }

    @Test fun appsAndSettingsChannelsAreNeverBoosted() {
        val layout = Layout.portrait(1080, 2400, TileSize.SMALL)
        val hub = HubBuilder.build(gameApps(2), emptyList(), emptyList(), settings, layout)
        val appsCh = hub.channels.first { it.title == "apps" }
        assertEquals(layout.grid, hub.pages[appsCh.firstPage].template)
        val setCh = hub.channels.first { it.title == "settings" }
        assertEquals(layout.grid, hub.pages[setCh.firstPage].template)
    }

    @Test fun folderTilesBoostTowardLargeButNotPastIt() {
        val layout = Layout.portrait(1080, 2400, TileSize.SMALL)
        val folder = FolderInfo("f1", "Solo", listOf("g1/A", "g2/A", "g3/A"))
        val hub = HubBuilder.build(gameApps(3), emptyList(), emptyList(), settings, layout, folders = listOf(folder))
        val ch = hub.channels.first { it.title == "Solo" }
        val page = hub.pages[ch.firstPage]
        val t = page.template
        // The first member is the folder's hero now, on the canonical rect.
        assertEquals(Kind.HERO, page.tiles[t.hero].kind)
        assertEquals(layout.heroBox, t.slots[t.hero])
        // The member tiles still boost past the layout's own grid size...
        assertTrue(t.slots[1].w > layout.grid.slots[0].w)
        // ...capped at the LARGE geometry: a tile's share of the layout width is
        // no more than a LARGE tile's share of a LARGE layout's width.
        val large = Layout.portrait(1080, 2400, TileSize.LARGE)
        assertTrue(t.slots[1].w / layout.w <= large.grid.slots[0].w / large.w + 0.01f)
        assertTrue(t.h <= layout.panelsH + 0.01f)
    }

    @Test fun cascadeDelayRunsDiagonallyFromTopLeft() {
        assertEquals(0f, Anim.delayFor(0, 0), 0f)
        // The wave is diagonal: a step right costs the same as a step down.
        assertEquals(Anim.delayFor(1, 0), Anim.delayFor(0, 1), 0f)
        assertEquals(Anim.delayFor(2, 1), Anim.delayFor(1, 2), 0f)
        assertEquals(3 * Anim.STAGGER_MS, Anim.delayFor(2, 1), 0f)
    }

    @Test fun cascadeProgressWaitsForDelayAndEndsOnTime() {
        val d = Anim.delayFor(1, 1)
        assertEquals(0f, Anim.progress(d - 1f, d), 0f)
        assertEquals(0f, Anim.progress(d, d), 0f)
        assertEquals(0.5f, Anim.progress(d + Anim.TILE_MS / 2, d), 0.001f)
        assertEquals(1f, Anim.progress(d + Anim.TILE_MS, d), 0f)
        assertEquals(1f, Anim.progress(d + Anim.TILE_MS * 3, d), 0f)
    }

    @Test fun cascadeEntryStartsSmallAndLowAndSettlesExact() {
        val (s0, r0) = Anim.entryAt(0f, 0f)
        assertEquals(Anim.FROM_SCALE, s0, 0.0001f)
        assertEquals(Anim.RISE, r0, 0.0001f)
        // Before its delay a tile still sits at the start pose.
        val (s2, r2) = Anim.entryAt(10f, 100f)
        assertEquals(Anim.FROM_SCALE, s2, 0.0001f)
        assertEquals(Anim.RISE, r2, 0.0001f)
        // Settled is exactly rest, so the end state matches the un-animated layout.
        val (s1, r1) = Anim.entryAt(Anim.TILE_MS, 0f)
        assertEquals(1f, s1, 0.0001f)
        assertEquals(0f, r1, 0.0001f)
    }

    @Test fun cascadeOvershootIsSingleBoundedAndSmall() {
        var peak = 0f
        var peaks = 0
        var prev = Anim.entryAt(0f, 0f).first
        var falling = false
        val steps = 260
        var minRise = 0f
        for (i in 1..steps) {
            val (s, r) = Anim.entryAt(Anim.TILE_MS * i / steps, 0f)
            if (s > peak) peak = s
            if (!falling && s < prev) { peaks++; falling = true }
            if (r < minRise) minRise = r
            prev = s
        }
        assertTrue("overshoot should read (peak $peak)", peak > 1f)
        assertTrue("overshoot stays a landing, not jelly (peak $peak)", peak <= 1.04f)
        assertEquals(1, peaks)
        // The rise overshoots upward by less than 2 px before returning to 0.
        assertTrue("rise overshoot bounded ($minRise)", minRise > -2f)
    }

    @Test fun cascadeTotalCoversTheLastTile() {
        val d = Anim.delayFor(3, 4)
        assertEquals(d + Anim.TILE_MS, Anim.totalMs(d), 0f)
        assertEquals(1f, Anim.progress(Anim.totalMs(d), d), 0f)
        // The deepest tile of a full portrait page (row 4, col 3) settles in ~600 ms.
        assertTrue(Anim.totalMs(Anim.delayFor(4, 3)) <= 600f)
    }
}
