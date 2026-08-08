package io.github.tabssh.ui.adapters

import android.content.Context
import android.text.format.Formatter
import io.github.tabssh.R
import io.github.tabssh.docker.transport.DockerImageSummary

/** Image list rows: repo:tag (or dangling), size, created. */
class DockerImageAdapter : DockerSimpleRowAdapter<DockerImageSummary>() {

    override fun keyOf(item: DockerImageSummary): String = item.id

    override fun linesOf(item: DockerImageSummary, context: Context): Triple<String, String, String> {
        val title = item.repoTags.firstOrNull()
            ?: context.getString(R.string.docker_image_dangling)
        val subtitle = Formatter.formatShortFileSize(context, item.sizeBytes)
        return Triple(title, subtitle, item.created)
    }
}
