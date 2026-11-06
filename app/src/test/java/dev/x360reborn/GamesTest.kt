package dev.x360reborn

import dev.x360reborn.home.AppInfo
import dev.x360reborn.home.Category
import dev.x360reborn.home.GameInfo
import dev.x360reborn.home.Glyph
import dev.x360reborn.home.HubBuilder
import dev.x360reborn.home.SettingsEntry
import dev.x360reborn.home.Target
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GamesTest {
    @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

    private val big = ByteArray(2048) { 7 }

    private fun file(parent: File, name: String, bytes: ByteArray): File {
        val f = File(parent, name)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    @Test fun findsIsoAndZarWithCleanedSortedNames() {
        val root = tmp.newFolder("Xbox360")
        file(root, "zeta_quest.zar", big)
        file(root, "Test_Racer.iso", big)
        val games = GameScanner.scan(listOf(root))
        assertEquals(listOf("Test Racer", "zeta quest"), games.map { it.name })
        assertEquals(listOf("ISO", "ZAR"), games.map { it.format })
        assertTrue(games.all { it.path.startsWith(root.absolutePath) })
    }

    @Test fun findsGamesOneFolderDown() {
        val root = tmp.newFolder("Xbox360")
        file(File(root, "sub"), "Nested Game.iso", big)
        val games = GameScanner.scan(listOf(root))
        assertEquals(listOf("Nested Game"), games.map { it.name })
    }

    @Test fun xexFolderGamePointsAtDefaultXex() {
        val root = tmp.newFolder("Xbox360")
        val folder = File(root, "Folder Game")
        val xex = file(folder, "default.xex", byteArrayOf(1, 2, 3))
        // A stray image inside a claimed folder is not a second game.
        file(folder, "cover.iso", big)
        val games = GameScanner.scan(listOf(root))
        assertEquals(1, games.size)
        assertEquals(GameInfo(xex.absolutePath, "Folder Game", "XEX"), games[0])
    }

    @Test fun godFoundByStfsMagic() {
        val root = tmp.newFolder("Xbox360")
        file(root, "Arcade Pack", "LIVE".toByteArray() + ByteArray(64))
        file(root, "Market Game", "PIRS".toByteArray() + ByteArray(64))
        file(root, "random.bin", "XXXX".toByteArray() + ByteArray(64))
        val games = GameScanner.scan(listOf(root))
        assertEquals(listOf("Arcade Pack", "Market Game"), games.map { it.name })
        assertTrue(games.all { it.format == "GOD" })
    }

    /** Synthetic STFS header: [magic] at 0, the display title UTF-16BE at 0x411. */
    private fun stfsHeader(title: String?, totalBytes: Int = 0x2000, magic: String = "LIVE"): ByteArray {
        val b = ByteArray(totalBytes)
        magic.toByteArray().copyInto(b)
        if (title != null && totalBytes > 0x411) {
            val units = title.toByteArray(Charsets.UTF_16BE)
            units.copyInto(b, 0x411, 0, minOf(units.size, totalBytes - 0x411))
        }
        return b
    }

    @Test fun godShowsStfsTitleNotHexFilename() {
        val root = tmp.newFolder("Xbox360")
        file(root, "58410A3F", stfsHeader("Velocity Racer"))
        val games = GameScanner.scan(listOf(root))
        assertEquals(listOf("Velocity Racer"), games.map { it.name })
        assertEquals("GOD", games[0].format)
    }

    @Test fun godFallsBackToFilenameWhenTitleBlankOrTruncated() {
        val root = tmp.newFolder("Xbox360")
        file(root, "Blank Title Game", stfsHeader(null))
        // The header ends before the title field starts: same fallback.
        file(root, "58410B22", stfsHeader("Ignored", totalBytes = 0x200))
        val games = GameScanner.scan(listOf(root))
        assertEquals(listOf("58410B22", "Blank Title Game"), games.map { it.name })
    }

    @Test fun godUsesLaterLanguageSlotWhenEnglishBlank() {
        val root = tmp.newFolder("Xbox360")
        val hdr = stfsHeader(null)
        "Titre Jeu".toByteArray(Charsets.UTF_16BE).copyInto(hdr, 0x411 + 3 * 0x200)
        file(root, "58410C77", hdr)
        assertEquals("Titre Jeu", GameScanner.scan(listOf(root))[0].name)
    }

    @Test fun stfsTitleReachesGameTilesAndHero() {
        val root = tmp.newFolder("Xbox360")
        file(root, "58410A3F", stfsHeader("Velocity Racer"))
        val games = GameScanner.scan(listOf(root))
        val hub = HubBuilder.build(emptyList(), emptyList(), emptyList(), settings, games360 = games)
        val ch = hub.channels.first { it.title == "games" }
        val page = hub.pages[ch.firstPage]
        val hero = page.tiles[page.template.hero]
        assertEquals("Velocity Racer", hero.label)
        assertEquals(Target.Game(games[0].path), hero.target)
    }

    @Test fun skipsJunkHiddenTinyAndTooDeep() {
        val root = tmp.newFolder("Xbox360")
        file(root, "tiny.iso", ByteArray(100))
        file(root, ".secret.iso", big)
        file(File(root, ".hidden"), "Hidden Game.iso", big)
        file(File(root, "a/b"), "Deep Game.iso", big)
        assertEquals(emptyList<GameInfo>(), GameScanner.scan(listOf(root)))
    }

    @Test fun dedupesOverlappingRoots() {
        val root = tmp.newFolder("Xbox360")
        file(root, "Only Game.iso", big)
        assertEquals(1, GameScanner.scan(listOf(root, root)).size)
    }

    private val settings = listOf(SettingsEntry("wi-fi", "x"))

    @Test fun gamesChannelAppearsWithOnly360Games() {
        val apps = listOf(AppInfo("a/A", "Alpha"), AppInfo("v/V", "Vid", Category.VIDEO))
        val games = listOf(
            GameInfo("/x/Zeta.iso", "Zeta", "ISO"),
            GameInfo("/x/Alpha.zar", "Alpha Game", "ZAR"),
        )
        val hub = HubBuilder.build(apps, emptyList(), emptyList(), settings, games360 = games)
        assertEquals(listOf("home", "games", "tv & movies", "apps", "settings"), hub.channels.map { it.title })
        val ch = hub.channels.first { it.title == "games" }
        val page = hub.pages[ch.firstPage]
        // Sorted by name, so the first 360 game takes the hero slot of its channel.
        val hero = page.tiles[page.template.hero]
        assertEquals(Target.Game("/x/Alpha.zar"), hero.target)
        assertEquals("ZAR", hero.sub)
        assertEquals(Glyph.GAMEPAD, hero.glyph)
        val gameTiles = (ch.firstPage until ch.firstPage + ch.pageCount)
            .flatMap { hub.pages[it].tiles }
            .filter { it.target is Target.Game }
        assertEquals(2, gameTiles.size)
    }

    @Test fun androidGamesComeBefore360Tiles() {
        val apps = listOf(AppInfo("g/G", "Game App", Category.GAME), AppInfo("a/A", "Alpha"))
        val games = listOf(GameInfo("/x/Aaa.iso", "Aaa 360", "ISO"))
        val hub = HubBuilder.build(apps, emptyList(), emptyList(), settings, games360 = games)
        val ch = hub.channels.first { it.title == "games" }
        val page = hub.pages[ch.firstPage]
        // Even though the 360 game's name sorts first, the Android game app takes the hero.
        assertEquals(Target.App("g/G"), page.tiles[page.template.hero].target)
        val gameTile = page.tiles.first { it.target is Target.Game }
        assertEquals("ISO", gameTile.sub)
        assertEquals(Glyph.GAMEPAD, gameTile.glyph)
        assertEquals(null, gameTile.iconKey)
    }

    // ---- Games cache (rebuilds reuse the last scan) ----------------------------------------

    @Test fun rebuildsReuseTheCachedScan() {
        val root = tmp.newFolder("Xbox360")
        file(root, "One.iso", big)
        val games = Games(hasAccessFn = { true }, roots = listOf(root), clock = { 0L })
        assertEquals(1, games.load().size)
        file(root, "Two.iso", big)
        // Same minute, not forced: the folders are not walked again.
        assertEquals(1, games.load().size)
        assertEquals(1, games.scans)
    }

    @Test fun forcedLoadRescans() {
        val root = tmp.newFolder("Xbox360")
        file(root, "One.iso", big)
        val games = Games(hasAccessFn = { true }, roots = listOf(root), clock = { 0L })
        games.load()
        file(root, "Two.iso", big)
        assertEquals(2, games.load(force = true).size)
        assertEquals(2, games.found)
        assertEquals(2, games.scans)
    }

    @Test fun staleCacheRescans() {
        val root = tmp.newFolder("Xbox360")
        file(root, "One.iso", big)
        var now = 0L
        val games = Games(hasAccessFn = { true }, roots = listOf(root), clock = { now }, maxAgeMs = 1_000L)
        games.load()
        file(root, "Two.iso", big)
        now = 999L
        assertEquals(1, games.load().size)
        now = 1_000L
        assertEquals(2, games.load().size)
    }

    @Test fun noAccessNeverScansAndDropsTheCache() {
        val root = tmp.newFolder("Xbox360")
        file(root, "One.iso", big)
        var access = true
        val games = Games(hasAccessFn = { access }, roots = listOf(root), clock = { 0L })
        games.load()
        access = false
        assertEquals(0, games.load().size)
        assertEquals(0, games.found)
        // Access back: the dropped cache means a fresh walk, not the old list.
        access = true
        file(root, "Two.iso", big)
        assertEquals(2, games.load().size)
        assertEquals(2, games.scans)
    }
}
