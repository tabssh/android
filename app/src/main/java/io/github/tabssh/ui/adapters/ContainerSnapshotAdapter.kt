package io.github.tabssh.ui.adapters

import android.content.Context
import io.github.tabssh.R
import io.github.tabssh.containers.transport.ContainerSnapshotSummary

/** Snapshot list rows: name, instance (· stateful), created or expiry. */
class ContainerSnapshotAdapter : ContainerSimpleRowAdapter<ContainerSnapshotSummary>() {

    override fun keyOf(item: ContainerSnapshotSummary): String = "${item.instance}/${item.name}"

    override fun linesOf(item: ContainerSnapshotSummary, context: Context): Triple<String, String, String> {
        val subtitle = if (item.stateful) {
            "${item.instance} · ${context.getString(R.string.container_snapshot_stateful_badge)}"
        } else {
            item.instance
        }
        val detail = when {
            item.expires.isNotEmpty() ->
                context.getString(R.string.container_snapshot_expires_fmt, item.expires)
            else -> item.created
        }
        return Triple(item.name, subtitle, detail)
    }
}
