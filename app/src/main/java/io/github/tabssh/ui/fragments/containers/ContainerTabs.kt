package io.github.tabssh.ui.fragments.containers

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import io.github.tabssh.R
import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.containers.EngineCapability

/**
 * One destination of the per-host container view.
 *
 * [capability] is the only thing that decides whether the tab exists: a
 * concept the engine does not have is hidden, never shown empty (IDEA.md
 * § Container host management). Nothing in the fragments branches on the
 * engine, so adding an engine is a change to [ContainerEngine.capabilities]
 * alone.
 */
data class ContainerTabSpec(
    val capability: EngineCapability,
    @StringRes val titleRes: Int,
    val create: () -> Fragment
)

/** The per-host destination registry, in the order the tabs are shown. */
object ContainerTabs {

    /**
     * Declaration order IS tab order: Dashboard, Containers, Stacks, Images,
     * Volumes, Networks, then the destinations only Incus and LXC/LXD have —
     * Snapshots, Profiles, Projects. Each is gated on its capability, so an
     * engine without the concept gets no tab rather than an empty one.
     */
    private val ALL: List<ContainerTabSpec> = listOf(
        ContainerTabSpec(
            EngineCapability.CONTAINERS,
            R.string.container_manager_tab_dashboard
        ) { ContainerDashboardFragment() },
        ContainerTabSpec(
            EngineCapability.CONTAINERS,
            R.string.container_manager_tab_containers
        ) { ContainerListFragment() },
        ContainerTabSpec(
            EngineCapability.COMPOSE_STACKS,
            R.string.container_manager_tab_stacks
        ) { ContainerStacksFragment() },
        ContainerTabSpec(
            EngineCapability.IMAGES,
            R.string.container_manager_tab_images
        ) { ContainerImagesFragment() },
        ContainerTabSpec(
            EngineCapability.VOLUMES,
            R.string.container_manager_tab_volumes
        ) { ContainerVolumesFragment() },
        ContainerTabSpec(
            EngineCapability.NETWORKS,
            R.string.container_manager_tab_networks
        ) { ContainerNetworksFragment() },
        ContainerTabSpec(
            EngineCapability.SNAPSHOTS,
            R.string.container_manager_tab_snapshots
        ) { ContainerSnapshotsFragment() },
        ContainerTabSpec(
            EngineCapability.PROFILES,
            R.string.container_manager_tab_profiles
        ) { ContainerProfilesFragment() },
        ContainerTabSpec(
            EngineCapability.PROJECTS,
            R.string.container_manager_tab_projects
        ) { ContainerProjectsFragment() }
    )

    /** The destinations [engine] actually has, in tab order. */
    fun forEngine(engine: ContainerEngine): List<ContainerTabSpec> =
        ALL.filter { engine.supports(it.capability) }
}
