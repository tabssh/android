package io.github.tabssh.ui.dialogs

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerTransportMessages
import io.github.tabssh.ui.utils.ContainerText

/** Upper bound on the remote stderr appended to a failure dialog. */
private const val MAX_DETAIL_LENGTH = 2048

/**
 * Centralized ContainerResult-failure presentation.
 *
 * Maps the transport-layer message constants ([ContainerTransportMessages]) onto
 * their string-resource equivalents so every screen shows the same actionable
 * text — a permission failure always carries the remediation for the engine
 * that produced it — and appends the ContainerResult detail (path, stderr)
 * when present.
 *
 * The engine-dependent messages are separate constants rather than one
 * formatted string precisely so this mapping can stay an exact-identity match:
 * an interpolated engine name would never equal any resource.
 */
object ContainerErrorPresenter {

    /** Localized user-facing message for any failed [result]. */
    fun messageFor(context: Context, result: ContainerResult<*>): String {
        val (message, detail) = when (result) {
            is ContainerResult.Success -> return ""
            is ContainerResult.PermissionDenied -> result.message to result.detail
            is ContainerResult.NotFound -> result.message to result.detail
            is ContainerResult.EngineNotInstalled -> result.message to result.detail
            is ContainerResult.TransportUnavailable -> result.message to result.detail
            is ContainerResult.Error -> result.message to result.detail
        }
        val localized = when (message) {
            ContainerTransportMessages.SOCKET_PERMISSION_DOCKER ->
                context.getString(R.string.container_msg_socket_permission_docker)
            ContainerTransportMessages.SOCKET_PERMISSION_PODMAN ->
                context.getString(R.string.container_msg_socket_permission_podman)
            ContainerTransportMessages.SOCKET_PERMISSION_INCUS ->
                context.getString(R.string.container_msg_socket_permission_incus)
            ContainerTransportMessages.SOCKET_PERMISSION_LXD ->
                context.getString(R.string.container_msg_socket_permission_lxd)
            ContainerTransportMessages.STREAMLOCAL_DENIED_REMEDIATION ->
                context.getString(R.string.container_msg_streamlocal_denied)
            ContainerTransportMessages.TCP_FORWARD_DENIED_REMEDIATION ->
                context.getString(R.string.container_msg_tcp_forward_denied)
            ContainerTransportMessages.SOCKET_MISSING_DOCKER ->
                context.getString(R.string.container_msg_socket_missing_docker)
            ContainerTransportMessages.SOCKET_MISSING_PODMAN ->
                context.getString(R.string.container_msg_socket_missing_podman)
            ContainerTransportMessages.SOCKET_MISSING_INCUS ->
                context.getString(R.string.container_msg_socket_missing_incus)
            ContainerTransportMessages.SOCKET_MISSING_LXD ->
                context.getString(R.string.container_msg_socket_missing_lxd)
            ContainerTransportMessages.DIAL_STDIO_UNSUPPORTED_DOCKER ->
                context.getString(R.string.container_msg_dial_stdio_unsupported_docker)
            ContainerTransportMessages.DIAL_STDIO_UNSUPPORTED_PODMAN ->
                context.getString(R.string.container_msg_dial_stdio_unsupported_podman)
            ContainerTransportMessages.CLI_MISSING_DOCKER ->
                context.getString(R.string.container_msg_cli_missing_docker)
            ContainerTransportMessages.CLI_MISSING_PODMAN ->
                context.getString(R.string.container_msg_cli_missing_podman)
            ContainerTransportMessages.CLI_MISSING_INCUS ->
                context.getString(R.string.container_msg_cli_missing_incus)
            ContainerTransportMessages.CLI_MISSING_LXD ->
                context.getString(R.string.container_msg_cli_missing_lxd)
            ContainerTransportMessages.COMPOSE_MISSING_DOCKER ->
                context.getString(R.string.container_msg_compose_missing_docker)
            ContainerTransportMessages.COMPOSE_MISSING_PODMAN ->
                context.getString(R.string.container_msg_compose_missing_podman)
            ContainerTransportMessages.SSH_SESSION_UNAVAILABLE ->
                context.getString(R.string.container_msg_ssh_unavailable)
            ContainerTransportMessages.ALL_TIERS_FAILED_DOCKER ->
                context.getString(R.string.container_msg_all_tiers_failed_docker)
            ContainerTransportMessages.ALL_TIERS_FAILED_PODMAN ->
                context.getString(R.string.container_msg_all_tiers_failed_podman)
            ContainerTransportMessages.ALL_TIERS_FAILED_INCUS ->
                context.getString(R.string.container_msg_all_tiers_failed_incus)
            ContainerTransportMessages.ALL_TIERS_FAILED_LXD ->
                context.getString(R.string.container_msg_all_tiers_failed_lxd)
            ContainerTransportMessages.NETWORK_ENDPOINT_UNSUPPORTED_INCUS ->
                context.getString(R.string.container_msg_network_endpoint_unsupported_incus)
            ContainerTransportMessages.NETWORK_ENDPOINT_UNSUPPORTED_LXD ->
                context.getString(R.string.container_msg_network_endpoint_unsupported_lxd)
            ContainerTransportMessages.ENDPOINT_MALFORMED ->
                context.getString(R.string.container_msg_endpoint_malformed)
            ContainerTransportMessages.CAPABILITY_UNSUPPORTED ->
                context.getString(R.string.container_msg_capability_unsupported)
            else -> message
        }
        // The detail is raw remote stderr — strip control/bidi characters and
        // cap it so a hostile daemon cannot forge dialog text or hand the
        // measure pass a megabyte-long line.
        return if (detail.isNullOrBlank()) {
            localized
        } else {
            "$localized\n\n${ContainerText.block(detail, MAX_DETAIL_LENGTH)}"
        }
    }

    /** Show the failure in a modal dialog (no-op for Success). */
    fun present(context: Context, result: ContainerResult<*>) {
        if (result is ContainerResult.Success) return
        val title = if (result is ContainerResult.PermissionDenied) {
            R.string.container_permission_title
        } else {
            R.string.container_error_title
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(messageFor(context, result))
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
