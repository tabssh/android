package com.tabssh.utils.performance

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import com.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive performance and battery optimization manager
 * Monitors and optimizes CPU, memory, network, and battery usage
 */
class PerformanceManager(private val context: Context) {
    
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    
    private val performanceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Performance monitoring state
    private val _performanceMetrics = MutableStateFlow(PerformanceMetrics())
    val performanceMetrics: StateFlow<PerformanceMetrics> = _performanceMetrics.asStateFlow()
    
    private val _batteryOptimizationLevel = MutableStateFlow(BatteryOptimizationLevel.BALANCED)
    val batteryOptimizationLevel: StateFlow<BatteryOptimizationLevel> = _batteryOptimizationLevel.asStateFlow()
    
    // Memory tracking
    private val memoryTracker = MemoryTracker()
    
    // Network efficiency
    private val networkOptimizer = NetworkOptimizer()
    
    // Rendering optimization
    private var renderingOptimizer: RenderingOptimizer? = null
    
    // Performance monitoring job
    private var monitoringJob: Job? = null
    
    init {
        Logger.d("PerformanceManager", "Performance manager initialized")
        startPerformanceMonitoring()
    }
    
    /**
     * Initialize performance optimizations
     */
    fun initialize() {
        detectBatteryState()
        applyInitialOptimizations()
        
        Logger.i("PerformanceManager", "Performance optimizations applied")
    }
    
    private fun detectBatteryState() {
        val batteryLevel = getBatteryLevel()
        val isPowerSaveMode = powerManager.isPowerSaveMode
        val isBatteryLow = batteryLevel < 20
        
        val optimizationLevel = when {
            isPowerSaveMode || isBatteryLow -> BatteryOptimizationLevel.AGGRESSIVE
            batteryLevel < 50 -> BatteryOptimizationLevel.CONSERVATIVE  
            else -> BatteryOptimizationLevel.BALANCED
        }
        
        _batteryOptimizationLevel.value = optimizationLevel
        
        Logger.d("PerformanceManager", "Battery state: level=$batteryLevel%, powerSave=$isPowerSaveMode, optimization=$optimizationLevel")
    }
    
    private fun applyInitialOptimizations() {
        val level = _batteryOptimizationLevel.value
        
        // Apply memory optimizations
        memoryTracker.setOptimizationLevel(level)
        
        // Apply network optimizations
        networkOptimizer.setOptimizationLevel(level)
        
        // Apply rendering optimizations
        renderingOptimizer?.setOptimizationLevel(level)
    }
    
    private fun startPerformanceMonitoring() {
        monitoringJob = performanceScope.launch {
            while (isActive) {
                try {
                    // Update performance metrics
                    updatePerformanceMetrics()
                    
                    // Check battery state changes
                    detectBatteryState()
                    
                    // Apply dynamic optimizations
                    applyDynamicOptimizations()
                    
                    // Monitoring frequency based on optimization level
                    val monitoringInterval = when (_batteryOptimizationLevel.value) {
                        BatteryOptimizationLevel.PERFORMANCE -> 5000L // 5 seconds
                        BatteryOptimizationLevel.BALANCED -> 15000L // 15 seconds
                        BatteryOptimizationLevel.CONSERVATIVE -> 30000L // 30 seconds
                        BatteryOptimizationLevel.AGGRESSIVE -> 60000L // 60 seconds
                    }
                    
                    delay(monitoringInterval)
                    
                } catch (e: Exception) {
                    Logger.e("PerformanceManager", "Error in performance monitoring", e)
                    delay(30000) // Wait longer on error
                }
            }
        }
    }
    
    private suspend fun updatePerformanceMetrics() {
        val currentMetrics = _performanceMetrics.value
        
        // Update memory metrics
        val memoryInfo = memoryTracker.getCurrentMemoryInfo()
        
        // Update CPU usage (simplified)
        val cpuUsage = getCPUUsage()
        
        // Update battery info
        val batteryLevel = getBatteryLevel()
        val isCharging = isDeviceCharging()
        
        // Update network stats
        val networkStats = networkOptimizer.getNetworkStats()
        
        val updatedMetrics = currentMetrics.copy(
            memoryUsedMB = memoryInfo.usedMemoryMB,
            memoryAvailableMB = memoryInfo.availableMemoryMB,
            memoryPressureLevel = memoryInfo.pressureLevel,
            cpuUsagePercent = cpuUsage,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            networkBytesReceived = networkStats.bytesReceived,
            networkBytesSent = networkStats.bytesSent,
            lastUpdated = System.currentTimeMillis()
        )
        
        _performanceMetrics.value = updatedMetrics
    }
    
    private fun applyDynamicOptimizations() {
        val metrics = _performanceMetrics.value
        val level = _batteryOptimizationLevel.value
        
        // Apply memory pressure optimizations
        when (metrics.memoryPressureLevel) {
            MemoryPressureLevel.HIGH, MemoryPressureLevel.CRITICAL -> {
                Logger.w("PerformanceManager", "High memory pressure, applying aggressive cleanup")
                performAggressiveMemoryCleanup()
            }
            MemoryPressureLevel.MODERATE -> {
                performModerateMemoryCleanup()
            }
            else -> { /* No action needed */ }
        }
        
        // Apply CPU optimizations
        if (metrics.cpuUsagePercent > 80) {
            Logger.w("PerformanceManager", "High CPU usage (${metrics.cpuUsagePercent}%), reducing rendering frequency")
            renderingOptimizer?.reduceRenderingFrequency()
        }
        
        // Apply battery optimizations
        if (metrics.batteryLevel < 15 && !metrics.isCharging) {
            Logger.w("PerformanceManager", "Critical battery level, applying emergency optimizations")
            applyEmergencyBatteryOptimizations()
        }
    }
    
    private fun performAggressiveMemoryCleanup() {
        // Trigger garbage collection
        System.gc()
        
        // Clear caches
        // This would integrate with other managers to clear their caches
        
        // Compress inactive terminal buffers
        // This would integrate with TerminalManager
        
        Logger.d("PerformanceManager", "Performed aggressive memory cleanup")
    }
    
    private fun performModerateMemoryCleanup() {
        // Less aggressive cleanup
        System.gc()
        
        Logger.d("PerformanceManager", "Performed moderate memory cleanup")
    }
    
    private fun applyEmergencyBatteryOptimizations() {
        // Reduce terminal refresh rate
        renderingOptimizer?.setEmergencyMode(true)
        
        // Reduce connection keep-alive frequency
        networkOptimizer.setEmergencyMode(true)
        
        // Disable animations
        setAnimationsEnabled(false)
        
        Logger.w("PerformanceManager", "Applied emergency battery optimizations")
    }
    
    private fun getCPUUsage(): Float {
        // Simplified CPU usage calculation
        // Real implementation would track process CPU time
        return try {
            val statFile = "/proc/${Process.myPid()}/stat"
            // This is a placeholder - real CPU monitoring is more complex
            0.0f
        } catch (e: Exception) {
            0.0f
        }
    }
    
    private fun getBatteryLevel(): Int {
        return try {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            100 // Default to full if can't read
        }
    }
    
    private fun isDeviceCharging(): Boolean {
        return try {
            val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            status == BatteryManager.BATTERY_STATUS_CHARGING || 
            status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Set rendering optimizer (called by UI components)
     */
    fun setRenderingOptimizer(optimizer: RenderingOptimizer) {
        renderingOptimizer = optimizer
        optimizer.setOptimizationLevel(_batteryOptimizationLevel.value)
    }
    
    /**
     * Enable/disable animations for battery saving
     */
    fun setAnimationsEnabled(enabled: Boolean) {
        // This would control animation enablement throughout the app
        Logger.d("PerformanceManager", "Animations enabled: $enabled")
    }
    
    /**
     * Get performance recommendations
     */
    fun getPerformanceRecommendations(): List<PerformanceRecommendation> {
        val metrics = _performanceMetrics.value
        val recommendations = mutableListOf<PerformanceRecommendation>()
        
        // Memory recommendations
        if (metrics.memoryPressureLevel == MemoryPressureLevel.HIGH) {
            recommendations.add(
                PerformanceRecommendation(
                    type = RecommendationType.MEMORY,
                    title = "High Memory Usage",
                    description = "Consider closing unused tabs or reducing scrollback buffer size",
                    priority = RecommendationPriority.HIGH
                )
            )
        }
        
        // Battery recommendations
        if (metrics.batteryLevel < 20 && !metrics.isCharging) {
            recommendations.add(
                PerformanceRecommendation(
                    type = RecommendationType.BATTERY,
                    title = "Low Battery",
                    description = "Enable battery saver mode to extend usage time",
                    priority = RecommendationPriority.HIGH
                )
            )
        }
        
        // CPU recommendations
        if (metrics.cpuUsagePercent > 70) {
            recommendations.add(
                PerformanceRecommendation(
                    type = RecommendationType.CPU,
                    title = "High CPU Usage",
                    description = "Reduce terminal animation frequency or close resource-intensive connections",
                    priority = RecommendationPriority.MEDIUM
                )
            )
        }
        
        return recommendations
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Logger.d("PerformanceManager", "Cleaning up performance manager")
        
        monitoringJob?.cancel()
        performanceScope.cancel()
        
        renderingOptimizer?.cleanup()
        networkOptimizer.cleanup()
        memoryTracker.cleanup()
    }
}

/**
 * Battery optimization levels
 */
enum class BatteryOptimizationLevel {
    PERFORMANCE,    // Maximum performance, minimal battery optimization
    BALANCED,       // Balanced performance and battery life
    CONSERVATIVE,   // Favor battery life over performance
    AGGRESSIVE      // Maximum battery conservation
}

/**
 * Performance metrics data class
 */
data class PerformanceMetrics(
    val memoryUsedMB: Long = 0,
    val memoryAvailableMB: Long = 0,
    val memoryPressureLevel: MemoryPressureLevel = MemoryPressureLevel.NORMAL,
    val cpuUsagePercent: Float = 0f,
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val networkBytesReceived: Long = 0,
    val networkBytesSent: Long = 0,
    val renderingFPS: Float = 60f,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Memory pressure levels
 */
enum class MemoryPressureLevel {
    NORMAL, MODERATE, HIGH, CRITICAL
}

/**
 * Performance recommendations
 */
data class PerformanceRecommendation(
    val type: RecommendationType,
    val title: String,
    val description: String,
    val priority: RecommendationPriority
)

enum class RecommendationType {
    MEMORY, CPU, BATTERY, NETWORK, RENDERING
}

enum class RecommendationPriority {
    LOW, MEDIUM, HIGH, CRITICAL
}

/**
 * Memory tracker for monitoring app memory usage
 */
class MemoryTracker {
    private var optimizationLevel = BatteryOptimizationLevel.BALANCED
    
    fun getCurrentMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val availableMemory = maxMemory - usedMemory
        
        val pressureLevel = when {
            usedMemory > maxMemory * 0.9 -> MemoryPressureLevel.CRITICAL
            usedMemory > maxMemory * 0.8 -> MemoryPressureLevel.HIGH
            usedMemory > maxMemory * 0.6 -> MemoryPressureLevel.MODERATE
            else -> MemoryPressureLevel.NORMAL
        }
        
        return MemoryInfo(usedMemory, availableMemory, pressureLevel)
    }
    
    fun setOptimizationLevel(level: BatteryOptimizationLevel) {
        optimizationLevel = level
    }
    
    fun cleanup() {}
    
    data class MemoryInfo(
        val usedMemoryMB: Long,
        val availableMemoryMB: Long,
        val pressureLevel: MemoryPressureLevel
    )
}

/**
 * Network optimizer for efficient data usage
 */
class NetworkOptimizer {
    private var optimizationLevel = BatteryOptimizationLevel.BALANCED
    private var emergencyMode = false
    private val bytesReceived = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    
    fun setOptimizationLevel(level: BatteryOptimizationLevel) {
        optimizationLevel = level
        
        // Adjust keep-alive intervals based on optimization level
        val keepAliveInterval = when (level) {
            BatteryOptimizationLevel.PERFORMANCE -> 30 // 30 seconds
            BatteryOptimizationLevel.BALANCED -> 60 // 60 seconds
            BatteryOptimizationLevel.CONSERVATIVE -> 120 // 2 minutes
            BatteryOptimizationLevel.AGGRESSIVE -> 300 // 5 minutes
        }
        
        Logger.d("NetworkOptimizer", "Set keep-alive interval to ${keepAliveInterval}s for optimization level $level")
    }
    
    fun setEmergencyMode(emergency: Boolean) {
        emergencyMode = emergency
        
        if (emergency) {
            // Reduce network activity to minimum
            Logger.w("NetworkOptimizer", "Emergency mode enabled - reducing network activity")
        }
    }
    
    fun getNetworkStats(): NetworkStats {
        return NetworkStats(
            bytesReceived = bytesReceived.get(),
            bytesSent = bytesSent.get()
        )
    }
    
    fun addBytesReceived(bytes: Long) = bytesReceived.addAndGet(bytes)
    fun addBytesSent(bytes: Long) = bytesSent.addAndGet(bytes)
    
    fun cleanup() {}
    
    data class NetworkStats(
        val bytesReceived: Long,
        val bytesSent: Long
    )
}

/**
 * Rendering optimizer for smooth 60fps performance
 */
class RenderingOptimizer {
    private var optimizationLevel = BatteryOptimizationLevel.BALANCED
    private var emergencyMode = false
    private var targetFPS = 60f
    private var actualFPS = 60f
    
    fun setOptimizationLevel(level: BatteryOptimizationLevel) {
        optimizationLevel = level
        
        // Adjust target FPS based on optimization level
        targetFPS = when (level) {
            BatteryOptimizationLevel.PERFORMANCE -> 60f
            BatteryOptimizationLevel.BALANCED -> 60f
            BatteryOptimizationLevel.CONSERVATIVE -> 45f
            BatteryOptimizationLevel.AGGRESSIVE -> 30f
        }
        
        Logger.d("RenderingOptimizer", "Set target FPS to $targetFPS for optimization level $level")
    }
    
    fun setEmergencyMode(emergency: Boolean) {
        emergencyMode = emergency
        
        if (emergency) {
            targetFPS = 15f // Very low FPS for emergency battery saving
            Logger.w("RenderingOptimizer", "Emergency mode enabled - reducing FPS to $targetFPS")
        }
    }
    
    fun reduceRenderingFrequency() {
        targetFPS = (targetFPS * 0.8f).coerceAtLeast(15f)
        Logger.d("RenderingOptimizer", "Reduced rendering frequency to $targetFPS FPS")
    }
    
    fun updateActualFPS(fps: Float) {
        actualFPS = fps
    }
    
    fun getTargetFPS(): Float = if (emergencyMode) minOf(targetFPS, 15f) else targetFPS
    fun getActualFPS(): Float = actualFPS
    
    fun cleanup() {}
}