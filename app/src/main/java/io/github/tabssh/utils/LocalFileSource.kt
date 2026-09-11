package io.github.tabssh.utils

import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Local file browser entry — either a plain filesystem [File] (used for
 * internal storage / SD-card volume roots that java.io.File can still list
 * directly) or a Storage Access Framework [DocumentFile] (used once the
 * user has granted a tree via ACTION_OPEN_DOCUMENT_TREE, which is required
 * to list files under scoped storage on Android 11+/API 30+).
 *
 * Wraps both behind one shape so FileAdapter and SFTPActivity's local
 * browser don't need to branch on File vs DocumentFile at every call site.
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

    abstract fun childCount(): Int

    class Plain(val file: File) : LocalFileSource() {
        override val name: String get() = file.name
        override val isDirectory: Boolean get() = file.isDirectory
        override val length: Long get() = file.length()
        override val lastModified: Long get() = file.lastModified()
        override val canRead: Boolean get() = file.canRead()
        override val canWrite: Boolean get() = file.canWrite()
        override val canExecute: Boolean get() = file.canExecute()
        override val id: String get() = file.absolutePath
        override fun childCount(): Int = file.listFiles()?.size ?: 0
    }

    class Saf(val doc: DocumentFile) : LocalFileSource() {
        override val name: String get() = doc.name ?: ""
        override val isDirectory: Boolean get() = doc.isDirectory
        override val length: Long get() = doc.length()
        override val lastModified: Long get() = doc.lastModified()
        override val canRead: Boolean get() = doc.canRead()
        override val canWrite: Boolean get() = doc.canWrite()
        override val canExecute: Boolean get() = false
        override val id: String get() = doc.uri.toString()
        override fun childCount(): Int = doc.listFiles().size
    }

    override fun equals(other: Any?): Boolean = other is LocalFileSource && other.id == id

    override fun hashCode(): Int = id.hashCode()
}
