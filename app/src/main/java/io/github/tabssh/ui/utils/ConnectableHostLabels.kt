package io.github.tabssh.ui.utils

import android.content.Context
import androidx.annotation.StringRes
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.ConnectableHost

/**
 * Display labels for [ConnectableHost] registry rows.
 *
 * Every picker that lists registry-backed hosts (pane-group member spinner,
 * multi-host monitoring picker, port-forward connection spinner) goes through
 * here so mixed-source lists stay scannable and consistent: the label always
 * carries the host name, its cached endpoint preview, and — for non-Hosts-tab
 * sources — a localized source badge, so a cloud instance, a container host,
 * and a saved connection sharing a name never look identical.
 */
object ConnectableHostLabels {

    /**
     * Localized source badge for [sourceType], or null for plain Hosts-tab
     * connection profiles (the unmarked default — badging every row would
     * turn the badge into noise).
     */
    @StringRes
    fun sourceBadge(sourceType: String): Int? = when (sourceType) {
        ConnectableHost.SOURCE_CLOUD_INSTANCE -> R.string.connectable_host_badge_cloud
        ConnectableHost.SOURCE_TELNET_HOST -> R.string.connectable_host_badge_telnet
        ConnectableHost.SOURCE_CONTAINER_HOST -> R.string.connectable_host_badge_container
        else -> null
    }

    /**
     * One-line picker label: `Name — preview` for connection profiles,
     * `Name — preview · Badge` for cloud/telnet/container-host rows.
     */
    fun pickerLabel(context: Context, host: ConnectableHost): String {
        val badge = sourceBadge(host.sourceType)
        return if (badge == null) {
            context.getString(R.string.connectable_host_label_fmt, host.name, host.hostPreview)
        } else {
            context.getString(
                R.string.connectable_host_label_badged_fmt,
                host.name, host.hostPreview, context.getString(badge)
            )
        }
    }
}
