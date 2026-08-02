package io.github.tabssh.utils

/**
 * Framework-free decision logic for the file:// "Open" round trip (see
 * TODO.AI.md) — size gating, cache file naming, LRU eviction, and the
 * mtime/size change detection used to decide whether an edited file needs
 * uploading back. Kept free of Android types so it can run under plain JVM
 * unit tests; RemoteFileOpener wires this into SFTPManager, FileProvider,
 * and Intents.
 */
object FileOpenPolicy {

    /** Default cache-cap for cacheDir/file-links/ before oldest entries are evicted. */
    const val DEFAULT_CACHE_CAP_BYTES = 100L * 1024 * 1024

    /** Default size threshold (MB) above which the size-gate prompt fires. */
    const val DEFAULT_SIZE_LIMIT_MB = 20

    /** One entry in the file-links cache directory, as seen by the eviction decision. */
    data class CachedFileStat(val name: String, val lastModified: Long, val length: Long)

    /**
     * True when [sizeBytes] exceeds the [limitMb]-megabyte threshold and the
     * caller should prompt before downloading rather than downloading
     * silently.
     */
    fun exceedsSizeGate(sizeBytes: Long, limitMb: Int): Boolean =
        sizeBytes > limitMb.toLong() * 1024 * 1024

    /**
     * Builds a stable, path-traversal-safe cache file name for [remotePath].
     * Stable per remote path (not per download) so repeated opens of the
     * same remote file reuse one cache entry instead of piling up copies;
     * only the remote directory component is folded into the name — the
     * basename after the last "/" is never used verbatim, since a hostile
     * or unusual remote name could otherwise contain "/" or "..".
     */
    fun cacheFileName(remotePath: String): String {
        val baseName = remotePath.substringAfterLast('/').ifEmpty { "file" }
        val safeBaseName = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val hash = remotePath.hashCode().toUInt().toString(16)
        return "${hash}_$safeBaseName"
    }

    /**
     * Lowercased extension without the dot, or null when [fileName] has
     * none. A leading dot with nothing before it (".bashrc") is a dotfile
     * name, not an extension separator, so it also yields null.
     */
    fun extensionOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return null
        return fileName.substring(dot + 1).lowercase()
    }

    /**
     * Returns the oldest-first subset of [files] to delete so the remaining
     * total drops to at or under [capBytes]. Ties (equal lastModified) break
     * by name for deterministic output. Never evicts more than necessary.
     */
    fun filesToEvict(files: List<CachedFileStat>, capBytes: Long): List<CachedFileStat> {
        var total = files.sumOf { it.length }
        if (total <= capBytes) return emptyList()

        val sorted = files.sortedWith(compareBy({ it.lastModified }, { it.name }))
        val toEvict = mutableListOf<CachedFileStat>()
        for (file in sorted) {
            if (total <= capBytes) break
            toEvict.add(file)
            total -= file.length
        }
        return toEvict
    }

    /**
     * True when the file's mtime or size no longer matches the snapshot
     * taken right before it was handed to an external editor — the trigger
     * for the "upload back?" prompt on resume.
     */
    fun hasFileChanged(
        originalMtime: Long,
        originalSize: Long,
        currentMtime: Long,
        currentSize: Long
    ): Boolean = currentMtime != originalMtime || currentSize != originalSize
}
