package io.github.tabssh.background

/**
 * Pure per-host due-time gate for [ContainerUpdateCheckWorker] — separated so
 * the enable/interval decision is unit-testable on the JVM.
 */
object UpdateCheckGate {

    /**
     * True when a host's update check should run now. [overrideHours] is the
     * per-host interval override (null = [defaultHours], the global
     * twice-daily cadence); a non-positive override falls back to the
     * default. [lastCheckedAt] of 0 means never checked — always due.
     */
    fun isHostDue(
        enabled: Boolean,
        lastCheckedAt: Long,
        overrideHours: Int?,
        defaultHours: Long,
        now: Long
    ): Boolean {
        if (!enabled) return false
        val hours = overrideHours?.takeIf { it > 0 }?.toLong() ?: defaultHours
        return now - lastCheckedAt >= hours * 60L * 60L * 1000L
    }
}
