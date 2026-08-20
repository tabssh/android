package io.github.tabssh.performance

/**
 * Data models for performance metrics
 */

/**
 * Complete snapshot of server performance metrics
 */
data class PerformanceMetrics(
    val timestamp: Long = System.currentTimeMillis(),
    val cpuUsage: CpuMetrics,
    val memoryUsage: MemoryMetrics,
    val diskUsage: DiskMetrics,
    val networkStats: NetworkMetrics,
    val loadAverage: LoadMetrics,
    val platformInfo: PlatformInfo = PlatformInfo.empty()
)

/**
 * CPU usage metrics
 */
data class CpuMetrics(
    // User space CPU %
    val userPercent: Float,
    // System/kernel CPU %
    val systemPercent: Float,
    // Idle CPU %
    val idlePercent: Float,
    // I/O wait %
    val iowaitPercent: Float,
    // Total usage % (100 - idle)
    val totalPercent: Float,
    // Logical CPU core count (from nproc / /proc/cpuinfo)
    val coreCount: Int = 1
) {
    companion object {
        fun empty() = CpuMetrics(0f, 0f, 100f, 0f, 0f, coreCount = 1)
    }
}

/**
 * Memory usage metrics
 */
data class MemoryMetrics(
    // Total RAM in bytes
    val totalBytes: Long,
    // Used RAM in bytes
    val usedBytes: Long,
    // Free RAM in bytes
    val freeBytes: Long,
    // Available RAM in bytes (free + buffers + cache)
    val availableBytes: Long,
    // Buffers + Cache in bytes
    val buffersAndCacheBytes: Long,
    // Used percentage
    val usedPercent: Float
) {
    companion object {
        fun empty() = MemoryMetrics(0, 0, 0, 0, 0, 0f)
    }
}

/**
 * Disk usage metrics
 */
data class DiskMetrics(
    // Total disk space in bytes
    val totalBytes: Long,
    // Used disk space in bytes
    val usedBytes: Long,
    // Available disk space in bytes
    val availableBytes: Long,
    // Used percentage
    val usedPercent: Float,
    // Mount point (usually "/")
    val mountPoint: String
) {
    companion object {
        fun empty() = DiskMetrics(0L, 0L, 0L, 0f, "/")
    }
}

/**
 * Network statistics
 */
data class NetworkMetrics(
    // Receive bytes/sec
    val rxBytesPerSec: Long,
    // Transmit bytes/sec
    val txBytesPerSec: Long,
    // Receive packets/sec
    val rxPacketsPerSec: Long,
    // Transmit packets/sec
    val txPacketsPerSec: Long,
    // Total received bytes since boot
    val totalRxBytes: Long,
    // Total transmitted bytes since boot
    val totalTxBytes: Long
) {
    companion object {
        fun empty() = NetworkMetrics(0, 0, 0, 0, 0L, 0L)
    }
}

/**
 * System load average
 */
data class LoadMetrics(
    // 1 minute load average
    val load1min: Float,
    // 5 minute load average
    val load5min: Float,
    // 15 minute load average
    val load15min: Float,
    // Running processes
    val runningProcesses: Int,
    // Total processes
    val totalProcesses: Int,
    // System uptime in seconds
    val uptime: Long
) {
    companion object {
        fun empty() = LoadMetrics(0f, 0f, 0f, 0, 0, 0)
    }
}

/**
 * Platform/OS information
 */
data class PlatformInfo(
    // e.g., "Linux", "FreeBSD"
    val osName: String,
    // Kernel version e.g., "5.15.0-91-generic"
    val osVersion: String,
    // e.g., "Ubuntu", "Debian", "CentOS"
    val distro: String,
    // e.g., "22.04", "12", "8"
    val distroVersion: String,
    // e.g., "jammy", "bookworm"
    val distroCodename: String,
    // e.g., "x86_64", "aarch64"
    val architecture: String,
    // Server hostname
    val hostname: String,
    // Full kernel string
    val kernelRelease: String
) {
    companion object {
        fun empty() = PlatformInfo("", "", "", "", "", "", "", "")
    }

    /**
     * Get a friendly display name for the OS
     */
    fun getDisplayName(): String {
        return when {
            distro.isNotBlank() && distroVersion.isNotBlank() -> "$distro $distroVersion"
            distro.isNotBlank() -> distro
            osName.isNotBlank() && osVersion.isNotBlank() -> "$osName $osVersion"
            osName.isNotBlank() -> osName
            else -> "Unknown"
        }
    }

    /**
     * Get icon emoji based on OS type
     */
    fun getOsIcon(): String {
        return when {
            distro.contains("ubuntu", ignoreCase = true) -> "🟠"
            distro.contains("debian", ignoreCase = true) -> "🔴"
            distro.contains("fedora", ignoreCase = true) -> "🔵"
            distro.contains("centos", ignoreCase = true) ||
            distro.contains("rocky", ignoreCase = true) ||
            distro.contains("alma", ignoreCase = true) -> "🟢"
            distro.contains("arch", ignoreCase = true) -> "🔷"
            distro.contains("alpine", ignoreCase = true) -> "🏔️"
            distro.contains("suse", ignoreCase = true) -> "🦎"
            osName.contains("freebsd", ignoreCase = true) -> "😈"
            osName.contains("darwin", ignoreCase = true) ||
            osName.contains("macos", ignoreCase = true) -> "🍎"
            else -> "🐧"
        }
    }
}

/**
 * Historical metrics for charting
 */
data class MetricsHistory(
    // Keep last 60 data points (5 minutes at 5s interval)
    val maxEntries: Int = 60,
    val cpuHistory: MutableList<Pair<Long, Float>> = mutableListOf(),
    val memoryHistory: MutableList<Pair<Long, Float>> = mutableListOf(),
    val networkRxHistory: MutableList<Pair<Long, Long>> = mutableListOf(),
    val networkTxHistory: MutableList<Pair<Long, Long>> = mutableListOf()
) {
    fun addCpuMetric(timestamp: Long, value: Float) {
        cpuHistory.add(timestamp to value)
        if (cpuHistory.size > maxEntries) cpuHistory.removeAt(0)
    }
    
    fun addMemoryMetric(timestamp: Long, value: Float) {
        memoryHistory.add(timestamp to value)
        if (memoryHistory.size > maxEntries) memoryHistory.removeAt(0)
    }
    
    fun addNetworkMetric(timestamp: Long, rx: Long, tx: Long) {
        networkRxHistory.add(timestamp to rx)
        networkTxHistory.add(timestamp to tx)
        if (networkRxHistory.size > maxEntries) networkRxHistory.removeAt(0)
        if (networkTxHistory.size > maxEntries) networkTxHistory.removeAt(0)
    }
    
    fun clear() {
        cpuHistory.clear()
        memoryHistory.clear()
        networkRxHistory.clear()
        networkTxHistory.clear()
    }
}
