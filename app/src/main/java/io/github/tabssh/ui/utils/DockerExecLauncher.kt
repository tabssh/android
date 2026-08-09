package io.github.tabssh.ui.utils

import android.content.Context
import android.content.Intent
import io.github.tabssh.docker.DockerSessionManager.DockerSession
import io.github.tabssh.docker.transport.SshExecRunner
import io.github.tabssh.ui.activities.TabTerminalActivity
import org.json.JSONObject

/**
 * Builds the terminal-tab intent for a docker exec shell into a container.
 * Probes for bash (falling back to sh), then constructs an ephemeral profile
 * that copies the linked connection's endpoint and auth; the profile is never
 * saved to the database and the exec tab opens in the terminal tab strip.
 */
object DockerExecLauncher {

    /** Probe the container's shell and return an intent for the exec terminal tab. */
    suspend fun buildExecIntent(
        context: Context,
        session: DockerSession,
        hostId: Long,
        containerId: String,
        containerName: String
    ): Intent {
        val docker = session.host.dockerCliPath ?: "docker"
        val quotedId = SshExecRunner.shQuote(containerId)
        val probe = session.runner.run(
            "$docker exec $quotedId sh -c " +
                "'command -v bash >/dev/null 2>&1 && echo bash || echo sh'"
        )
        val shell = probe.stdout.trim().lines().lastOrNull()?.trim()
            .takeIf { it == "bash" } ?: "sh"
        // docker exec -it needs a client-side PTY; the exec channel only
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
        val execProfile = session.profile.copy(
            id = "docker-exec:$hostId:$containerId",
            name = "docker: $containerName",
            remoteCommand = "$docker exec -it $quotedId $shell",
            multiplexerMode = "OFF",
            advancedSettings = adv.toString()
        )
        return TabTerminalActivity.createIntent(
            context, execProfile, autoConnect = true, forceNew = true
        )
    }
}
