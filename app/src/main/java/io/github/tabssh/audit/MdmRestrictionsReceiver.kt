package io.github.tabssh.audit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger

/**
 * Receives [Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED] from the Android
 * Enterprise framework whenever an MDM administrator pushes new managed-app
 * configuration. Notifies [AuditLogManager] to reload its cached MDM bundle so
 * the next [AuditLogManager.isEnabled] call reflects the latest policy.
 *
 * Registered in AndroidManifest.xml with `exported=false` — only the system
 * (platform) is allowed to deliver this broadcast.
 */
class MdmRestrictionsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) return
        Logger.i("MdmRestrictionsReceiver", "MDM restrictions changed — refreshing audit config")
        val app = context.applicationContext as? TabSSHApplication ?: return
        app.auditLogManager.onMdmRestrictionsChanged()
    }
}
