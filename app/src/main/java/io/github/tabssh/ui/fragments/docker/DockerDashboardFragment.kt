package io.github.tabssh.ui.fragments.docker

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import io.github.tabssh.R
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.DockerDiskUsage
import io.github.tabssh.docker.transport.DockerEngineInfo
import io.github.tabssh.docker.transport.DockerVersionInfo
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import kotlinx.coroutines.launch

/**
 * Host dashboard destination (PLAN.AI.md step 22): engine info, version, and
 * disk usage from the shared transport.
 */
class DockerDashboardFragment : DockerPageFragment() {

    private lateinit var progressBar: ProgressBar
    private lateinit var cardEngine: MaterialCardView
    private lateinit var cardDisk: MaterialCardView
    private lateinit var textEngineName: TextView
    private lateinit var textEngineVersion: TextView
    private lateinit var textEngineOs: TextView
    private lateinit var textEngineContainers: TextView
    private lateinit var textEngineImages: TextView
    private lateinit var textEngineResources: TextView
    private lateinit var containerDiskRows: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_docker_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        progressBar = view.findViewById(R.id.progress_bar)
        cardEngine = view.findViewById(R.id.card_engine)
        cardDisk = view.findViewById(R.id.card_disk)
        textEngineName = view.findViewById(R.id.text_engine_name)
        textEngineVersion = view.findViewById(R.id.text_engine_version)
        textEngineOs = view.findViewById(R.id.text_engine_os)
        textEngineContainers = view.findViewById(R.id.text_engine_containers)
        textEngineImages = view.findViewById(R.id.text_engine_images)
        textEngineResources = view.findViewById(R.id.text_engine_resources)
        containerDiskRows = view.findViewById(R.id.container_disk_rows)

        cardEngine.visibility = View.GONE
        cardDisk.visibility = View.GONE

        // Base class wires sessionFlow/refreshFlow into onSessionReady.
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onSessionReady(session: DockerSessionManager.DockerSession) {
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val info = session.transport.engineInfo()
            val version = session.transport.engineVersion()
            val disk = session.transport.diskUsage()
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE

            val infoValue = info.valueOrNull()
            if (infoValue != null) {
                bindEngine(infoValue, version.valueOrNull())
            } else {
                DockerErrorPresenter.present(requireContext(), info)
            }
            disk.valueOrNull()?.let { bindDisk(it) }
        }
    }

    private fun bindEngine(info: DockerEngineInfo, version: DockerVersionInfo?) {
        cardEngine.visibility = View.VISIBLE
        textEngineName.text = info.name
        textEngineVersion.text = getString(
            R.string.docker_dashboard_version_fmt,
            version?.version ?: info.serverVersion,
            version?.apiVersion ?: "?"
        )
        textEngineOs.text = getString(
            R.string.docker_dashboard_os_fmt, info.operatingSystem, info.architecture
        )
        textEngineContainers.text = getString(
            R.string.docker_dashboard_containers_fmt,
            info.containersTotal, info.containersRunning,
            info.containersPaused, info.containersStopped
        )
        textEngineImages.text = getString(R.string.docker_dashboard_images_fmt, info.images)
        textEngineResources.text = getString(
            R.string.docker_dashboard_cpu_mem_fmt,
            info.ncpu,
            Formatter.formatShortFileSize(requireContext(), info.memTotalBytes)
        )
    }

    private fun bindDisk(disk: DockerDiskUsage) {
        cardDisk.visibility = View.VISIBLE
        containerDiskRows.removeAllViews()
        val ctx = requireContext()
        // Match the XML data rows: 13sp, theme onSurface, space_xs between rows.
        val rowColor = com.google.android.material.color.MaterialColors.getColor(
            containerDiskRows, com.google.android.material.R.attr.colorOnSurface
        )
        val rowSpacing = resources.getDimensionPixelSize(R.dimen.space_xs)
        disk.rows.forEach { row ->
            val text = TextView(ctx)
            text.textSize = 13f
            text.setTextColor(rowColor)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = rowSpacing
            text.layoutParams = params
            text.text = getString(
                R.string.docker_dashboard_disk_row_fmt,
                row.type,
                row.totalCount,
                row.active,
                Formatter.formatShortFileSize(ctx, row.sizeBytes),
                Formatter.formatShortFileSize(ctx, row.reclaimableBytes)
            )
            containerDiskRows.addView(text)
        }
    }
}
