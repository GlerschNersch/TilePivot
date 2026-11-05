package dev.x360reborn

import android.content.Context
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
            file.length() >= 4 && hasStfsMagic(file) ->
                add(GameInfo(file.absolutePath, cleanName(file.nameWithoutExtension), "GOD"))
        }
    }

    private fun hasStfsMagic(file: File): Boolean = runCatching {
        val head = ByteArray(4)
        file.inputStream().use { if (it.read(head) < 4) return@runCatching false }
        STFS_MAGIC.any { magic -> head.indices.all { head[it] == magic[it].code.toByte() } }
    }.getOrDefault(false)

    private const val MAX_DEPTH = 2
}

/** Android-facing store: gates the scan on all-files access and remembers the last count. */
class Games(private val context: Context) {
    @Volatile var found: Int = 0
        private set

    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    /** Loads the game list; returns empty (and never touches the filesystem) without access. */
    fun load(): List<GameInfo> {
        if (!hasAccess()) {
            found = 0
            return emptyList()
        }
        val games = GameScanner.scan(DEFAULT_ROOTS.map(::File))
        found = games.size
        return games
    }

    companion object {
        val DEFAULT_ROOTS = listOf(
            "/storage/emulated/0/Xbox360",
            "/storage/emulated/0/ROMs/xbox360",
            "/storage/emulated/0/Games/Xbox360",
        )
    }
}
