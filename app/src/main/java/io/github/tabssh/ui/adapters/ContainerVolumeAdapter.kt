package io.github.tabssh.ui.adapters

import android.content.Context
import io.github.tabssh.containers.transport.ContainerVolumeSummary

/** Volume list rows: name, driver, mountpoint. */
class ContainerVolumeAdapter : ContainerSimpleRowAdapter<ContainerVolumeSummary>() {

    override fun keyOf(item: ContainerVolumeSummary): String = item.name

    override fun linesOf(item: ContainerVolumeSummary, context: Context): Triple<String, String, String> =
        Triple(item.name, item.driver, item.mountpoint)
}
