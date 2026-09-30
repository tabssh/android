package io.github.tabssh.automation

import androidx.work.Data

/** Validates the complete encoded request without truncating executable text. */
internal object TaskerInputData {
    fun create(
        action: String,
        connectionId: String?,
        connectionName: String?,
        command: String?,
        keys: String?,
        waitForResult: Boolean,
        timeoutMs: Long? = null
    ): Data? {
        if (action !in LocalePlugin.SUPPORTED_ACTIONS) return null
        if (connectionId.isNullOrEmpty() && connectionName.isNullOrEmpty()) return null
        if ((connectionId?.length ?: 0) > LocalePlugin.MAX_NAME_LENGTH ||
            (connectionName?.length ?: 0) > LocalePlugin.MAX_NAME_LENGTH ||
            (command?.length ?: 0) > LocalePlugin.MAX_COMMAND_LENGTH ||
            (keys?.length ?: 0) > LocalePlugin.MAX_KEYS_LENGTH
        ) return null
        if (action == TaskerWorker.ACTION_SEND_COMMAND && command.isNullOrEmpty()) return null
        if (action == TaskerWorker.ACTION_SEND_KEYS && keys.isNullOrEmpty()) return null

        return try {
            val builder = Data.Builder()
                .putString(TaskerWorker.KEY_ACTION, action)
                .putString(TaskerWorker.KEY_CONNECTION_ID, connectionId)
                .putString(TaskerWorker.KEY_CONNECTION_NAME, connectionName)
                .putString(TaskerWorker.KEY_COMMAND, command)
                .putString(TaskerWorker.KEY_KEYS, keys)
                .putBoolean(TaskerWorker.KEY_WAIT_FOR_RESULT, waitForResult)
            timeoutMs?.let { builder.putLong(TaskerWorker.KEY_TIMEOUT_MS, it) }
            // Data enforces the 10 KB serialized limit, including UTF-8 and key overhead.
            builder.build()
        } catch (_: IllegalStateException) {
            null
        }
    }
}
