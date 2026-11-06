package dev.x360reborn

import java.io.File
import java.io.InputStream

/**
 * A [ScanNode] over a plain directory, so the scanner's rules run on temp folders. Its uri
 * and host path are the absolute path; its key is the canonical path. [listFails] simulates
 * a revoked grant or vanished folder: listing throws, as a provider would.
 */
class FileNode(val file: File, private val listFails: Boolean = false) : ScanNode {
    override val name: String get() = file.name
    override val isDirectory: Boolean get() = file.isDirectory
    override val length: Long? get() = if (file.isFile) file.length() else null
    override val uri: String get() = file.absolutePath
    override val key: String get() = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    override val hostPath: String? get() = file.absolutePath
    override fun children(): List<ScanNode> {
        if (listFails) throw SecurityException("Permission Denial: no grant")
        return file.listFiles()?.map { FileNode(it) } ?: emptyList()
    }
    override fun open(): InputStream? = runCatching { file.inputStream() }.getOrNull()
}
