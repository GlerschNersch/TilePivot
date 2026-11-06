package dev.x360reborn

/**
 * Maps a Storage Access Framework document or tree URI to an absolute filesystem path, for
 * the one consumer that needs one: Xendroid+'s launch contract takes `game_uri` as a host
 * path, not a content URI. Pure string logic (no android.net.Uri), so it is unit-tested on
 * the JVM; the caller supplies the storage volumes it knows about.
 *
 * Mappable:
 * - The external-storage provider (`com.android.externalstorage.documents`), whose document
 *   ids are `<volume>:<relative path>`. `primary` is the primary shared storage; any other
 *   volume id (an SD card's or USB drive's FAT uuid, e.g. `1A2B-3C4D`) maps only when
 *   [volumes] knows it (the caller fills it from StorageManager.getStorageVolumes()).
 * - `raw:` document ids, which some providers (notably the Downloads provider for files it
 *   tracks by path) use to carry an absolute path verbatim.
 *
 * Everything else returns null: Downloads-provider `msf:` / numeric ids, media-store ids,
 * cloud providers (Drive, OneDrive, ...), unknown volumes, malformed URIs, and any path
 * with a `..` segment. Null means "this game has no direct file path"; the launcher then
 * explains that instead of firing an intent Xendroid+ cannot open.
 */
object HostPaths {
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    const val PRIMARY = "primary"

    /** The pieces of a document/tree URI the mapping needs. */
    data class DocRef(val authority: String, val treeId: String?, val documentId: String)

    /**
     * Splits `content://<authority>/tree/<treeId>[/document/<docId>]` or
     * `content://<authority>/document/<docId>`; ids are percent-decoded. Null if malformed.
     */
    fun parse(uri: String): DocRef? {
        val prefix = "content://"
        if (!uri.startsWith(prefix)) return null
        val rest = uri.substring(prefix.length).substringBefore('?').substringBefore('#')
        val parts = rest.split('/')
        if (parts.size < 3 || parts[0].isEmpty()) return null
        val authority = parts[0]
        return when {
            parts[1] == "tree" && parts.size == 3 ->
                decode(parts[2])?.let { DocRef(authority, it, it) }
            parts[1] == "tree" && parts.size == 5 && parts[3] == "document" -> {
                val tree = decode(parts[2]) ?: return null
                val doc = decode(parts[4]) ?: return null
                DocRef(authority, tree, doc)
            }
            parts[1] == "document" && parts.size == 3 ->
                decode(parts[2])?.let { DocRef(authority, null, it) }
            else -> null
        }?.takeIf { it.documentId.isNotEmpty() }
    }

    /**
     * The absolute path for [uri], or null when it cannot be mapped (see the class doc).
     * [volumes] maps a volume id (`primary`, or a uuid, matched case-insensitively) to that
     * volume's mount directory, e.g. "primary" -> "/storage/emulated/0".
     */
    fun map(uri: String, volumes: Map<String, String>): String? {
        val ref = parse(uri) ?: return null
        return mapDocumentId(ref.authority, ref.documentId, volumes)
    }

    fun mapDocumentId(authority: String, documentId: String, volumes: Map<String, String>): String? {
        if (documentId.startsWith("raw:")) {
            val path = documentId.removePrefix("raw:")
            return path.takeIf { it.startsWith("/") && safe(it) }?.trimEnd('/')?.ifEmpty { "/" }
        }
        if (authority != EXTERNAL_STORAGE) return null
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1).trim('/')
        val base = volumes.entries.firstOrNull { it.key.equals(volume, ignoreCase = true) }?.value
            ?: return null
        if (!safe(relative)) return null
        val root = base.trimEnd('/')
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    /** A short human label for a picked tree: its host path when mappable, else its last segment. */
    fun describe(uri: String, volumes: Map<String, String>): String {
        map(uri, volumes)?.let { return it }
        val ref = parse(uri) ?: return uri
        val id = ref.treeId ?: ref.documentId
        return id.substringAfterLast('/').substringAfter(':').ifEmpty { id }
    }

    private fun safe(path: String) = path.split('/').none { it == ".." } && '\u0000' !in path

    /** Strict UTF-8 percent-decoding ('+' stays a plus, as android.net.Uri encodes it). */
    private fun decode(s: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(v)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
