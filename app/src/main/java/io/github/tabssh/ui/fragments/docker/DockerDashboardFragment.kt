package io.github.tabssh.ui.fragments.docker

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import io.github.tabssh.R
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.DockerDiskUsage
import io.github.tabssh.docker.transport.DockerEngineInfo
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.DockerVersionInfo
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.utils.DockerText
import io.github.tabssh.utils.coroutines.loadConcurrently
import io.github.tabssh.utils.logging.Logger

/** Cap for a single daemon-reported field inside a formatted dashboard line. */
private const val MAX_FIELD = 64

/** Upper bound on `docker system df` rows rendered as child views. */
private const val MAX_DISK_ROWS = 32

/**
 * Hard bound on the whole dashboard load (info + version + disk). The three
 * calls run concurrently below, so this is the worst case of any ONE of
 * them, not their sum — generous enough for a slow CLI-tier host, but it
 * guarantees the spinner resolves into an error instead of spinning forever
 * when a probe stalls (see AI.md PART 9 "every network error surfaces
 * through the PART 2 error surfaces with a retry path").
 */
private const val DASHBOARD_LOAD_TIMEOUT_MS = 45_000L

/**
 * Host dashboard destination: engine info, version, and
 * disk usage from the shared transport.
 */
class DockerDashboardFragment : DockerPageFragment() {

    companion object {
        private const val TAG = "DockerDashboardFragment"
    }

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
        Logger.d(TAG, "load: start hostId=${session.host.id} mode=${session.mode}")
        startLoad {
            // Concurrent, not sequential: one slow call must not delay the
            // other two, and the overall timeout bounds the worst case to
            // one call's timeout instead of their sum.
            val loaded = loadConcurrently(
                DASHBOARD_LOAD_TIMEOUT_MS,
                a = { session.transport.engineInfo() },
                b = { session.transport.engineVersion() },
                c = { session.transport.diskUsage() }
            )
            if (!isAdded) return@startLoad
            progressBar.visibility = View.GONE

            if (loaded == null) {
                Logger.w(TAG, "load: timed out after ${DASHBOARD_LOAD_TIMEOUT_MS}ms hostId=${session.host.id}")
                DockerErrorPresenter.present(
                    requireContext(),
                    DockerResult.Error("dashboard", "Timed out loading engine status")
                )
                return@startLoad
            }

            val (info, version, disk) = loaded
            Logger.d(
                TAG,
                "load: done hostId=${session.host.id} info=${info.outcome()} " +
                    "version=${version.outcome()} disk=${disk.outcome()}"
            )

            val infoValue = info.valueOrNull()
            if (infoValue != null) {
                bindEngine(infoValue, version.valueOrNull())
            } else {
                DockerErrorPresenter.present(requireContext(), info)
            }
            disk.valueOrNull()?.let { bindDisk(it) }
        }
    }

    /** Short, credential-free outcome tag for a [DockerResult] — safe to log. */
    private fun <T> DockerResult<T>.outcome(): String = when (this) {
        is DockerResult.Success -> "ok"
        is DockerResult.PermissionDenied -> "permission_denied"
        is DockerResult.NotFound -> "not_found"
        is DockerResult.TransportUnavailable -> "transport_unavailable"
        is DockerResult.Error -> "error"
    }

    private fun bindEngine(info: DockerEngineInfo, version: DockerVersionInfo?) {
        cardEngine.visibility = View.VISIBLE
        // Engine name, version and platform strings are whatever the remote
        // daemon reports — strip control/bidi characters before display.
        textEngineName.text = DockerText.display(info.name)
        textEngineVersion.text = getString(
            R.string.docker_dashboard_version_fmt,
            DockerText.display(version?.version ?: info.serverVersion, MAX_FIELD),
            DockerText.display(version?.apiVersion ?: "?", MAX_FIELD)
        )
        textEngineOs.text = getString(
            R.string.docker_dashboard_os_fmt,
            DockerText.display(info.operatingSystem, MAX_FIELD),
            DockerText.display(info.architecture, MAX_FIELD)
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
        // A daemon that reports hundreds of df rows would otherwise inflate
        // hundreds of TextViews on the main thread.
        disk.rows.take(MAX_DISK_ROWS).forEach { row ->
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
                DockerText.display(row.type, MAX_FIELD),
                row.totalCount,
                row.active,
                Formatter.formatShortFileSize(ctx, row.sizeBytes),
                Formatter.formatShortFileSize(ctx, row.reclaimableBytes)
            )
            containerDiskRows.addView(text)
        }
    }
}
