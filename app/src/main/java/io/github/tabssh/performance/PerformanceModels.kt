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
    val loadAverage: LoadMetrics
)

/**
 * CPU usage metrics
 */
data class CpuMetrics(
    val userPercent: Float,      // User space CPU %
    val systemPercent: Float,     // System/kernel CPU %
    val idlePercent: Float,       // Idle CPU %
    val iowaitPercent: Float,     // I/O wait %
    val totalPercent: Float       // Total usage % (100 - idle)
) {
    companion object {
        fun empty() = CpuMetrics(0f, 0f, 100f, 0f, 0f)
    }
}

/**
 * Memory usage metrics
 */
data class MemoryMetrics(
    val totalMB: Long,            // Total RAM in MB
    val usedMB: Long,             // Used RAM in MB
    val freeMB: Long,             // Free RAM in MB
    val availableMB: Long,        // Available RAM in MB (free + buffers + cache)
    val buffersAndCacheMB: Long,  // Buffers + Cache in MB
    val usedPercent: Float        // Used percentage
) {
    companion object {
        fun empty() = MemoryMetrics(0, 0, 0, 0, 0, 0f)
    }
}

/**
 * Disk usage metrics
 */
data class DiskMetrics(
    val totalGB: Float,           // Total disk space in GB
    val usedGB: Float,            // Used disk space in GB
    val availableGB: Float,       // Available disk space in GB
    val usedPercent: Float,       // Used percentage
    val mountPoint: String        // Mount point (usually "/")
) {
    companion object {
        fun empty() = DiskMetrics(0f, 0f, 0f, 0f, "/")
    }
}

/**
 * Network statistics
 */
data class NetworkMetrics(
    val rxBytesPerSec: Long,      // Receive bytes/sec
    val txBytesPerSec: Long,      // Transmit bytes/sec
    val rxPacketsPerSec: Long,    // Receive packets/sec
    val txPacketsPerSec: Long,    // Transmit packets/sec
    val totalRxMB: Float,         // Total received MB
    val totalTxMB: Float          // Total transmitted MB
) {
    companion object {
        fun empty() = NetworkMetrics(0, 0, 0, 0, 0f, 0f)
    }
}

/**
 * System load average
 */
data class LoadMetrics(
    val load1min: Float,          // 1 minute load average
    val load5min: Float,          // 5 minute load average
    val load15min: Float,         // 15 minute load average
    val runningProcesses: Int,    // Running processes
    val totalProcesses: Int,      // Total processes
    val uptime: Long              // System uptime in seconds
) {
    companion object {
        fun empty() = LoadMetrics(0f, 0f, 0f, 0, 0, 0)
    }
}

/**
 * Historical metrics for charting
 */
data class MetricsHistory(
    val maxEntries: Int = 60,     // Keep last 60 data points (5 minutes at 5s interval)
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
