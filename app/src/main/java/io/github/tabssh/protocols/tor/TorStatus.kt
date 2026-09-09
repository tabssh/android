package io.github.tabssh.protocols.tor

/**
 * Live state of the bundled tor process, as reported by [TorManager]. Distinguishes
 * a spawned process from an actually-usable circuit — bootstrap percent comes from
 * parsing tor's own log output (see [TorNativeClient]).
 */
sealed class TorStatus {
    object Stopped : TorStatus()
    object Starting : TorStatus()
    data class Bootstrapping(val percent: Int) : TorStatus()
    object Connected : TorStatus()
    data class Failed(val reason: String) : TorStatus()
}
