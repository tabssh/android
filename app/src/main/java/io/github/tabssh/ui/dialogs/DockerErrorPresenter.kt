package io.github.tabssh.ui.dialogs

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.DockerTransportMessages

/**
 * Centralized DockerResult-failure presentation (PLAN.AI.md step 34).
 *
 * Maps the transport-layer message constants ([DockerTransportMessages]) onto
 * their string-resource equivalents so every screen shows the same actionable
 * text — permission-denied failures always carry the docker-group remediation
 * — and appends the DockerResult detail (path, stderr) when present.
 */
object DockerErrorPresenter {

    /** Localized user-facing message for any failed [result]. */
    fun messageFor(context: Context, result: DockerResult<*>): String {
        val (message, detail) = when (result) {
            is DockerResult.Success -> return ""
            is DockerResult.PermissionDenied -> result.message to result.detail
            is DockerResult.NotFound -> result.message to result.detail
            is DockerResult.TransportUnavailable -> result.message to result.detail
            is DockerResult.Error -> result.message to result.detail
        }
        val localized = when (message) {
            DockerTransportMessages.SOCKET_PERMISSION_REMEDIATION ->
                context.getString(R.string.docker_msg_socket_permission)
            DockerTransportMessages.STREAMLOCAL_DENIED_REMEDIATION ->
                context.getString(R.string.docker_msg_streamlocal_denied)
            DockerTransportMessages.SOCKET_MISSING ->
                context.getString(R.string.docker_msg_socket_missing)
            DockerTransportMessages.DIAL_STDIO_UNSUPPORTED ->
                context.getString(R.string.docker_msg_dial_stdio_unsupported)
            DockerTransportMessages.DOCKER_CLI_MISSING ->
                context.getString(R.string.docker_msg_cli_missing)
            DockerTransportMessages.COMPOSE_MISSING ->
                context.getString(R.string.docker_msg_compose_missing)
            DockerTransportMessages.SSH_SESSION_UNAVAILABLE ->
                context.getString(R.string.docker_msg_ssh_unavailable)
            DockerTransportMessages.ALL_TIERS_FAILED ->
                context.getString(R.string.docker_msg_all_tiers_failed)
            else -> message
        }
        return if (detail.isNullOrBlank()) localized else "$localized\n\n$detail"
    }

    /** Show the failure in a modal dialog (no-op for Success). */
    fun present(context: Context, result: DockerResult<*>) {
        if (result is DockerResult.Success) return
        val title = if (result is DockerResult.PermissionDenied) {
            R.string.docker_permission_title
        } else {
            R.string.docker_error_title
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(messageFor(context, result))
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
