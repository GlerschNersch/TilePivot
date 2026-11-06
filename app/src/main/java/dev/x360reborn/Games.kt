package dev.x360reborn

import dev.x360reborn.home.GameInfo
import java.io.InputStream

/**
 * One entry in a picked game folder, as the scanner sees it. Production nodes are SAF
 * documents ([DocNode]); unit tests walk plain directories through a java.io adapter, so
 * every scan rule below stays testable without a device.
 */
interface ScanNode {
    /** Display name, e.g. "Skate 3.iso". */
    val name: String
    val isDirectory: Boolean
    /** Size in bytes, or null when the provider does not report one. */
    val length: Long?
    /** Stable id handed around the app: the document URI (a path for test nodes). */
    val uri: String
    /** Identity for dedupe across overlapping picked folders (provider + document id). */
    val key: String
    /** Absolute filesystem path for Xendroid+, or null when this storage cannot provide one. */
    val hostPath: String?
    /** Entries inside a directory; empty for files, revoked grants and vanished folders. */
    fun children(): List<ScanNode>
    /** A stream over the file's bytes, or null when it cannot be opened. */
    fun open(): InputStream?
}

/**
 * Pure scan logic for Xbox 360 game files, over [ScanNode] trees. Recognised forms,
 * mirroring what Xendroid+ itself finds: ISO/ZAR files, folders holding a default.xex,
 * and STFS containers (GOD / XBLA) whose first four bytes are the magic "LIVE", "CON "
 * or "PIRS".
 */
object GameScanner {
    private val STFS_MAGIC = listOf("LIVE", "CON ", "PIRS")
    private const val MIN_IMAGE_BYTES = 1024L

    /** Display names: '_' and '.' runs collapse to single spaces ("Test_Racer.II" -> "Test Racer II"). */
    fun cleanName(raw: String): String = raw.replace(Regex("[._]+"), " ").trim().replace(Regex(" +"), " ")

    /** "Skate 3.iso" -> "Skate 3"; a name without an extension is returned whole. */
    fun baseName(name: String): String =
        name.lastIndexOf('.').let { if (it > 0) name.substring(0, it) else name }

    /**
     * Scans each picked root, walking at most two levels below it (files up to two levels
     * down; folders at depth two or less are checked for a default.xex child). Results are
     * deduped by [ScanNode.key] and sorted by display name. For a folder game the uri and
     * host path are the default.xex inside it (the game_uri Xendroid+ expects for the XEX
     * form). A root that cannot be listed (revoked grant, removed card) contributes nothing.
     */
    fun scan(roots: List<ScanNode>): List<GameInfo> {
        val out = ArrayList<GameInfo>()
        val seen = HashSet<String>()
        fun add(node: ScanNode, game: GameInfo) {
            if (seen.add(node.key)) out += game
        }
        for (root in roots) {
            runCatching { if (root.isDirectory) walk(root, null, depth = 0, ::add) }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * [dir] itself sits at [depth] below the root; its entries are one level deeper.
     * [parent] is [dir]'s own parent inside the picked tree (null at the root: art lookups
     * never leave the tree the user granted).
     */
    private fun walk(dir: ScanNode, parent: ScanNode?, depth: Int, add: (ScanNode, GameInfo) -> Unit) {
        for (entry in dir.children()) {
            if (entry.name.startsWith(".")) continue // hidden files and folders are never games
            if (entry.isDirectory) {
                // A folder containing default.xex is one game; its contents are not scanned.
                val xex = entry.children().firstOrNull {
                    !it.isDirectory && it.name.equals("default.xex", ignoreCase = true)
                }
                if (xex != null) {
                    add(xex, GameInfo(xex.uri, cleanName(entry.name), "XEX",
                        cover = artIn(listOfNotNull(entry, dir), entry.name), hostPath = xex.hostPath))
                } else if (depth + 1 < MAX_DEPTH) {
                    walk(entry, dir, depth + 1, add)
                }
            } else {
                addFile(entry, dir, parent, add)
            }
        }
    }

    private fun addFile(file: ScanNode, dir: ScanNode, parent: ScanNode?, add: (ScanNode, GameInfo) -> Unit) {
        val lower = file.name.lowercase()
        val base = baseName(file.name)
        val size = file.length
        when {
            lower.endsWith(".iso") || lower.endsWith(".zar") -> {
                // Tiny files with an image extension are junk (placeholders, partial downloads).
                if (size == null || size >= MIN_IMAGE_BYTES) {
                    val format = if (lower.endsWith(".iso")) "ISO" else "ZAR"
                    add(file, GameInfo(file.uri, cleanName(base), format,
                        cover = artIn(listOf(dir), base), hostPath = file.hostPath))
                }
            }
            // STFS containers (Games on Demand, XBLA) carry their type in the first 4 bytes.
            // Their real title is in the header metadata; the file name is a content ID.
            (size == null || size >= 4) && hasStfsMagic(file) -> {
                // Beside the package first, then one folder up (packages live in title-id folders).
                val cover = artIn(listOfNotNull(dir, parent), base)
                val head = readHead(file::open, HEADER_BYTES)
                add(file, GameInfo(
                    file.uri, head?.let { stfsTitle(it.first, it.second) } ?: cleanName(base), "GOD",
                    cover = cover,
                    // A user's own cover wins, so the header thumbnail only matters without one.
                    embeddedArt = cover == null && head != null && stfsThumbnail(head.first, head.second) != null,
                    hostPath = file.hostPath,
                ))
            }
        }
    }

    /** Image extensions accepted for user-supplied covers, in preference order. */
    private val COVER_EXT = listOf("jpg", "png", "jpeg", "webp")

    /** Generic cover file names, in preference order after the game's own name. */
    private val COVER_NAMES = listOf("cover", "boxart", "folder")

    /**
     * Candidate cover file names for a game whose artifact base name is [base]
     * ("Skate 3" for Skate 3.iso, or for a folder of that name), most preferred
     * first: the game's own name with each image extension, then the generic
     * names (cover, boxart, folder) the same way.
     */
    fun artCandidates(base: String): List<String> =
        (listOf(base) + COVER_NAMES).flatMap { name -> COVER_EXT.map { ext -> "$name.$ext" } }

    /**
     * The first of [artCandidates] found in any of [dirs], searched in order so a game's own
     * folder beats its parent (directory-major, then name-major within a folder); names match
     * case-insensitively against each folder's listing. Returns the folder and the entry's
     * real name. Generic over the folder type so the ordering is testable on plain lists.
     */
    fun <D> resolveArt(dirs: List<D>, base: String, list: (D) -> List<String>?): Pair<D, String>? {
        val candidates = artCandidates(base)
        for (dir in dirs) {
            val names = list(dir) ?: continue
            for (cand in candidates) {
                names.firstOrNull { it.equals(cand, ignoreCase = true) }?.let { return dir to it }
            }
        }
        return null
    }

    /** [resolveArt] over scan nodes: the uri of the winning image file, or null. */
    private fun artIn(dirs: List<ScanNode>, base: String): String? {
        val (dir, name) = resolveArt(dirs.distinctBy { it.key }, base) { d ->
            d.children().filter { !it.isDirectory }.map { it.name }
        } ?: return null
        return dir.children().firstOrNull { !it.isDirectory && it.name == name }?.uri
    }

    /** Reads up to [max] bytes from the start of a stream; null if it cannot be opened. */
    fun readHead(open: () -> InputStream?, max: Int): Pair<ByteArray, Int>? = runCatching {
        val head = ByteArray(max)
        val read = open()?.use { input ->
            var n = 0
            while (n < max) {
                val r = input.read(head, n, max - n)
                if (r < 0) break
                n += r
            }
            n
        } ?: return@runCatching null
        head to read
    }.getOrNull()

    private fun isStfs(head: ByteArray, read: Int): Boolean =
        read >= 4 && STFS_MAGIC.any { m -> (0 until 4).all { head[it] == m[it].code.toByte() } }

    private fun hasStfsMagic(file: ScanNode): Boolean =
        readHead(file::open, 4)?.let { (head, read) -> isStfs(head, read) } ?: false

    /**
     * The PNG thumbnail in an STFS package's XContent header, read through [open], or null.
     *
     * Layout (free60 XContent header): thumbnail size at 0x1712 and title-thumbnail size at
     * 0x1716 (big-endian u32), the images themselves at 0x171A and 0x571A, each in a slot of
     * at most 0x4000 bytes. The content's own thumbnail is preferred, the title's is the
     * fallback. A size outside the slot or data without the PNG signature yields null, so a
     * damaged or unusual header means "no art", never a broken image. (The 17559 system
     * packages carry no thumbnails at all; both size fields are 0 in every one of them.)
     */
    fun stfsThumbnail(open: () -> InputStream?): ByteArray? =
        readHead(open, HEADER_BYTES)?.let { (head, read) -> stfsThumbnail(head, read) }

    /** [stfsThumbnail] over an already-read header prefix of [read] bytes. */
    fun stfsThumbnail(head: ByteArray, read: Int): ByteArray? {
        if (read < THUMB_OFFSET || !isStfs(head, read)) return null
        return pngAt(head, read, THUMB_OFFSET, u32be(head, THUMB_SIZE_AT))
            ?: pngAt(head, read, TITLE_THUMB_OFFSET, u32be(head, TITLE_THUMB_SIZE_AT))
    }

    private fun u32be(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngAt(head: ByteArray, read: Int, off: Int, size: Long): ByteArray? {
        if (size < PNG_SIG.size || size > THUMB_SLOT || off + size > read) return null
        if (PNG_SIG.indices.any { head[off + it] != PNG_SIG[it] }) return null
        return head.copyOfRange(off, off + size.toInt())
    }

    /** [stfsTitle] read through [open]. */
    fun stfsTitle(open: () -> InputStream?): String? =
        readHead(open, TITLE_OFFSET + LANG_STRIDE * LANG_SLOTS)?.let { (head, read) -> stfsTitle(head, read) }

    /**
     * The display title from an STFS package's XContent header prefix ([read] valid bytes
     * of [head]), or null when unreadable.
     *
     * Layout (validated against real 17559 system packages, e.g. the DashPack shows
     * "DashPack"): after the volume descriptor, the header holds nine per-language
     * metadata blocks of 0x200 bytes starting at 0x411; each block opens with the
     * display name, 0x80 bytes of UTF-16BE, NUL-terminated. Slot 0 is English; the
     * first non-blank slot wins. Any failure (short file, control bytes in the field)
     * yields null and the caller falls back to the file name.
     */
    fun stfsTitle(head: ByteArray, read: Int): String? {
        // The caller matched the magic already; re-check so a swapped file can't slip by.
        if (!isStfs(head, read)) return null
        for (slot in 0 until LANG_SLOTS) {
            val off = TITLE_OFFSET + slot * LANG_STRIDE
            if (off + 2 > read) break
            val title = readTitleSlot(head, read, off)
            if (title != null) return title
        }
        return null
    }

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

    private const val MAX_DEPTH = 2

    // XContent header display-name layout (see stfsTitle).
    private const val TITLE_OFFSET = 0x411
    private const val TITLE_FIELD_BYTES = 0x80
    private const val LANG_SLOTS = 9
    private const val LANG_STRIDE = 0x200

    // XContent header thumbnail layout (see stfsThumbnail).
    private const val THUMB_SIZE_AT = 0x1712
    private const val TITLE_THUMB_SIZE_AT = 0x1716
    private const val THUMB_OFFSET = 0x171A
    private const val TITLE_THUMB_OFFSET = 0x571A
    private const val THUMB_SLOT = 0x4000

    /** One read covers the title slots and both thumbnail slots. */
    private const val HEADER_BYTES = TITLE_THUMB_OFFSET + THUMB_SLOT
}

/**
 * The game list over the user's picked folders, with the last result cached.
 *
 * The hub rebuilds often (package changes, every settings toggle, after each launch), and
 * none of those change the game folders, so a rebuild reuses the cached list. The folders
 * are walked again only when [load] is forced (the library's re-scan, a folder added or
 * removed), when the set of picked folders changed, or when the cache is older than [maxAgeMs].
 */
class Games(
    /** The picked folders' root nodes, in pick order; empty when none are picked. */
    private val roots: () -> List<ScanNode>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    @Volatile var found: Int = 0
        private set

    /** Walks done so far; tests use it to tell a cache hit from a rescan. */
    @Volatile var scans: Int = 0
        private set

    private var cached: List<GameInfo>? = null
    private var cachedRoots: List<String> = emptyList()
    private var cachedAt = 0L

    /** Loads the game list; with no folder picked it is empty and nothing is read. */
    @Synchronized
    fun load(force: Boolean = false): List<GameInfo> {
        val picked = runCatching(roots).getOrDefault(emptyList())
        if (picked.isEmpty()) {
            found = 0
            cached = null
            return emptyList()
        }
        val now = clock()
        val keys = picked.map { it.key }
        cached?.let { if (!force && keys == cachedRoots && now - cachedAt < maxAgeMs) return it }
        val games = GameScanner.scan(picked)
        scans++
        cached = games
        cachedRoots = keys
        cachedAt = now
        found = games.size
        return games
    }

    companion object {
        /** A rebuild after this long walks the folders again, so files copied in show up. */
        const val MAX_AGE_MS = 5 * 60_000L
    }
}
