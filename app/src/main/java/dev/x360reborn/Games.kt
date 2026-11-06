package dev.x360reborn

import android.os.Environment
import dev.x360reborn.home.GameInfo
import java.io.File

/**
 * Pure scan logic for Xbox 360 game files (kept Android-free so unit tests can run it on
 * temp directories). Recognised forms, mirroring what Xendroid+ itself finds:
 * ISO/ZAR files, folders holding a default.xex, and STFS containers (GOD / XBLA) whose
 * first four bytes are the magic "LIVE", "CON " or "PIRS".
 */
object GameScanner {
    private val STFS_MAGIC = listOf("LIVE", "CON ", "PIRS")
    private const val MIN_IMAGE_BYTES = 1024L

    /** Display names: '_' and '.' runs collapse to single spaces ("Test_Racer.II" -> "Test Racer II"). */
    fun cleanName(raw: String): String = raw.replace(Regex("[._]+"), " ").trim().replace(Regex(" +"), " ")

    /**
     * Scans [roots] in order, walking at most two levels below each root; results are deduped
     * by canonical game path and sorted by display name. For a folder game the path is the
     * default.xex inside it (that is the game_uri Xendroid+ expects for the XEX form).
     */
    fun scan(roots: List<File>): List<GameInfo> {
        val out = ArrayList<GameInfo>()
        val seen = HashSet<String>()
        fun add(game: GameInfo) {
            val canonical = runCatching { File(game.path).canonicalPath }.getOrDefault(game.path)
            if (seen.add(canonical)) out += game
        }
        for (root in roots) {
            if (root.isDirectory) walk(root, depth = 0, ::add)
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** [dir] itself sits at [depth] below the root; its entries are one level deeper. */
    private fun walk(dir: File, depth: Int, add: (GameInfo) -> Unit) {
        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (entry in entries) {
            if (entry.name.startsWith(".")) continue // hidden files and folders are never games
            if (entry.isDirectory) {
                // A folder containing default.xex is one game; its contents are not scanned.
                val xex = entry.listFiles()?.firstOrNull {
                    it.isFile && it.name.equals("default.xex", ignoreCase = true)
                }
                if (xex != null) {
                    add(GameInfo(xex.absolutePath, cleanName(entry.name), "XEX"))
                } else if (depth + 1 < MAX_DEPTH) {
                    walk(entry, depth + 1, add)
                }
            } else if (entry.isFile) {
                addFile(entry, add)
            }
        }
    }

    private fun addFile(file: File, add: (GameInfo) -> Unit) {
        val lower = file.name.lowercase()
        when {
            lower.endsWith(".iso") || lower.endsWith(".zar") -> {
                // Tiny files with an image extension are junk (placeholders, partial downloads).
                if (file.length() >= MIN_IMAGE_BYTES) {
                    val format = if (lower.endsWith(".iso")) "ISO" else "ZAR"
                    add(GameInfo(file.absolutePath, cleanName(file.nameWithoutExtension), format))
                }
            }
            // STFS containers (Games on Demand, XBLA) carry their type in the first 4 bytes.
            // Their real title is in the header metadata; the file name is a content ID.
            file.length() >= 4 && hasStfsMagic(file) ->
                add(GameInfo(file.absolutePath, stfsTitle(file) ?: cleanName(file.nameWithoutExtension), "GOD"))
        }
    }

    /**
     * The display title from an STFS package's XContent header, or null when unreadable.
     *
     * Layout (validated against real 17559 system packages, e.g. the DashPack shows
     * "DashPack"): after the volume descriptor, the header holds nine per-language
     * metadata blocks of 0x200 bytes starting at 0x411; each block opens with the
     * display name, 0x80 bytes of UTF-16BE, NUL-terminated. Slot 0 is English; the
     * first non-blank slot wins. Any failure (short file, control bytes in the field)
     * yields null and the caller falls back to the file name, as before this parsing.
     */
    fun stfsTitle(file: File): String? = runCatching {
        val wanted = TITLE_OFFSET + LANG_STRIDE * LANG_SLOTS
        val head = ByteArray(wanted)
        val read = file.inputStream().use { input ->
            var n = 0
            while (n < wanted) {
                val r = input.read(head, n, wanted - n)
                if (r < 0) break
                n += r
            }
            n
        }
        // The caller matched the magic already; re-check so a swapped file can't slip by.
        if (read < 4) return@runCatching null
        if (STFS_MAGIC.none { m -> (0 until 4).all { head[it] == m[it].code.toByte() } }) return@runCatching null
        for (slot in 0 until LANG_SLOTS) {
            val off = TITLE_OFFSET + slot * LANG_STRIDE
            if (off + 2 > read) break
            val title = readTitleSlot(head, read, off)
            if (title != null) return@runCatching title
        }
        null
    }.getOrNull()

    /** One language slot's name field: UTF-16BE until NUL or the 0x80-byte field edge. */
    private fun readTitleSlot(head: ByteArray, read: Int, off: Int): String? {
        val sb = StringBuilder()
        var i = off
        val end = minOf(off + TITLE_FIELD_BYTES, read - 1)
        while (i < end) {
            val unit = ((head[i].toInt() and 0xFF) shl 8) or (head[i + 1].toInt() and 0xFF)
            if (unit == 0) break
            if (unit < 0x20) return null // control bytes: not a real title
            sb.append(unit.toChar())
            i += 2
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun hasStfsMagic(file: File): Boolean = runCatching {
        val head = ByteArray(4)
        file.inputStream().use { if (it.read(head) < 4) return@runCatching false }
        STFS_MAGIC.any { magic -> head.indices.all { head[it] == magic[it].code.toByte() } }
    }.getOrDefault(false)

    private const val MAX_DEPTH = 2

    // XContent header display-name layout (see stfsTitle).
    private const val TITLE_OFFSET = 0x411
    private const val TITLE_FIELD_BYTES = 0x80
    private const val LANG_SLOTS = 9
    private const val LANG_STRIDE = 0x200
}

/**
 * Android-facing store: gates the scan on all-files access and caches the last result.
 *
 * The hub rebuilds often (package changes, every settings toggle, after each launch), and
 * none of those change the game folders, so a rebuild reuses the cached list. The folders
 * are walked again only when [load] is forced (the settings tile's re-scan), when access was
 * just granted, or when the cache is older than [maxAgeMs].
 */
class Games(
    private val hasAccessFn: () -> Boolean = Environment::isExternalStorageManager,
    private val roots: List<File> = DEFAULT_ROOTS.map(::File),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    @Volatile var found: Int = 0
        private set

    /** Walks done so far; tests use it to tell a cache hit from a rescan. */
    @Volatile var scans: Int = 0
        private set

    private var cached: List<GameInfo>? = null
    private var cachedAt = 0L

    fun hasAccess(): Boolean = hasAccessFn()

    /** Loads the game list; returns empty (and never touches the filesystem) without access. */
    @Synchronized
    fun load(force: Boolean = false): List<GameInfo> {
        if (!hasAccess()) {
            found = 0
            cached = null
            return emptyList()
        }
        val now = clock()
        cached?.let { if (!force && now - cachedAt < maxAgeMs) return it }
        val games = GameScanner.scan(roots)
        scans++
        cached = games
        cachedAt = now
        found = games.size
        return games
    }

    companion object {
        /** A rebuild after this long walks the folders again, so files copied in show up. */
        const val MAX_AGE_MS = 5 * 60_000L

        val DEFAULT_ROOTS = listOf(
            "/storage/emulated/0/Xbox360",
            "/storage/emulated/0/ROMs/xbox360",
            "/storage/emulated/0/Games/Xbox360",
        )
    }
}
