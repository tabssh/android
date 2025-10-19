package com.tabssh.storage.files

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * File management utilities for TabSSH
 * Handles local file operations, SSH key storage, and temporary files
 */
class FileManager(private val context: Context) {

    companion object {
        private const val SSH_KEYS_DIR = "ssh_keys"
        private const val TEMP_DIR = "temp"
        private const val DOWNLOADS_DIR = "downloads"
        private const val BACKUPS_DIR = "backups"
    }

    private val appDataDir: File = context.filesDir
    private val sshKeysDir: File = File(appDataDir, SSH_KEYS_DIR)
    private val tempDir: File = File(appDataDir, TEMP_DIR)
    private val downloadsDir: File = File(appDataDir, DOWNLOADS_DIR)
    private val backupsDir: File = File(appDataDir, BACKUPS_DIR)

    init {
        createDirectories()
    }

    private fun createDirectories() {
        listOf(sshKeysDir, tempDir, downloadsDir, backupsDir).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    /**
     * Save SSH private key to secure storage
     */
    suspend fun saveSSHKey(keyName: String, keyData: ByteArray): File = withContext(Dispatchers.IO) {
        val keyFile = File(sshKeysDir, keyName)
        FileOutputStream(keyFile).use { output ->
            output.write(keyData)
        }
        // Set restrictive permissions (owner read/write only)
        keyFile.setReadable(false, false)
        keyFile.setReadable(true, true)
        keyFile.setWritable(false, false)
        keyFile.setWritable(true, true)
        keyFile
    }

    /**
     * Load SSH private key from storage
     */
    suspend fun loadSSHKey(keyName: String): ByteArray? = withContext(Dispatchers.IO) {
        val keyFile = File(sshKeysDir, keyName)
        if (!keyFile.exists()) {
            return@withContext null
        }

        try {
            FileInputStream(keyFile).use { input ->
                input.readBytes()
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * List available SSH keys
     */
    fun listSSHKeys(): List<String> {
        return sshKeysDir.listFiles()?.map { it.name } ?: emptyList()
    }

    /**
     * Delete SSH key
     */
    suspend fun deleteSSHKey(keyName: String): Boolean = withContext(Dispatchers.IO) {
        val keyFile = File(sshKeysDir, keyName)
        keyFile.delete()
    }

    /**
     * Create temporary file
     */
    suspend fun createTempFile(prefix: String, suffix: String): File = withContext(Dispatchers.IO) {
        File.createTempFile(prefix, suffix, tempDir)
    }

    /**
     * Clean up temporary files
     */
    suspend fun cleanupTempFiles() = withContext(Dispatchers.IO) {
        tempDir.listFiles()?.forEach { file ->
            if (file.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000) {
                file.delete()
            }
        }
    }

    /**
     * Save downloaded file
     */
    suspend fun saveDownload(fileName: String, data: ByteArray): File = withContext(Dispatchers.IO) {
        val downloadFile = File(downloadsDir, fileName)
        FileOutputStream(downloadFile).use { output ->
            output.write(data)
        }
        downloadFile
    }

    /**
     * List downloaded files
     */
    fun listDownloads(): List<File> {
        return downloadsDir.listFiles()?.toList() ?: emptyList()
    }

    /**
     * Save backup file
     */
    suspend fun saveBackup(fileName: String, data: ByteArray): File = withContext(Dispatchers.IO) {
        val backupFile = File(backupsDir, fileName)
        FileOutputStream(backupFile).use { output ->
            output.write(data)
        }
        backupFile
    }

    /**
     * List backup files
     */
    fun listBackups(): List<File> {
        return backupsDir.listFiles()?.toList() ?: emptyList()
    }

    /**
     * Get available storage space
     */
    fun getAvailableSpace(): Long {
        return appDataDir.freeSpace
    }

    /**
     * Get used storage space
     */
    fun getUsedSpace(): Long {
        return calculateDirectorySize(appDataDir)
    }

    private fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        directory.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }
        return size
    }
}