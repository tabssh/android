package io.github.tabssh.ui.utils

import androidx.annotation.StringRes
import io.github.tabssh.R
import io.github.tabssh.containers.ContainerEngine

/**
 * Display names for engines and transport tiers.
 *
 * The stored values (`docker`, `cli_exec`, …) are machine identifiers; every
 * user-facing surface goes through here so no raw id reaches a label
 * (AI.md PART 7 § Human-Readable Values).
 */
object ContainerEngineLabels {

    /** Product name of [engine], e.g. "Docker". */
    @StringRes
    fun engineName(engine: ContainerEngine): Int = when (engine) {
        ContainerEngine.DOCKER -> R.string.container_engine_docker
        ContainerEngine.INCUS -> R.string.container_engine_incus
        ContainerEngine.PODMAN -> R.string.container_engine_podman
        ContainerEngine.LXD -> R.string.container_engine_lxd
    }

    /**
     * Decorative glyph for [engine], used to make a mixed-engine list scannable.
     * It is never the only differentiator — every surface that shows it also
     * shows [engineName] — so it stays marked as decorative for TalkBack.
     */
    @StringRes
    fun engineIcon(engine: ContainerEngine): Int = when (engine) {
        ContainerEngine.DOCKER -> R.string.container_engine_icon_docker
        ContainerEngine.INCUS -> R.string.container_engine_icon_incus
        ContainerEngine.PODMAN -> R.string.container_engine_icon_podman
        ContainerEngine.LXD -> R.string.container_engine_icon_lxd
    }

    /**
     * Plain-language name of a persisted `ContainerHost.transportMode` tier.
     * This is the configured/detected transport tier, never a live connection
     * status — it must not be confused with `lastConnected` in the row below.
     * The default tier ("auto") and any unrecognised value — a legacy tier, a
     * corrupt row — both read as "Auto-detect" rather than leaking the stored
     * identifier or implying a connection that hasn't happened yet.
     */
    @StringRes
    fun transportMode(mode: String): Int = when (mode) {
        "api_streamlocal" -> R.string.container_transport_mode_socket
        "api_stdio" -> R.string.container_transport_mode_stdio
        "cli_exec" -> R.string.container_transport_mode_cli
        else -> R.string.container_transport_mode_unknown
    }
}
