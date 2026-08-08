package io.github.tabssh.ui.adapters

import android.content.Context
import io.github.tabssh.docker.transport.DockerNetworkSummary

/** Network list rows: name, driver · scope, id. */
class DockerNetworkAdapter : DockerSimpleRowAdapter<DockerNetworkSummary>() {

    override fun keyOf(item: DockerNetworkSummary): String = item.id

    override fun linesOf(item: DockerNetworkSummary, context: Context): Triple<String, String, String> =
        Triple(item.name, "${item.driver} · ${item.scope}", item.id.take(12))
}
