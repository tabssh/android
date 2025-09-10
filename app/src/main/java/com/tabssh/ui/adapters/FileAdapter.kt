package com.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tabssh.R
import com.tabssh.databinding.ItemFileBinding
import com.tabssh.sftp.RemoteFileInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying files in SFTP browser
 * Supports both local files and remote files
 */
class FileAdapter : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {
    
    // Local files
    private var localFiles: List<File> = emptyList()
    private var onFileClick: ((File) -> Unit)? = null
    private var onFileLongClick: ((File) -> Unit)? = null
    
    // Remote files
    private var remoteFiles: List<RemoteFileInfo> = emptyList()
    private var onRemoteFileClick: ((RemoteFileInfo) -> Unit)? = null
    private var onRemoteFileLongClick: ((RemoteFileInfo) -> Unit)? = null
    
    private var isRemote = false
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    
    // Constructor for local files
    constructor(
        files: List<File>,
        isRemote: Boolean = false,
        onFileClick: ((File) -> Unit)? = null,
        onFileLongClick: ((File) -> Unit)? = null
    ) {
        this.localFiles = files
        this.isRemote = isRemote
        this.onFileClick = onFileClick
        this.onFileLongClick = onFileLongClick
    }
    
    // Constructor for remote files
    constructor(
        remoteFiles: List<RemoteFileInfo>,
        isRemote: Boolean = true,
        onRemoteFileClick: ((RemoteFileInfo) -> Unit)? = null,
        onRemoteFileLongClick: ((RemoteFileInfo) -> Unit)? = null
    ) {
        this.remoteFiles = remoteFiles
        this.isRemote = isRemote
        this.onRemoteFileClick = onRemoteFileClick
        this.onRemoteFileLongClick = onRemoteFileLongClick
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        if (isRemote) {
            if (position < remoteFiles.size) {
                holder.bindRemoteFile(remoteFiles[position])
            }
        } else {
            if (position < localFiles.size) {
                holder.bindLocalFile(localFiles[position])
            }
        }
    }
    
    override fun getItemCount(): Int = if (isRemote) remoteFiles.size else localFiles.size
    
    inner class FileViewHolder(
        private val binding: ItemFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bindLocalFile(file: File) {
            binding.apply {
                iconFile.setImageResource(getLocalFileIcon(file))
                textFileName.text = file.name
                textFileSize.text = if (file.isDirectory) {
                    val itemCount = file.listFiles()?.size ?: 0
                    "$itemCount items"
                } else {
                    formatFileSize(file.length())
                }
                textFileDate.text = dateFormat.format(Date(file.lastModified()))
                textFilePermissions.text = getLocalFilePermissions(file)
                
                // Click listeners
                root.setOnClickListener {
                    onFileClick?.invoke(file)
                }
                
                root.setOnLongClickListener {
                    onFileLongClick?.invoke(file)
                    true
                }
                
                // Accessibility
                root.contentDescription = buildString {
                    if (file.isDirectory) append("Folder ") else append("File ")
                    append(file.name)
                    append(". Size: ${textFileSize.text}")
                    append(". Modified: ${textFileDate.text}")
                }
            }
        }
        
        fun bindRemoteFile(file: RemoteFileInfo) {
            binding.apply {
                iconFile.setImageResource(getRemoteFileIcon(file))
                textFileName.text = file.name
                textFileSize.text = if (file.isDirectory) {
                    "Directory"
                } else {
                    file.getFormattedSize()
                }
                textFileDate.text = dateFormat.format(Date(file.modifiedTime))
                textFilePermissions.text = file.permissions
                
                // Click listeners
                root.setOnClickListener {
                    onRemoteFileClick?.invoke(file)
                }
                
                root.setOnLongClickListener {
                    onRemoteFileLongClick?.invoke(file)
                    true
                }
                
                // Accessibility
                root.contentDescription = buildString {
                    append(file.getFileType()).append(" ")
                    append(file.name)
                    append(". Size: ${file.getFormattedSize()}")
                    append(". Permissions: ${file.permissions}")
                    if (file.isSymlink) append(". Symbolic link")
                }
            }
        }
        
        private fun getLocalFileIcon(file: File): Int {
            return when {
                file.isDirectory -> R.drawable.ic_folder
                file.name.endsWith(".txt") || file.name.endsWith(".log") -> R.drawable.ic_file_text
                file.name.endsWith(".jpg") || file.name.endsWith(".png") -> R.drawable.ic_file_image
                file.name.endsWith(".zip") || file.name.endsWith(".tar.gz") -> R.drawable.ic_file_archive
                file.canExecute() -> R.drawable.ic_file_executable
                else -> R.drawable.ic_file_generic
            }
        }
        
        private fun getRemoteFileIcon(file: RemoteFileInfo): Int {
            return when {
                file.isDirectory -> R.drawable.ic_folder
                file.isSymlink -> R.drawable.ic_file_link
                file.name.endsWith(".txt") || file.name.endsWith(".log") -> R.drawable.ic_file_text
                file.name.endsWith(".jpg") || file.name.endsWith(".png") -> R.drawable.ic_file_image
                file.name.endsWith(".zip") || file.name.endsWith(".tar.gz") -> R.drawable.ic_file_archive
                file.permissions.startsWith("-rwx") -> R.drawable.ic_file_executable
                else -> R.drawable.ic_file_generic
            }
        }
        
        private fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
                else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            }
        }
        
        private fun getLocalFilePermissions(file: File): String {
            return buildString {
                append(if (file.isDirectory) "d" else "-")
                append(if (file.canRead()) "r" else "-")
                append(if (file.canWrite()) "w" else "-")
                append(if (file.canExecute()) "x" else "-")
                append("------") // Other permissions not available in Java
            }
        }
    }
}