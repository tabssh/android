package io.github.tabssh.utils

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Local file browser entry — either a plain filesystem [File] (used for
 * internal storage / SD-card volume roots once the file-manager-class
 * MANAGE_EXTERNAL_STORAGE permission is granted, or below API 30 via the
 * legacy READ/WRITE_EXTERNAL_STORAGE permissions) or a Storage Access
 * Framework [DocumentFile] (used as the fallback when that permission is
 * declined, and on API 29 where no full-filesystem-access API exists).
 *
 * Wraps both behind one shape so FileAdapter and SFTPActivity's local
 * browser don't need to branch on File vs DocumentFile at every call site.
 *
 * Every property is snapshotted once, at construction time, rather than
 * re-read from the underlying [File]/[DocumentFile] on every access. For
 * [Plain] this turns repeated `stat()` syscalls per RecyclerView bind into
 * plain field reads; for [Saf] it eliminates a ContentResolver binder
 * round-trip per property per bind, which is what made the SAF-backed local
 * browser slow to scroll.
 */
sealed class LocalFileSource {
    abstract val name: String
    abstract val isDirectory: Boolean
    abstract val length: Long
    abstract val lastModified: Long
    abstract val canRead: Boolean
    abstract val canWrite: Boolean
    abstract val canExecute: Boolean

    /** Stable identity for DiffUtil and selection sets — absolute path or content URI. */
    abstract val id: String

    class Plain(val file: File) : LocalFileSource() {
        override val name: String = file.name
        override val isDirectory: Boolean = file.isDirectory
        override val length: Long = if (isDirectory) 0L else file.length()
        override val lastModified: Long = file.lastModified()
        override val canRead: Boolean = file.canRead()
        override val canWrite: Boolean = file.canWrite()
        override val canExecute: Boolean = file.canExecute()
        override val id: String = file.absolutePath
    }

    class Saf private constructor(
        val doc: DocumentFile,
        override val name: String,
        override val isDirectory: Boolean,
        override val length: Long,
        override val lastModified: Long,
        override val canRead: Boolean,
        override val canWrite: Boolean
    ) : LocalFileSource() {
        override val canExecute: Boolean = false
        override val id: String = doc.uri.toString()

        /**
         * Snapshots [doc] the slow way — one ContentResolver binder
         * round-trip per property (name, MIME type, size, last-modified,
         * read flag, write flag). Only used for the single-root case
         * ([SFTPActivity] restoring/opening a tree root); every directory
         * *listing* goes through [listChildren] instead, which is the hot
         * path browsing actually exercises.
         */
        constructor(doc: DocumentFile) : this(
            doc = doc,
            name = doc.name ?: "",
            isDirectory = doc.isDirectory,
            length = if (doc.isDirectory) 0L else doc.length(),
            lastModified = doc.lastModified(),
            canRead = doc.canRead(),
            canWrite = doc.canWrite()
        )

        companion object {
            /**
             * Lists [parent]'s children with a single batched
             * ContentResolver query instead of [DocumentFile.listFiles]
             * followed by per-child property access. The latter costs up
             * to six separate binder round-trips to the DocumentsProvider
             * per child (name, MIME type, size, last-modified, read flag,
             * write flag) — for a 132-item directory that's 700+ round
             * trips, which is what made the SAF-backed local browser slow
             * to open even off the main thread (see [SFTPActivity]'s
             * loadLocalDirectorySaf KDoc for the earlier ANR this same
             * code path used to cause). One query, selecting every needed
             * column for every child, replaces all of that with exactly
             * one round trip per directory.
             *
             * Each child's [doc] is rebuilt via [DocumentFile.fromTreeUri]
             * (not `fromSingleUri`) on a tree-form document URI, so it
             * keeps full create/list/find capability if the user
             * navigates into it — a `fromSingleUri` wrapper would silently
             * lose that and break uploads/folder creation one level down.
             */
            fun listChildren(context: Context, parent: DocumentFile): List<Saf> {
                val treeUri = parent.uri
                val parentDocumentId = DocumentsContract.getDocumentId(treeUri)
                val childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_FLAGS
                )
                val results = mutableListOf<Saf>()
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idxId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val idxName = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val idxMime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val idxSize = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    val idxModified = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val idxFlags = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(idxId) ?: continue
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                        val childDoc = DocumentFile.fromTreeUri(context, childUri) ?: continue
                        val mimeType = cursor.getString(idxMime)
                        val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
                        val flags = cursor.getInt(idxFlags)
                        val canWrite =
                            (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0 ||
                                (isDir && (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0) ||
                                (!isDir && (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0)
                        results.add(
                            Saf(
                                doc = childDoc,
                                name = cursor.getString(idxName) ?: "",
                                isDirectory = isDir,
                                length = if (isDir) 0L else cursor.getLong(idxSize),
                                lastModified = cursor.getLong(idxModified),
                                canRead = !mimeType.isNullOrEmpty(),
                                canWrite = canWrite
                            )
                        )
                    }
                }
                return results
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is LocalFileSource && other.id == id

    override fun hashCode(): Int = id.hashCode()
}
