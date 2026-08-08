package io.github.tabssh.ui.adapters

import android.content.Context
import io.github.tabssh.docker.transport.DockerVolumeSummary

/** Volume list rows: name, driver, mountpoint. */
class DockerVolumeAdapter : DockerSimpleRowAdapter<DockerVolumeSummary>() {

    override fun keyOf(item: DockerVolumeSummary): String = item.name

    override fun linesOf(item: DockerVolumeSummary, context: Context): Triple<String, String, String> =
        Triple(item.name, item.driver, item.mountpoint)
}
