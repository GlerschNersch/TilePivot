package dev.x360reborn

import dev.x360reborn.home.AppInfo
import dev.x360reborn.home.Category
import dev.x360reborn.home.GAME_ART
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

    // ---- cover art ---------------------------------------------------------------------------

    private val pngSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun fakePng(n: Int) = pngSig + ByteArray(n - pngSig.size) { 3 }

    /** Synthetic STFS header with optional thumbnails at the XContent offsets. */
    private fun stfsWithThumbs(thumb: ByteArray?, titleThumb: ByteArray?, thumbSize: Int? = null): ByteArray {
        val b = stfsHeader("Art Game", totalBytes = 0x9800)
        fun u32(at: Int, v: Int) { for (k in 0 until 4) b[at + k] = (v ushr (24 - 8 * k)).toByte() }
        if (thumb != null) { u32(0x1712, thumbSize ?: thumb.size); thumb.copyInto(b, 0x171A) }
        if (titleThumb != null) { u32(0x1716, titleThumb.size); titleThumb.copyInto(b, 0x571A) }
        return b
    }

    @Test fun stfsThumbnailReadsTheContentThumbnail() {
        val root = tmp.newFolder("Xbox360")
        val png = fakePng(300)
        val f = file(root, "58410A01", stfsWithThumbs(png, fakePng(200)))
        assertTrue(png.contentEquals(GameScanner.stfsThumbnail(f)!!))
    }

    @Test fun stfsThumbnailFallsBackToTheTitleThumbnail() {
        val root = tmp.newFolder("Xbox360")
        val title = fakePng(200)
        val f = file(root, "58410A02", stfsWithThumbs(null, title))
        assertTrue(title.contentEquals(GameScanner.stfsThumbnail(f)!!))
    }

    @Test fun stfsThumbnailRejectsBadSizesAndNonPng() {
        val root = tmp.newFolder("Xbox360")
        // Size bigger than the 0x4000 slot.
        assertEquals(null, GameScanner.stfsThumbnail(file(root, "a", stfsWithThumbs(fakePng(64), null, thumbSize = 0x4001))))
        // Data without the PNG signature.
        assertEquals(null, GameScanner.stfsThumbnail(file(root, "b", stfsWithThumbs(ByteArray(64) { 1 }, null))))
        // No thumbnails at all (the 17559 system packages look like this).
        assertEquals(null, GameScanner.stfsThumbnail(file(root, "c", stfsWithThumbs(null, null))))
        // Not STFS.
        assertEquals(null, GameScanner.stfsThumbnail(file(root, "d.iso", big)))
    }

    @Test fun godPackageWithThumbnailHasEmbeddedArt() {
        val root = tmp.newFolder("Xbox360")
        file(root, "58410A03", stfsWithThumbs(fakePng(100), null))
        file(root, "58410A04", stfsWithThumbs(null, null))
        val byName = GameScanner.scan(listOf(root)).associateBy { File(it.path).name }
        assertTrue(byName.getValue("58410A03").embeddedArt)
        assertEquals(false, byName.getValue("58410A04").embeddedArt)
    }

    @Test fun sidecarImageIsTheCoverAndWinsOverEmbeddedArt() {
        val root = tmp.newFolder("Xbox360")
        file(root, "Skate 3 (USA, Europe).iso", big)
        val png = file(root, "Skate 3 (USA, Europe).PNG", ByteArray(10))
        file(root, "58410A05", stfsWithThumbs(fakePng(100), null))
        val jpg = file(root, "58410A05.jpg", ByteArray(10))
        val games = GameScanner.scan(listOf(root)).associateBy { File(it.path).name }
        assertEquals(png.absolutePath, games.getValue("Skate 3 (USA, Europe).iso").cover)
        val god = games.getValue("58410A05")
        assertEquals(jpg.absolutePath, god.cover)
        assertEquals(false, god.embeddedArt)
    }

    @Test fun folderGameFindsCoverInsideItsFolder() {
        val root = tmp.newFolder("Xbox360")
        val dir = File(root, "Folder Game")
        file(dir, "default.xex", big)
        val cover = file(dir, "Boxart.jpg", ByteArray(10))
        assertEquals(cover.absolutePath, GameScanner.scan(listOf(root))[0].cover)
    }

    @Test fun artCandidatesPreferTheGamesOwnNameThenGenericNames() {
        val c = GameScanner.artCandidates("Skate 3")
        assertEquals(listOf("Skate 3.jpg", "Skate 3.png", "Skate 3.jpeg", "Skate 3.webp"), c.take(4))
        assertTrue(c.indexOf("cover.jpg") > c.indexOf("Skate 3.webp"))
        assertTrue(c.indexOf("cover.jpg") < c.indexOf("boxart.jpg"))
        assertTrue(c.indexOf("boxart.jpg") < c.indexOf("folder.jpg"))
    }

    @Test fun resolveArtSearchesOwnFolderBeforeParent() {
        val own = File("own")
        val parent = File("parent")
        val listing = mapOf(own to arrayOf("cover.jpg"), parent to arrayOf("Game.jpg"))
        // A generic name in the game's own folder beats the game's name one level up.
        assertEquals(File(own, "cover.jpg").absolutePath,
            GameScanner.resolveArt(listOf(own, parent), "Game") { listing[it] })
        // Within one folder, the game's own name beats the generic name.
        val both = mapOf(own to arrayOf("cover.jpg", "Game.png"))
        assertEquals(File(own, "Game.png").absolutePath,
            GameScanner.resolveArt(listOf(own), "Game") { both[it] })
    }

    @Test fun isoFallsBackToTheGenericCoverName() {
        val root = tmp.newFolder("Xbox360")
        file(root, "Plain Game.iso", big)
        val cover = file(root, "cover.JPG", ByteArray(10))
        assertEquals(cover.absolutePath, GameScanner.scan(listOf(root))[0].cover)
    }

    @Test fun folderGameFallsBackToArtBesideTheFolder() {
        val root = tmp.newFolder("Xbox360")
        val dir = File(root, "Folder Game")
        file(dir, "default.xex", big)
        val cover = file(root, "Folder Game.jpg", ByteArray(10))
        assertEquals(cover.absolutePath, GameScanner.scan(listOf(root))[0].cover)
    }

    @Test fun godPackageFallsBackToArtAboveItsFolder() {
        val root = tmp.newFolder("Xbox360")
        val titleDir = File(root, "4D5307D6")
        file(titleDir, "58410A06", stfsWithThumbs(null, null))
        val cover = file(root, "cover.png", ByteArray(10))
        assertEquals(cover.absolutePath, GameScanner.scan(listOf(root)).single().cover)
    }

    @Test fun gamesWithoutArtKeepTheGlyphTile() {
        val root = tmp.newFolder("Xbox360")
        file(root, "No Art.iso", big)
        file(root, "With Art.iso", big)
        file(root, "With Art.png", ByteArray(10))
        val games = GameScanner.scan(listOf(root))
        val hub = HubBuilder.build(emptyList(), emptyList(), emptyList(), settings, games360 = games)
        val tiles = hub.pages.flatMap { it.tiles }.filter { it.target is Target.Game }.associateBy { it.label }
        assertEquals(null, tiles.getValue("No Art").iconKey)
        assertEquals(GAME_ART + File(root, "With Art.iso").absolutePath, tiles.getValue("With Art").iconKey)
        assertEquals(Glyph.GAMEPAD, tiles.getValue("With Art").glyph)
    }

    @Test fun coverSubsamplingKeepsTheShortEdgeAboveTheTarget() {
        assertEquals(1, Covers.sampleSize(64, 64, 300))
        assertEquals(1, Covers.sampleSize(599, 800, 300))
        assertEquals(2, Covers.sampleSize(600, 800, 300))
        assertEquals(4, Covers.sampleSize(1400, 2000, 300))
    }
}
