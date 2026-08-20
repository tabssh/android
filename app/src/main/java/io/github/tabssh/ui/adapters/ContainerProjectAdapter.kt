package io.github.tabssh.ui.adapters

import android.content.Context
import io.github.tabssh.R
import io.github.tabssh.containers.transport.ContainerProjectSummary

/** Project list rows: name, description or object count, active marker. */
class ContainerProjectAdapter : ContainerSimpleRowAdapter<ContainerProjectSummary>() {

    override fun keyOf(item: ContainerProjectSummary): String = item.name

    override fun linesOf(item: ContainerProjectSummary, context: Context): Triple<String, String, String> {
        val subtitle = item.description.ifEmpty {
            context.getString(R.string.container_project_used_by_fmt, item.usedByCount)
        }
        val detail = if (item.active) context.getString(R.string.container_project_active) else ""
        return Triple(item.name, subtitle, detail)
    }
}
