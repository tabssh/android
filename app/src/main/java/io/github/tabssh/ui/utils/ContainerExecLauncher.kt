package io.github.tabssh.ui.utils

import android.content.Context
import android.content.Intent
import io.github.tabssh.containers.ContainerSessionManager.ContainerSession
import io.github.tabssh.containers.transport.SshExecRunner
import io.github.tabssh.ui.activities.TabTerminalActivity
import org.json.JSONObject

/**
 * Builds the terminal-tab intent for an exec shell into a container or
 * instance. Probes for bash (falling back to sh), then constructs an ephemeral
 * profile that copies the linked connection's endpoint and auth; the profile is
 * never saved to the database and the exec tab opens in the terminal tab strip.
 *
 * The two CLI families spell the same operation differently: Docker and Podman
 * take the command straight after the container, Incus and LXC/LXD separate it
 * with `--` and allocate the PTY with `-t` rather than `-it`.
 */
object ContainerExecLauncher {

    /** Probe the container's shell and return an intent for the exec terminal tab. */
    suspend fun buildExecIntent(
        context: Context,
        session: ContainerSession,
        hostId: Long,
        containerId: String,
        containerName: String
    ): Intent {
        val engine = session.host.engineType()
        val cli = session.host.cliBinary()
        // Instances outside the selected project are invisible to the CLI
        // unless the exec carries the same scope the rest of the app uses.
        val projectFlag = session.transport.activeProject
            ?.let { " --project ${SshExecRunner.shQuote(it)}" }
            .orEmpty()
        val dockerStyle = engine.speaksDockerApi
        val separator = if (dockerStyle) "" else " --"
        val ttyFlag = if (dockerStyle) "-it " else "-t "
        val quotedId = SshExecRunner.shQuote(containerId)
        val probe = session.runner.run(
            "$cli$projectFlag exec $quotedId$separator sh -c " +
                "'command -v bash >/dev/null 2>&1 && echo bash || echo sh'"
        )
        val shell = probe.stdout.trim().lines().lastOrNull()?.trim()
            .takeIf { it == "bash" } ?: "sh"
        // An interactive exec needs a client-side PTY; the exec channel only
        // allocates one when requestTTY is yes/force (auto = no PTY), so
        // force it on the ephemeral profile while preserving other settings
        val adv = try {
            JSONObject(
                session.profile.advancedSettings?.takeIf { it.isNotBlank() } ?: "{}"
            )
        } catch (_: Exception) {
            JSONObject()
        }
        adv.put("requestTTY", "force")
        // The container name comes from the remote daemon and becomes the tab
        // title — strip control/bidi characters so it cannot rewrite the tab
        // strip or the terminal's own title area.
        val safeName = ContainerText.display(containerName, 64)
        val execProfile = session.profile.copy(
            id = "${engine.id}-exec:$hostId:$containerId",
            name = "${engine.id}: $safeName",
            remoteCommand = "$cli$projectFlag exec $ttyFlag$quotedId$separator $shell",
            multiplexerMode = "OFF",
            advancedSettings = adv.toString()
        )
        return TabTerminalActivity.createIntent(
            context, execProfile, autoConnect = true, forceNew = true
        )
    }
}
