package dev.x360reborn

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import java.io.InputStream

/**
 * The picked-folder list as stored: one tree URI per line, in pick order, no duplicates.
 * Pure so the add/remove/decode rules are unit-tested.
 */
object PickedFolders {
    fun decode(stored: String?): List<String> =
        stored.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun encode(trees: List<String>): String = trees.distinct().joinToString("\n")

    /** Adding a folder that is already picked keeps the list (and its order) unchanged. */
    fun add(trees: List<String>, tree: String): List<String> = if (tree in trees) trees else trees + tree

    fun remove(trees: List<String>, tree: String): List<String> = trees - tree
}

/**
 * A SAF document inside a picked tree. Children come from one ContentResolver query per
 * folder (id, name, MIME type, size) and are cached for the node's lifetime, which is one
 * scan. Every provider failure (revoked grant -> SecurityException, unmounted card,
 * vanished folder) degrades to "no children" / "cannot open", never a crash.
 */
class DocNode(
    private val resolver: ContentResolver,
    private val tree: Uri,
    private val documentId: String,
    override val name: String,
    override val isDirectory: Boolean,
    override val length: Long?,
    private val volumes: Map<String, String>,
) : ScanNode {
    private val docUri: Uri by lazy { DocumentsContract.buildDocumentUriUsingTree(tree, documentId) }

    override val uri: String get() = docUri.toString()
    override val key: String get() = "${tree.authority}\u0000$documentId"
    override val hostPath: String? get() = HostPaths.mapDocumentId(tree.authority.orEmpty(), documentId, volumes)

    private val kids: List<ScanNode> by lazy { if (isDirectory) query() else emptyList() }

    override fun children(): List<ScanNode> = kids

    override fun open(): InputStream? =
        if (isDirectory) null else runCatching { resolver.openInputStream(docUri) }.getOrNull()

    private fun query(): List<ScanNode> = try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        resolver.query(childrenUri, COLUMNS, null, null, null)?.use { c ->
            val out = ArrayList<ScanNode>(c.count)
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val size = if (c.isNull(3)) null else c.getLong(3)
                out += DocNode(resolver, tree, id, c.getString(1) ?: id,
                    c.getString(2) == Document.MIME_TYPE_DIR, size, volumes)
            }
            out
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w("TilePivot", "can't list $documentId: ${e.javaClass.simpleName}")
        emptyList()
    }

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
        )
    }
}

/**
 * The user's Xbox 360 game library: SAF folders picked once with ACTION_OPEN_DOCUMENT_TREE,
 * read-only, with the grant persisted. The tree URIs live in their own prefs file
 * ("game_library"), NOT in "hub", so the settings backup export never carries them: a grant
 * belongs to this install on this device and means nothing anywhere else. The same file is
 * excluded from Android's own cloud backup and device transfer (res/xml/data_extraction_rules.xml).
 */
class GameLibrary(context: Context) {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A picked folder for the settings list. [path] null means games here can't launch. */
    data class Folder(val tree: String, val label: String, val path: String?, val available: Boolean)

    fun trees(): List<String> = PickedFolders.decode(prefs.getString(TREES, null))

    /** Persists the read grant for [tree] and adds it; false if the grant can't be taken. */
    fun add(tree: Uri): Boolean {
        try {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w("TilePivot", "no persistable grant for $tree", e)
            return false
        }
        prefs.edit().putString(TREES, PickedFolders.encode(PickedFolders.add(trees(), tree.toString()))).apply()
        return true
    }

    fun remove(tree: String) {
        runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString(TREES, PickedFolders.encode(PickedFolders.remove(trees(), tree))).apply()
    }

    /** Volume id -> mount directory for every storage volume currently mounted. */
    fun volumes(): Map<String, String> = runCatching {
        val sm = app.getSystemService(StorageManager::class.java)
        buildMap {
            for (v in sm.storageVolumes) {
                val dir = v.directory?.absolutePath ?: continue
                if (v.isPrimary) put(HostPaths.PRIMARY, dir)
                v.uuid?.let { put(it, dir) }
            }
        }
    }.getOrDefault(emptyMap())

    /** Root nodes for the scanner: picked trees whose grant is still held. */
    fun roots(): List<ScanNode> {
        val held = heldGrants()
        val vols = volumes()
        return trees().filter { it in held }.mapNotNull { root(it, vols) }
    }

    /** The settings list: every picked folder, marked unavailable when it can't be read now. */
    fun folders(): List<Folder> {
        val held = heldGrants()
        val vols = volumes()
        return trees().map { t ->
            val node = if (t in held) root(t, vols) else null
            val available = node != null && runCatching { probe(Uri.parse(t)) }.getOrDefault(false)
            Folder(t, HostPaths.describe(t, vols), HostPaths.map(t, vols), available)
        }
    }

    private fun heldGrants(): Set<String> = runCatching {
        resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    }.getOrDefault(emptySet())

    private fun root(tree: String, vols: Map<String, String>): ScanNode? = runCatching {
        val uri = Uri.parse(tree)
        val id = DocumentsContract.getTreeDocumentId(uri)
        DocNode(resolver, uri, id, id.substringAfterLast('/').substringAfter(':'), true, null, vols)
    }.getOrNull()

    /** True if the tree's root document still resolves to a directory. */
    private fun probe(tree: Uri): Boolean {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(doc, arrayOf(Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            c.moveToFirst() && c.getString(0) == Document.MIME_TYPE_DIR
        } ?: false
    }

    companion object {
        const val PREFS = "game_library"
        private const val TREES = "trees"
    }
}
