package io.github.tabssh.ui.fragments.containers

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.card.MaterialCardView
import io.github.tabssh.R
import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.EngineCapability
import io.github.tabssh.containers.transport.ContainerDiskUsage
import io.github.tabssh.containers.transport.ContainerEngineInfo
import io.github.tabssh.containers.transport.ContainerEngineVersion
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.ui.utils.ContainerEngineLabels
import io.github.tabssh.ui.utils.ContainerText
import io.github.tabssh.utils.Format
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Cap for a single daemon-reported field inside a formatted dashboard line. */
private const val MAX_FIELD = 64

/** Upper bound on `system df` rows rendered as child views. */
private const val MAX_DISK_ROWS = 32

/**
 * Hard bound on the whole dashboard load. Every call below runs concurrently,
 * so this is the worst case of any ONE of them, not their sum — generous
 * enough for a slow CLI-tier host, but it guarantees the spinner resolves
 * into a message instead of spinning forever when a probe stalls (AI.md
 * PART 9: every network error surfaces with a retry path).
 */
private const val DASHBOARD_LOAD_TIMEOUT_MS = 45_000L

/**
 * Per-host dashboard — the first destination.
 *
 * Shows the host's name and engine, one inventory count per concept the
 * engine actually has, the engine build details, and disk usage on engines
 * that report it. The container count deliberately counts compose stack
 * members even though the Containers tab hides them, so a host with 3
 * standalone containers and 2 stacks of 2 reads "Stacks 2, Containers 7"
 * (IDEA.md § Container host management).
 */
class ContainerDashboardFragment : ContainerPageFragment() {

    companion object {
        private const val TAG = "ContainerDashboardFragment"
    }

    private lateinit var progressBar: ProgressBar
    private lateinit var cardHost: MaterialCardView
    private lateinit var cardCounts: MaterialCardView
    private lateinit var cardEngine: MaterialCardView
    private lateinit var cardDisk: MaterialCardView
    private lateinit var textHostName: TextView
    private lateinit var textHostEngine: TextView
    private lateinit var textEngineName: TextView
    private lateinit var textEngineVersion: TextView
    private lateinit var textEngineOs: TextView
    private lateinit var textEngineContainers: TextView
    private lateinit var textEngineResources: TextView
    private lateinit var textNotice: TextView
    private lateinit var containerCountRows: LinearLayout
    private lateinit var containerDiskRows: LinearLayout

    /** One inventory line; a null [value] means the host did not report it. */
    private data class CountRow(
        @DrawableRes val iconRes: Int,
        @StringRes val labelRes: Int,
        val value: Int?
    )

    /** Everything the dashboard renders, gathered in one concurrent pass. */
    private data class DashboardLoad(
        val info: ContainerResult<ContainerEngineInfo>,
        val version: ContainerResult<ContainerEngineVersion>,
        val stacks: Int?,
        val containers: Int?,
        val networks: Int?,
        val volumes: Int?,
        val images: Int?,
        val disk: ContainerDiskUsage?
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_container_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        progressBar = view.findViewById(R.id.progress_bar)
        cardHost = view.findViewById(R.id.card_host)
        cardCounts = view.findViewById(R.id.card_counts)
        cardEngine = view.findViewById(R.id.card_engine)
        cardDisk = view.findViewById(R.id.card_disk)
        textHostName = view.findViewById(R.id.text_host_name)
        textHostEngine = view.findViewById(R.id.text_host_engine)
        textEngineName = view.findViewById(R.id.text_engine_name)
        textEngineVersion = view.findViewById(R.id.text_engine_version)
        textEngineOs = view.findViewById(R.id.text_engine_os)
        textEngineContainers = view.findViewById(R.id.text_engine_containers)
        textEngineResources = view.findViewById(R.id.text_engine_resources)
        textNotice = view.findViewById(R.id.text_dashboard_notice)
        containerCountRows = view.findViewById(R.id.container_count_rows)
        containerDiskRows = view.findViewById(R.id.container_disk_rows)

        // Base class wires sessionFlow/refreshFlow into onSessionReady.
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onSessionReady(session: ContainerSessionManager.ContainerSession) {
        val engine = session.host.engineType()
        bindHost(session, engine)
        progressBar.visibility = View.VISIBLE
        textNotice.visibility = View.GONE
        Logger.d(TAG, "load: start hostId=${session.host.id} engine=${engine.id}")
        startLoad {
            val loaded = load(session, engine)
            if (!isAdded) return@startLoad
            progressBar.visibility = View.GONE

            if (loaded == null) {
                Logger.w(TAG, "load: timed out after ${DASHBOARD_LOAD_TIMEOUT_MS}ms hostId=${session.host.id}")
                showNotice(getString(R.string.container_dashboard_timeout))
                return@startLoad
            }

            Logger.d(
                TAG,
                "load: done hostId=${session.host.id} info=${loaded.info.outcome()} " +
                    "version=${loaded.version.outcome()}"
            )

            bindCounts(engine, loaded)
            val infoValue = loaded.info.valueOrNull()
            if (infoValue != null) {
                bindEngine(engine, infoValue, loaded.version.valueOrNull())
            } else {
                cardEngine.visibility = View.GONE
            }
            loaded.disk?.let { bindDisk(it) } ?: run { cardDisk.visibility = View.GONE }
            bindNotice(loaded, infoValue != null)
        }
    }

    /**
     * Every listing the engine supports, fetched concurrently under one
     * timeout — a host with a slow image store must not delay the counts that
     * already resolved.
     */
    private suspend fun load(
        session: ContainerSessionManager.ContainerSession,
        engine: ContainerEngine
    ): DashboardLoad? = withTimeoutOrNull(DASHBOARD_LOAD_TIMEOUT_MS) {
        coroutineScope {
            val info = async { session.transport.engineInfo() }
            val version = async { session.transport.engineVersion() }
            val containers = async { session.transport.listContainers(all = true).valueOrNull()?.size }
            val stacks = async { if (engine.supports(EngineCapability.COMPOSE_STACKS)) stackCount(session) else null }
            val networks = async {
                if (engine.supports(EngineCapability.NETWORKS)) {
                    session.transport.listNetworks().valueOrNull()?.size
                } else {
                    null
                }
            }
            val volumes = async {
                if (engine.supports(EngineCapability.VOLUMES)) {
                    session.transport.listVolumes().valueOrNull()?.size
                } else {
                    null
                }
            }
            val images = async {
                if (engine.supports(EngineCapability.IMAGES)) {
                    session.transport.listImages().valueOrNull()?.size
                } else {
                    null
                }
            }
            val disk = async {
                if (engine.supports(EngineCapability.DISK_USAGE)) {
                    session.transport.diskUsage().valueOrNull()
                } else {
                    null
                }
            }
            DashboardLoad(
                info = info.await(),
                version = version.await(),
                stacks = stacks.await(),
                containers = containers.await(),
                networks = networks.await(),
                volumes = volumes.await(),
                images = images.await(),
                disk = disk.await()
            )
        }
    }

    /**
     * Stacks the user can act on: the app's own tracked stacks merged by name
     * with the projects `compose ls` discovers — exactly the set the Stacks
     * tab lists, so the two never disagree.
     */
    private suspend fun stackCount(session: ContainerSessionManager.ContainerSession): Int {
        val tracked = app.database.composeStackDao()
            .getStacksForHostList(session.host.id)
            .map { it.name }
        val discovered = session.transport.composeLs().valueOrNull().orEmpty().map { it.name }
        return (tracked + discovered).toSet().size
    }

    private fun bindHost(
        session: ContainerSessionManager.ContainerSession,
        engine: ContainerEngine
    ) {
        cardHost.visibility = View.VISIBLE
        textHostName.text = ContainerText.display(session.host.name, MAX_FIELD)
        textHostEngine.text = getString(
            R.string.container_dashboard_engine_fmt,
            getString(ContainerEngineLabels.engineName(engine)),
            getString(ContainerEngineLabels.transportMode(session.mode))
        )
    }

    /** One row per concept the engine has; concepts it lacks contribute none. */
    private fun bindCounts(engine: ContainerEngine, loaded: DashboardLoad) {
        val rows = mutableListOf<CountRow>()
        if (engine.supports(EngineCapability.COMPOSE_STACKS)) {
            rows += CountRow(
                R.drawable.ic_container_stack, R.string.container_manager_tab_stacks, loaded.stacks
            )
        }
        if (engine.supports(EngineCapability.CONTAINERS)) {
            rows += CountRow(
                R.drawable.ic_container, R.string.container_manager_tab_containers, loaded.containers
            )
        }
        if (engine.supports(EngineCapability.NETWORKS)) {
            rows += CountRow(
                R.drawable.ic_container_network, R.string.container_manager_tab_networks, loaded.networks
            )
        }
        if (engine.supports(EngineCapability.VOLUMES)) {
            rows += CountRow(
                R.drawable.ic_container_volume, R.string.container_manager_tab_volumes, loaded.volumes
            )
        }
        if (engine.supports(EngineCapability.IMAGES)) {
            rows += CountRow(
                R.drawable.ic_container_image, R.string.container_manager_tab_images, loaded.images
            )
        }

        cardCounts.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        containerCountRows.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        rows.forEach { row ->
            val view = inflater.inflate(R.layout.item_container_count_row, containerCountRows, false)
            val label = getString(row.labelRes)
            view.findViewById<ImageView>(R.id.image_count_icon).setImageResource(row.iconRes)
            view.findViewById<TextView>(R.id.text_count_label).text = label
            view.findViewById<TextView>(R.id.text_count_value).text =
                row.value?.let { Format.count(it) }
                    ?: getString(R.string.container_dashboard_count_unavailable)
            // The row is one TalkBack node reading "Containers: 7" instead of
            // three separate stops for icon, label and number — and the dash
            // that stands in for a missing count is spoken as words.
            view.contentDescription = row.value?.let {
                getString(R.string.container_dashboard_count_desc_fmt, label, Format.count(it))
            } ?: getString(R.string.container_dashboard_count_desc_unavailable_fmt, label)
            containerCountRows.addView(view)
        }
    }

    private fun bindEngine(
        engine: ContainerEngine,
        info: ContainerEngineInfo,
        version: ContainerEngineVersion?
    ) {
        cardEngine.visibility = View.VISIBLE
        // Engine name, version and platform strings are whatever the remote
        // daemon reports — strip control/bidi characters before display.
        textEngineName.text = ContainerText.display(info.name, MAX_FIELD)
        textEngineVersion.text = getString(
            R.string.container_dashboard_version_fmt,
            getString(ContainerEngineLabels.engineName(engine)),
            ContainerText.display(version?.version ?: info.serverVersion, MAX_FIELD),
            ContainerText.display(version?.apiVersion ?: "", MAX_FIELD)
                .ifBlank { getString(R.string.container_value_unknown) }
        )
        textEngineOs.text = getString(
            R.string.container_dashboard_os_fmt,
            ContainerText.display(info.operatingSystem, MAX_FIELD),
            ContainerText.display(info.architecture, MAX_FIELD)
        )
        textEngineContainers.text = getString(
            R.string.container_dashboard_containers_fmt,
            info.containersRunning, info.containersPaused, info.containersStopped
        )
        textEngineResources.text = getString(
            R.string.container_dashboard_cpu_mem_fmt,
            info.ncpu,
            Formatter.formatShortFileSize(requireContext(), info.memTotalBytes)
        )
    }

    private fun bindDisk(disk: ContainerDiskUsage) {
        cardDisk.visibility = View.VISIBLE
        containerDiskRows.removeAllViews()
        val ctx = requireContext()
        // Match the XML data rows: caption size, theme onSurface, space_xs gap.
        val rowColor = com.google.android.material.color.MaterialColors.getColor(
            containerDiskRows, com.google.android.material.R.attr.colorOnSurface
        )
        val rowSpacing = resources.getDimensionPixelSize(R.dimen.space_xs)
        val rowTextSize = resources.getDimension(R.dimen.text_size_caption)
        // A daemon that reports hundreds of df rows would otherwise inflate
        // hundreds of TextViews on the main thread.
        disk.rows.take(MAX_DISK_ROWS).forEach { row ->
            val text = TextView(ctx)
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, rowTextSize)
            text.setTextColor(rowColor)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = rowSpacing
            text.layoutParams = params
            text.text = getString(
                R.string.container_dashboard_disk_row_fmt,
                ContainerText.display(row.type, MAX_FIELD),
                Format.count(row.totalCount),
                Format.count(row.active),
                Formatter.formatShortFileSize(ctx, row.sizeBytes),
                Formatter.formatShortFileSize(ctx, row.reclaimableBytes)
            )
            containerDiskRows.addView(text)
        }
    }

    /**
     * A partly-readable host still renders every count it did return; the
     * notice names the gap so a missing number is never mistaken for a zero.
     */
    private fun bindNotice(loaded: DashboardLoad, engineInfoOk: Boolean) {
        val missing = listOf(
            loaded.containers, loaded.networks, loaded.volumes, loaded.images
        ).any { it == null } || !engineInfoOk
        if (missing) {
            showNotice(getString(R.string.container_dashboard_partial))
        } else {
            textNotice.visibility = View.GONE
        }
    }

    private fun showNotice(message: String) {
        textNotice.text = message
        textNotice.visibility = View.VISIBLE
    }

    /** Short, credential-free outcome tag for a [ContainerResult] — safe to log. */
    private fun <T> ContainerResult<T>.outcome(): String = when (this) {
        is ContainerResult.Success -> "ok"
        is ContainerResult.PermissionDenied -> "permission_denied"
        is ContainerResult.NotFound -> "not_found"
        is ContainerResult.EngineNotInstalled -> "engine_not_installed"
        is ContainerResult.TransportUnavailable -> "transport_unavailable"
        is ContainerResult.Error -> "error"
    }
}
