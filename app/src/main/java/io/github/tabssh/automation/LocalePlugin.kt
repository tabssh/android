package io.github.tabssh.automation

import android.os.Bundle

/**
 * Shared constants and bundle marshalling for the com.twofortyfouram
 * Locale plugin protocol (the standard Tasker/Locale action-plugin IPC).
 * The host app (Tasker, Locale, Automate) discovers [LocaleEditActivity]
 * via ACTION_EDIT_SETTING, stores the [EXTRA_BUNDLE] it returns, and
 * later fires [LocaleFireReceiver] with that same bundle attached.
 */
object LocalePlugin {

    // Protocol actions/extras defined by the Locale Developer Platform.
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_STRING_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"

    // Plugin-private bundle keys; versioned for forward compatibility.
    const val BUNDLE_KEY_VERSION = "io.github.tabssh.locale.VERSION"
    const val BUNDLE_KEY_ACTION = "io.github.tabssh.locale.ACTION"
    const val BUNDLE_KEY_CONNECTION_ID = "io.github.tabssh.locale.CONNECTION_ID"
    const val BUNDLE_KEY_CONNECTION_NAME = "io.github.tabssh.locale.CONNECTION_NAME"
    const val BUNDLE_KEY_COMMAND = "io.github.tabssh.locale.COMMAND"
    const val BUNDLE_KEY_KEYS = "io.github.tabssh.locale.KEYS"
    const val BUNDLE_KEY_WAIT_FOR_RESULT = "io.github.tabssh.locale.WAIT_FOR_RESULT"

    const val BUNDLE_VERSION = 1

    // Same caps TaskerActionReceiver applies to raw intent extras.
    const val MAX_COMMAND_LENGTH = 8192
    const val MAX_KEYS_LENGTH = 1024
    const val MAX_NAME_LENGTH = 256

    val SUPPORTED_ACTIONS = setOf(
        TaskerWorker.ACTION_CONNECT,
        TaskerWorker.ACTION_DISCONNECT,
        TaskerWorker.ACTION_SEND_COMMAND,
        TaskerWorker.ACTION_SEND_KEYS
    )

    /**
     * Builds the plugin-private config bundle stored by the host app.
     * Only primitive types are allowed — hosts persist the bundle across
     * processes and reject Parcelable/Serializable payloads.
     */
    fun buildBundle(
        action: String,
        connectionId: String,
        connectionName: String,
        command: String?,
        keys: String?,
        waitForResult: Boolean
    ): Bundle = Bundle().apply {
        putInt(BUNDLE_KEY_VERSION, BUNDLE_VERSION)
        putString(BUNDLE_KEY_ACTION, action)
        putString(BUNDLE_KEY_CONNECTION_ID, connectionId)
        putString(BUNDLE_KEY_CONNECTION_NAME, connectionName)
        if (!command.isNullOrEmpty()) putString(BUNDLE_KEY_COMMAND, command)
        if (!keys.isNullOrEmpty()) putString(BUNDLE_KEY_KEYS, keys)
        putBoolean(BUNDLE_KEY_WAIT_FOR_RESULT, waitForResult)
    }

    /**
     * Validates a bundle delivered by a host app. Hosts relay whatever
     * was stored, but a malicious caller can fire the receiver directly
     * with arbitrary contents — treat everything as untrusted input.
     */
    fun isBundleValid(bundle: Bundle?): Boolean {
        if (bundle == null) return false
        if (bundle.getInt(BUNDLE_KEY_VERSION, -1) != BUNDLE_VERSION) return false
        val action = bundle.getString(BUNDLE_KEY_ACTION) ?: return false
        if (action !in SUPPORTED_ACTIONS) return false
        val id = bundle.getString(BUNDLE_KEY_CONNECTION_ID)
        val name = bundle.getString(BUNDLE_KEY_CONNECTION_NAME)
        if (id.isNullOrEmpty() && name.isNullOrEmpty()) return false
        if ((id?.length ?: 0) > MAX_NAME_LENGTH) return false
        if ((name?.length ?: 0) > MAX_NAME_LENGTH) return false
        if ((bundle.getString(BUNDLE_KEY_COMMAND)?.length ?: 0) > MAX_COMMAND_LENGTH) return false
        if ((bundle.getString(BUNDLE_KEY_KEYS)?.length ?: 0) > MAX_KEYS_LENGTH) return false
        if (action == TaskerWorker.ACTION_SEND_COMMAND &&
            bundle.getString(BUNDLE_KEY_COMMAND).isNullOrEmpty()
        ) return false
        if (action == TaskerWorker.ACTION_SEND_KEYS &&
            bundle.getString(BUNDLE_KEY_KEYS).isNullOrEmpty()
        ) return false
        return true
    }

    /** Short human-readable summary shown in the host app's task editor. */
    fun buildBlurb(action: String, connectionName: String, command: String?, keys: String?): String {
        val verb = when (action) {
            TaskerWorker.ACTION_CONNECT -> "Connect"
            TaskerWorker.ACTION_DISCONNECT -> "Disconnect"
            TaskerWorker.ACTION_SEND_COMMAND -> "Run: ${command.orEmpty()}"
            TaskerWorker.ACTION_SEND_KEYS -> "Keys: ${keys.orEmpty()}"
            else -> action
        }
        return "$verb → $connectionName".take(60)
    }
}
