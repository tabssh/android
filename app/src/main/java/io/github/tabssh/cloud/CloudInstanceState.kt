package io.github.tabssh.cloud

data class CloudInstanceState(
    val id: String,
    val name: String,
    val ip: String?,
    val privateIp: String?,
    val status: String,
    val rawStatus: String,
    val region: String?,
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        fun normalizeStatus(raw: String): String = when (raw.lowercase()) {
            "running", "active" -> "running"
            "off", "offline", "stopped", "terminated" -> "stopped"
            "booting", "starting", "pending", "new" -> "starting"
            "shutting_down", "stopping" -> "stopping"
            "rebooting", "restarting" -> "rebooting"
            else -> "unknown"
        }
    }
}
