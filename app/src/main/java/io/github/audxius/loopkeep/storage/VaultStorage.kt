package io.github.audxius.loopkeep.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

data class DocInfo(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
) {
    val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
}

/**
 * The user's chosen folder, accessed through the Storage Access Framework.
 *
 * Uses raw [DocumentsContract] queries: listing a folder is one query that returns name, size
 * and mtime for every child, which is far faster than per-file DocumentFile calls.
 */
class VaultStorage(private val resolver: ContentResolver, val treeUri: Uri) {

    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)
    @Volatile private var notesId: String? = null
    @Volatile private var mediaId: String? = null

    fun uriOf(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    fun listChildren(parentId: String): List<DocInfo> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<DocInfo>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += DocInfo(
                    documentId = c.getString(0),
                    name = c.getString(1) ?: continue,
                    mimeType = c.getString(2) ?: "application/octet-stream",
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        } ?: throw IOException("Cannot list folder. Was access to it revoked?")
        return out
    }

    fun info(documentId: String): DocInfo? {
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        return runCatching {
            resolver.query(uriOf(documentId), projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else DocInfo(
                    c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                    if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }.getOrNull()
    }

    /** Checks the folder is still reachable (permission not revoked, SD card present). */
    fun isAccessible(): Boolean = runCatching { listChildren(rootId); true }.getOrDefault(false)

    @Synchronized fun notesDir(): String = notesId ?: ensureDir(NOTES).also { notesId = it }
    @Synchronized fun mediaDir(): String = mediaId ?: ensureDir(MEDIA).also { mediaId = it }

    private fun ensureDir(name: String): String {
        listChildren(rootId).firstOrNull { it.isDirectory && it.name == name }?.let { return it.documentId }
        val created = DocumentsContract.createDocument(resolver, uriOf(rootId), Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Could not create folder '$name'")
        return DocumentsContract.getDocumentId(created)
    }

    fun readText(documentId: String): String =
        resolver.openInputStream(uriOf(documentId))?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw FileNotFoundException(documentId)

    fun openInput(documentId: String): InputStream =
        resolver.openInputStream(uriOf(documentId)) ?: throw FileNotFoundException(documentId)

    /** Overwrites a document, making sure it's truncated (plain "w" doesn't truncate on some providers). */
    fun writeText(documentId: String, text: String) = writeBytes(documentId, text.toByteArray(Charsets.UTF_8))

    private fun writeBytes(documentId: String, bytes: ByteArray) {
        val uri = uriOf(documentId)
        val pfd = runCatching { resolver.openFileDescriptor(uri, "rwt") }.getOrNull()
        if (pfd != null) {
            pfd.use {
                FileOutputStream(it.fileDescriptor).use { out ->
                    out.channel.truncate(0)
                    out.write(bytes)
                    out.fd.sync()
                }
            }
        } else {
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $documentId")).use { it.write(bytes) }
        }
    }

    /**
     * Creates a new text document. The provider may adjust the name on collision, so the returned
     * [DocInfo] has the name it actually got.
     */
    fun createText(parentId: String, name: String, text: String, mime: String = "text/markdown"): DocInfo {
        val uri = DocumentsContract.createDocument(resolver, uriOf(parentId), mime, name)
            ?: throw IOException("Could not create $name")
        val id = DocumentsContract.getDocumentId(uri)
        writeBytes(id, text.toByteArray(Charsets.UTF_8))
        return info(id) ?: DocInfo(id, name, mime, text.length.toLong(), System.currentTimeMillis())
    }

    /**
     * Copies [source] into [parentId] as [name]. Writes to `<name>.part` first and renames when
     * complete, so an interrupted copy never leaves a truncated file under the real name.
     */
    fun importFile(source: File, parentId: String, name: String): DocInfo {
        val partName = "$name.part"
        listChildren(parentId).filter { it.name == partName || it.name == name }.forEach { delete(it.documentId) }
        // octet-stream so the provider doesn't append its own extension.
        val partUri = DocumentsContract.createDocument(resolver, uriOf(parentId), "application/octet-stream", partName)
            ?: throw IOException("Could not create $partName")
        try {
            (resolver.openOutputStream(partUri, "w") ?: throw IOException("Cannot write $partName")).use { out ->
                source.inputStream().use { it.copyTo(out, 256 * 1024) }
            }
            val finalUri = DocumentsContract.renameDocument(resolver, partUri, name) ?: partUri
            val id = DocumentsContract.getDocumentId(finalUri)
            return info(id) ?: DocInfo(id, name, "", source.length(), System.currentTimeMillis())
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
            throw e
        }
    }

    fun delete(documentId: String): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uriOf(documentId)) }.getOrDefault(false)

    /** Deletes the children of [parentId] with these names. Returns how many were deleted. */
    fun deleteNamed(parentId: String, names: Set<String>): Int =
        if (names.isEmpty()) 0 else listChildren(parentId).filter { it.name in names }.count { delete(it.documentId) }

    /**
     * Hides or shows media/ in gallery apps with a `.nomedia` file. Obsidian ignores dot-files, so the
     * vault is unaffected. Returns the folder's file-system path (for a media rescan) when known.
     */
    fun setGalleryVisible(visible: Boolean): String? {
        val media = mediaDir()
        val marker = listChildren(media).firstOrNull { it.name == NOMEDIA }
        if (visible && marker != null) delete(marker.documentId)
        if (!visible && marker == null) {
            DocumentsContract.createDocument(resolver, uriOf(media), "application/octet-stream", NOMEDIA)
        }
        return filesystemPath(media)
    }

    /** "primary:Documents/Vault/media" → "/storage/emulated/0/Documents/Vault/media" (external storage provider only). */
    private fun filesystemPath(documentId: String): String? {
        val volume = documentId.substringBefore(':', "")
        val rel = documentId.substringAfter(':', "")
        if (treeUri.authority != "com.android.externalstorage.documents" || volume.isEmpty()) return null
        val root = if (volume == "primary") android.os.Environment.getExternalStorageDirectory().path else "/storage/$volume"
        return "$root/$rel"
    }

    /** Human-readable location, e.g. "primary:Obsidian/TikTok" → "Obsidian/TikTok". */
    fun displayPath(): String = rootId.substringAfter(':', rootId).ifEmpty { "/" }

    companion object {
        const val NOTES = "notes"
        const val MEDIA = "media"
        const val NOMEDIA = ".nomedia"
    }
}
