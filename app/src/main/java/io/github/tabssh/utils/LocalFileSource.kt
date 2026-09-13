package io.github.tabssh.utils

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

    class Saf(val doc: DocumentFile) : LocalFileSource() {
        override val name: String = doc.name ?: ""
        override val isDirectory: Boolean = doc.isDirectory
        override val length: Long = if (isDirectory) 0L else doc.length()
        override val lastModified: Long = doc.lastModified()
        override val canRead: Boolean = doc.canRead()
        override val canWrite: Boolean = doc.canWrite()
        override val canExecute: Boolean = false
        override val id: String = doc.uri.toString()
    }

    override fun equals(other: Any?): Boolean = other is LocalFileSource && other.id == id

    override fun hashCode(): Int = id.hashCode()
}
