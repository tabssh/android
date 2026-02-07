package io.github.tabssh.performance

import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects performance metrics from remote servers via SSH commands
 */
class MetricsCollector(private val sshConnection: SSHConnection) {

    private var previousNetworkStats: Pair<Long, Long>? = null // (rxBytes, txBytes)
    private var previousNetworkTime: Long = 0

    /**
     * Collect all performance metrics
     */
    suspend fun collectMetrics(): Result<PerformanceMetrics> = withContext(Dispatchers.IO) {
        try {
            val cpuMetrics = collectCpuMetrics()
            val memoryMetrics = collectMemoryMetrics()
            val diskMetrics = collectDiskMetrics()
            val networkMetrics = collectNetworkMetrics()
            val loadMetrics = collectLoadMetrics()

            Result.success(
                PerformanceMetrics(
                    timestamp = System.currentTimeMillis(),
                    cpuUsage = cpuMetrics,
                    memoryUsage = memoryMetrics,
                    diskUsage = diskMetrics,
                    networkStats = networkMetrics,
                    loadAverage = loadMetrics
                )
            )
        } catch (e: Exception) {
            Logger.e("MetricsCollector", "Failed to collect metrics", e)
            Result.failure(e)
        }
    }

    /**
     * Collect CPU usage from /proc/stat
     */
    private suspend fun collectCpuMetrics(): CpuMetrics {
        return try {
            val output = sshConnection.executeCommand("cat /proc/stat | head -1")
            parseCpuStats(output)
        } catch (e: Exception) {
            Logger.e("MetricsCollector", "Failed to collect CPU metrics", e)
            CpuMetrics.empty()
        }
    }

    /**
     * Parse /proc/stat output
     * Format: cpu user nice system idle iowait irq softirq steal guest guest_nice
     */
    private fun parseCpuStats(output: String): CpuMetrics {
        val parts = output.trim().split("\\s+".toRegex())
        if (parts.size < 5) return CpuMetrics.empty()

        val user = parts[1].toLongOrNull() ?: 0
        val nice = parts[2].toLongOrNull() ?: 0
        val system = parts[3].toLongOrNull() ?: 0
        val idle = parts[4].toLongOrNull() ?: 0
        val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0

        val total = user + nice + system + idle + iowait
        if (total == 0L) return CpuMetrics.empty()

        return CpuMetrics(
            userPercent = ((user + nice) * 100f / total),
            systemPercent = (system * 100f / total),
            idlePercent = (idle * 100f / total),
            iowaitPercent = (iowait * 100f / total),
            totalPercent = ((total - idle) * 100f / total)
        )
    }

    /**
     * Collect memory usage from /proc/meminfo
     */
    private suspend fun collectMemoryMetrics(): MemoryMetrics {
        return try {
            val output = sshConnection.executeCommand("cat /proc/meminfo | head -20")
            parseMemoryInfo(output)
        } catch (e: Exception) {
            Logger.e("MetricsCollector", "Failed to collect memory metrics", e)
            MemoryMetrics.empty()
        }
    }

    /**
     * Parse /proc/meminfo output
     */
    private fun parseMemoryInfo(output: String): MemoryMetrics {
        val lines = output.lines()
        val memTotal = extractValue(lines, "MemTotal")
        val memFree = extractValue(lines, "MemFree")
        val memAvailable = extractValue(lines, "MemAvailable")
        val buffers = extractValue(lines, "Buffers")
        val cached = extractValue(lines, "Cached")

        val totalMB = memTotal / 1024
        val freeMB = memFree / 1024
        val availableMB = memAvailable / 1024
        val buffersAndCacheMB = (buffers + cached) / 1024
        val usedMB = totalMB - availableMB

        return MemoryMetrics(
            totalMB = totalMB,
            usedMB = usedMB,
            freeMB = freeMB,
            availableMB = availableMB,
            buffersAndCacheMB = buffersAndCacheMB,
            usedPercent = if (totalMB > 0) (usedMB * 100f / totalMB) else 0f
        )
    }

    /**
     * Extract value from /proc/meminfo line (in KB)
     */
    private fun extractValue(lines: List<String>, key: String): Long {
        return lines.find { it.startsWith(key) }
            ?.substringAfter(":")
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.firstOrNull()
            ?.toLongOrNull() ?: 0
    }

    /**
     * Collect disk usage using df command
     */
    private suspend fun collectDiskMetrics(): DiskMetrics {
        return try {
            val output = sshConnection.executeCommand("df -BG / | tail -1")
            parseDiskUsage(output)
        } catch (e: Exception) {
            Logger.e("MetricsCollector", "Failed to collect disk metrics", e)
            DiskMetrics.empty()
        }
    }

    /**
     * Parse df output
     * Format: Filesystem 1G-blocks Used Available Use% Mounted
     */
    private fun parseDiskUsage(output: String): DiskMetrics {
        val parts = output.trim().split("\\s+".toRegex())
        if (parts.size < 6) return DiskMetrics.empty()

        val total = parts[1].removeSuffix("G").toFloatOrNull() ?: 0f
        val used = parts[2].removeSuffix("G").toFloatOrNull() ?: 0f
        val available = parts[3].removeSuffix("G").toFloatOrNull() ?: 0f
        val usedPercent = parts[4].removeSuffix("%").toFloatOrNull() ?: 0f
        val mountPoint = parts.getOrNull(5) ?: "/"

        return DiskMetrics(
            totalGB = total,
            usedGB = used,
            availableGB = available,
            usedPercent = usedPercent,
            mountPoint = mountPoint
        )
    }

    /**
     * Collect network statistics from /proc/net/dev
     */
    private suspend fun collectNetworkMetrics(): NetworkMetrics {
        return try {
            val output = sshConnection.executeCommand("cat /proc/net/dev | grep -E 'eth0|ens|enp' | head -1")
            parseNetworkStats(output)
        } catch (e: Exception) {
            Logger.e("MetricsCollector", "Failed to collect network metrics", e)
            NetworkMetrics.empty()
        }
    }

    /**
     * Parse /proc/net/dev output
     * Format: interface: rx_bytes rx_packets ... tx_bytes tx_packets ...
     */
    private fun parseNetworkStats(output: String): NetworkMetrics {
        val parts = output.trim().split("\\s+".toRegex())
        if (parts.size < 10) return NetworkMetrics.empty()

        val rxBytes = parts[1].toLongOrNull() ?: 0
        val rxPackets = parts[2].toLongOrNull() ?: 0
        val txBytes = parts[9].toLongOrNull() ?: 0
        val txPackets = parts[10].toLongOrNull() ?: 0

        val currentTime = System.currentTimeMillis()

        // Calculate bytes/sec if we have previous measurement
        val (rxBytesPerSec, txBytesPerSec, rxPacketsPerSec, txPacketsPerSec) = if (previousNetworkStats != null && previousNetworkTime > 0) {
            val timeDiff = (currentTime - previousNetworkTime) / 1000.0
            val (prevRx, prevTx) = previousNetworkStats!!
            
            if (timeDiff > 0) {
                val rxDiff = (rxBytes - prevRx).coerceAtLeast(0)
                val txDiff = (txBytes - prevTx).coerceAtLeast(0)
                
                listOf(
                    (rxDiff / timeDiff).toLong(),
                    (txDiff / timeDiff).toLong(),
                    0L, // We don't track packet rates in this simple version
                    0L
                )
            } else {
                listOf(0L, 0L, 0L, 0L)
            }
        } else {
            listOf(0L, 0L, 0L, 0L)
        }

        // Store current values for next calculation
        previousNetworkStats = rxBytes to txBytes
        previousNetworkTime = currentTime

        return NetworkMetrics(
            rxBytesPerSec = rxBytesPerSec,
            txBytesPerSec = txBytesPerSec,
            rxPacketsPerSec = rxPacketsPerSec,
            txPacketsPerSec = txPacketsPerSec,
            totalRxMB = rxBytes / 1024f / 1024f,
            totalTxMB = txBytes / 1024f / 1024f
        )
    }

    /**
     * Collect load average from /proc/loadavg and uptime
     */
    private suspend fun collectLoadMetrics(): LoadMetrics {
        return try {
            val loadOutput = sshConnection.executeCommand("cat /proc/loadavg")
            val uptimeOutput = sshConnection.executeCommand("cat /proc/uptime | cut -d' ' -f1")
            parseLoadMetrics(loadOutput, uptimeOutput)
        } catch (e: Exception) {
            Logger.e("MetricsCollector", "Failed to collect load metrics", e)
            LoadMetrics.empty()
        }
    }

    /**
     * Parse load average and uptime
     * /proc/loadavg format: 0.52 0.58 0.59 1/123 12345
     */
    private fun parseLoadMetrics(loadOutput: String, uptimeOutput: String): LoadMetrics {
        val loadParts = loadOutput.trim().split("\\s+".toRegex())
        if (loadParts.size < 4) return LoadMetrics.empty()

        val load1 = loadParts[0].toFloatOrNull() ?: 0f
        val load5 = loadParts[1].toFloatOrNull() ?: 0f
        val load15 = loadParts[2].toFloatOrNull() ?: 0f

        val processInfo = loadParts[3].split("/")
        val running = processInfo.getOrNull(0)?.toIntOrNull() ?: 0
        val total = processInfo.getOrNull(1)?.toIntOrNull() ?: 0

        val uptime = uptimeOutput.trim().toFloatOrNull()?.toLong() ?: 0

        return LoadMetrics(
            load1min = load1,
            load5min = load5,
            load15min = load15,
            runningProcesses = running,
            totalProcesses = total,
            uptime = uptime
        )
    }

    /**
     * Reset network statistics (for new connection)
     */
    fun resetNetworkStats() {
        previousNetworkStats = null
        previousNetworkTime = 0
    }
}
