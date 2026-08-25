package io.github.tabssh.hypervisor.console

import android.content.Context
import io.github.tabssh.R
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Structured classification of a VNC/SPICE/console connection failure —
 * the equivalent of [io.github.tabssh.ssh.connection.SSHConnectionErrorInfo]
 * for non-SSH console transports. One shared struct covers all three
 * protocols (distinguished by [protocol]) rather than near-duplicate classes,
 * per the project's reuse-before-creating convention.
 */
data class ConsoleErrorInfo(
    val protocol: String,
    val errorType: ConsoleErrorType,
    val userMessage: String,
    val technicalDetails: String,
    val possibleSolutions: List<String>
)

enum class ConsoleErrorType {
    CONNECTION_REFUSED,
    HOST_UNREACHABLE,
    TIMEOUT,
    AUTH_FAILED,
    TLS_ERROR,
    PROTOCOL_ERROR,
    GENERIC
}

/**
 * Classifies a [Throwable] raised while connecting/streaming a VNC, SPICE, or
 * hypervisor-console session into a [ConsoleErrorInfo]. Mirrors
 * `SSHConnection.buildDetailedErrorInfo()`'s cause-unwrapping approach so a
 * wrapped `SocketTimeoutException` (e.g. inside a WebSocket/RFB library
 * exception) is still recognized rather than falling into the generic bucket.
 */
object ConsoleErrorClassifier {

    fun classify(context: Context, protocol: String, throwable: Throwable): ConsoleErrorInfo {
        val technicalDetails = "${throwable.javaClass.simpleName}: ${throwable.message}"

        // Unwrap the cause chain looking for a recognizable network exception —
        // libraries (OkHttp, RFB/VNC clients, SPICE loaders) frequently wrap the
        // real cause inside a generic IOException or their own exception type.
        var cursor: Throwable? = throwable
        var depth = 0
        while (cursor != null && depth < 6) {
            val info = classifyDirect(context, protocol, cursor, throwable.message, technicalDetails)
            if (info != null) return info
            cursor = cursor.cause
            depth++
        }

        return ConsoleErrorInfo(
            protocol = protocol,
            errorType = ConsoleErrorType.GENERIC,
            userMessage = context.getString(R.string.console_error_generic_fmt, protocol),
            technicalDetails = technicalDetails,
            possibleSolutions = listOf(context.getString(R.string.console_error_solution_generic))
        )
    }

    private fun classifyDirect(
        context: Context,
        protocol: String,
        candidate: Throwable,
        topMessage: String?,
        technicalDetails: String
    ): ConsoleErrorInfo? {
        val msg = candidate.message.orEmpty()
        return when {
            candidate is SocketTimeoutException -> ConsoleErrorInfo(
                protocol, ConsoleErrorType.TIMEOUT,
                context.getString(R.string.console_error_timeout_fmt, protocol),
                technicalDetails,
                listOf(
                    context.getString(R.string.console_error_solution_check_network),
                    context.getString(R.string.console_error_solution_check_host_reachable)
                )
            )
            candidate is UnknownHostException -> ConsoleErrorInfo(
                protocol, ConsoleErrorType.HOST_UNREACHABLE,
                context.getString(R.string.console_error_unknown_host_fmt, protocol),
                technicalDetails,
                listOf(context.getString(R.string.console_error_solution_check_hostname))
            )
            candidate is ConnectException || msg.contains("connection refused", ignoreCase = true) -> ConsoleErrorInfo(
                protocol, ConsoleErrorType.CONNECTION_REFUSED,
                context.getString(R.string.console_error_connection_refused_fmt, protocol),
                technicalDetails,
                listOf(
                    context.getString(R.string.console_error_solution_check_service_running),
                    context.getString(R.string.console_error_solution_check_port)
                )
            )
            candidate is SSLException || msg.contains("ssl", ignoreCase = true) || msg.contains("certificate", ignoreCase = true) -> ConsoleErrorInfo(
                protocol, ConsoleErrorType.TLS_ERROR,
                context.getString(R.string.console_error_tls_fmt, protocol),
                technicalDetails,
                listOf(context.getString(R.string.console_error_solution_tls))
            )
            msg.contains("auth", ignoreCase = true) || msg.contains("unauthorized", ignoreCase = true) ||
                msg.contains("401", ignoreCase = false) || msg.contains("403", ignoreCase = false) -> ConsoleErrorInfo(
                protocol, ConsoleErrorType.AUTH_FAILED,
                context.getString(R.string.console_error_auth_failed_fmt, protocol),
                technicalDetails,
                listOf(context.getString(R.string.console_error_solution_check_credentials))
            )
            msg.contains("handshake", ignoreCase = true) || msg.contains("protocol", ignoreCase = true) ||
                candidate is IOException && msg.contains("unexpected", ignoreCase = true) -> ConsoleErrorInfo(
                protocol, ConsoleErrorType.PROTOCOL_ERROR,
                context.getString(R.string.console_error_protocol_fmt, protocol),
                technicalDetails,
                listOf(context.getString(R.string.console_error_solution_protocol))
            )
            else -> null
        }
    }
}
