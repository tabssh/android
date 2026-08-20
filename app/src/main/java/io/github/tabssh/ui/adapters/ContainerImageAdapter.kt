package io.github.tabssh.ui.adapters

import android.content.Context
import android.text.format.Formatter
import io.github.tabssh.R
import io.github.tabssh.containers.transport.ContainerImageSummary

/** Image list rows: repo:tag (or dangling), size, created. */
class ContainerImageAdapter : ContainerSimpleRowAdapter<ContainerImageSummary>() {

    override fun keyOf(item: ContainerImageSummary): String = item.id

    override fun linesOf(item: ContainerImageSummary, context: Context): Triple<String, String, String> {
        val title = item.repoTags.firstOrNull()
            ?: context.getString(R.string.container_image_dangling)
        val subtitle = Formatter.formatShortFileSize(context, item.sizeBytes)
        return Triple(title, subtitle, item.created)
    }
}
