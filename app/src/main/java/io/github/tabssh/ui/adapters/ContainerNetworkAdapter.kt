package io.github.tabssh.ui.adapters

import android.content.Context
import io.github.tabssh.containers.transport.ContainerNetworkSummary

/** Network list rows: name, driver · scope, id. */
class ContainerNetworkAdapter : ContainerSimpleRowAdapter<ContainerNetworkSummary>() {

    override fun keyOf(item: ContainerNetworkSummary): String = item.id

    override fun linesOf(item: ContainerNetworkSummary, context: Context): Triple<String, String, String> =
        Triple(item.name, "${item.driver} · ${item.scope}", item.id.take(12))
}
