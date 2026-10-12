package io.github.matiyaaa.fuse.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.matiyaaa.fuse.sync.DocumentTree
import io.github.matiyaaa.fuse.sync.TreeEntry
import java.io.InputStream
import java.io.OutputStream

/**
 * A folder the person granted through the system's folder picker, read and written through its
 * documents provider (another app's, such as Eden's `<package>.user`, or the storage's own). Fuse
 * reaches nothing the person didn't grant: [forUri] answers only for links whose access Android
 * still holds for Fuse, so a grant taken back reads as "no access", never as an empty folder.
 */
internal class AndroidDocumentTree private constructor(private val context: Context, private val tree: Uri) : DocumentTree {
    private val resolver get() = context.contentResolver

    private class Doc(val id: String, val name: String, val directory: Boolean, val size: Long, val modified: Long, val flags: Int)

    private fun children(parentId: String): List<Doc>? = try {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
            ),
            null, null, null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Doc(c.getString(0), c.getString(1) ?: continue, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4), if (c.isNull(5)) 0 else c.getInt(5)))
                }
            }
        }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The document at [path] inside the granted folder, walking it by display names. */
    private fun find(path: String): Doc? {
        var current = Doc(DocumentsContract.getTreeDocumentId(tree), "", true, 0, 0, 0)
        if (path.isEmpty()) return current
        for (part in path.split('/')) {
            if (!current.directory) return null
            current = children(current.id)?.firstOrNull { it.name == part } ?: return null
        }
        return current
    }

    private fun uriOf(doc: Doc): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc.id)

    override fun list(path: String): List<TreeEntry>? {
        val dir = find(path)?.takeIf { it.directory } ?: return null
        return children(dir.id)?.map { TreeEntry(it.name, it.directory, it.size, it.modified) }
    }

    override fun open(path: String): InputStream? {
        val doc = find(path)?.takeIf { !it.directory } ?: return null
        return try { resolver.openInputStream(uriOf(doc)) } catch (e: Exception) { null }
    }

    override fun create(path: String): OutputStream? = try {
        var parent = find("") ?: return null
        val parts = path.split('/')
        var built = ""
        for (folder in parts.dropLast(1)) {
            built = if (built.isEmpty()) folder else "$built/$folder"
            val existing = children(parent.id)?.firstOrNull { it.name == folder && it.directory }
            parent = existing ?: run {
                DocumentsContract.createDocument(resolver, uriOf(parent), DocumentsContract.Document.MIME_TYPE_DIR, folder) ?: return null
                find(built)?.takeIf { it.directory } ?: return null
            }
        }
        val name = parts.last()
        val existing = children(parent.id)?.firstOrNull { it.name == name && !it.directory }
        val target = existing?.let(::uriOf)
            ?: DocumentsContract.createDocument(resolver, uriOf(parent), "application/octet-stream", name)
            ?: return null
        // A provider may rename a new file to avoid a clash ("x (1)"): then it isn't the file asked for.
        if (existing == null && find(path) == null) {
            DocumentsContract.deleteDocument(resolver, target)
            null
        } else {
            resolver.openOutputStream(target, "wt")
        }
    } catch (e: Exception) {
        null
    }

    override fun rename(path: String, newName: String): Boolean = try {
        val doc = find(path) ?: return false
        if (doc.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME == 0) return false
        DocumentsContract.renameDocument(resolver, uriOf(doc), newName) != null &&
            find(path.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" }) != null
    } catch (e: Exception) {
        false
    }

    override fun delete(path: String): Boolean = try {
        val doc = find(path) ?: return true
        DocumentsContract.deleteDocument(resolver, uriOf(doc))
    } catch (e: Exception) {
        false
    }

    companion object {
        /** The granted folder at [uri], while Android still holds Fuse's access to it; null once it is taken back. */
        fun forUri(context: Context, uri: String): DocumentTree? {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
            val granted = context.contentResolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission }
            return if (granted) AndroidDocumentTree(context.applicationContext, parsed) else null
        }
    }
}
