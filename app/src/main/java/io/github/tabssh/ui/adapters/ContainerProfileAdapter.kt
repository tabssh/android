package io.github.tabssh.ui.adapters

import android.content.Context
import io.github.tabssh.R
import io.github.tabssh.containers.transport.ContainerProfileSummary

/** Profile list rows: name, description or usage count, attached devices. */
class ContainerProfileAdapter : ContainerSimpleRowAdapter<ContainerProfileSummary>() {

    override fun keyOf(item: ContainerProfileSummary): String = item.name

    override fun linesOf(item: ContainerProfileSummary, context: Context): Triple<String, String, String> {
        // A profile usually has a description; when it has none the usage
        // count is the more useful thing to put on the second line.
        val subtitle = item.description.ifEmpty {
            context.getString(R.string.container_profile_used_by_fmt, item.usedBy.size)
        }
        val detail = if (item.devices.isEmpty()) {
            ""
        } else {
            context.getString(R.string.container_profile_devices_fmt, item.devices.joinToString(", "))
        }
        return Triple(item.name, subtitle, detail)
    }
}
