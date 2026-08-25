package io.github.tabssh.utils

import android.content.Context
import io.github.tabssh.R
import io.github.tabssh.utils.logging.Logger
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps a caught [Throwable] to a friendly, translatable, user-facing message
 * instead of splicing raw exception text into UI strings (AI.md PART 2
 * "Error surfaces": blocking errors get a specific, actionable message,
 * never raw exception text). The raw exception class/message is always
 * logged via [Logger] and returned as [MappedError.technicalDetail] so a
 * "Copy details"/"Copy" affordance can still surface it for debugging —
 * this does not remove existing debug capability, it just keeps the raw
 * text out of the dialog/Toast body.
 */
object ThrowableMapper {

    data class MappedError(val message: String, val technicalDetail: String)

    /**
     * Classify [throwable], log it under [tag] via [Logger], and return a
     * friendly message plus the raw technical detail. Callers typically feed
     * [MappedError.message] into an existing `R.string.x_failed_fmt` template
     * in place of `e.message`, and pass [MappedError.technicalDetail] as the
     * dialog's copy-button text.
     */
    fun map(context: Context, tag: String, throwable: Throwable, logContext: String = "Operation failed"): MappedError {
        Logger.e(tag, logContext, throwable)
        val technicalDetail = "${throwable.javaClass.simpleName}: ${throwable.message}"

        // Unwrap the cause chain — a library frequently wraps the real
        // network exception inside a generic IOException or its own type.
        var cursor: Throwable? = throwable
        var depth = 0
        while (cursor != null && depth < 6) {
            classifyDirect(context, cursor)?.let { return MappedError(it, technicalDetail) }
            cursor = cursor.cause
            depth++
        }
        return MappedError(context.getString(R.string.error_mapper_generic), technicalDetail)
    }

    private fun classifyDirect(context: Context, candidate: Throwable): String? {
        val msg = candidate.message.orEmpty()
        return when {
            candidate is SocketTimeoutException -> context.getString(R.string.error_connection_timeout)
            candidate is UnknownHostException -> context.getString(R.string.error_mapper_unknown_host)
            candidate is ConnectException || msg.contains("connection refused", ignoreCase = true) ->
                context.getString(R.string.error_mapper_connection_refused)
            candidate is SSLException || msg.contains("ssl", ignoreCase = true) || msg.contains("certificate", ignoreCase = true) ->
                context.getString(R.string.error_mapper_ssl)
            candidate is java.io.FileNotFoundException -> context.getString(R.string.error_mapper_not_found)
            candidate is IOException -> context.getString(R.string.error_mapper_io)
            else -> null
        }
    }
}
