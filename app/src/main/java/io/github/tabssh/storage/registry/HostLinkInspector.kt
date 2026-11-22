package io.github.tabssh.storage.registry

import android.content.Context
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.containers.ContainerHostRemover
import org.json.JSONArray

/**
 * What else points at a saved SSH connection. Deleting the connection also takes these with it
 * (or unlinks them), so the delete confirmation lists them and [removeLinks] does the work.
 */
object HostLinkInspector {

    enum class Kind { DOCKER_HOST, HYPERVISOR, PORT_FORWARD, PANE_GROUP, DASHBOARD }

    data class Link(val kind: Kind, val label: String)

    private const val DASHBOARD_PREFS = "multi_host_dashboard"
    private const val KEY_GROUPS = "dash_groups_json"
    private const val KEY_HOSTS_PREFIX = "dash_hosts_"
    private const val UNGROUPED_ID = "__ungrouped__"

    suspend fun describe(app: TabSSHApplication, connectionId: String): List<Link> {
        val db = app.database
        val links = mutableListOf<Link>()
        db.containerHostDao().getAllList().filter { it.linkedConnectionId == connectionId }
            .forEach { links += Link(Kind.DOCKER_HOST, it.name) }
        db.hypervisorDao().getAllList().filter { it.linkedConnectionId == connectionId }
            .forEach { links += Link(Kind.HYPERVISOR, it.name) }
        db.portForwardDao().getByConnection(connectionId)
            .forEach { links += Link(Kind.PORT_FORWARD, it.name.ifBlank { it.id }) }
        db.paneGroupDao().getAllList()
            .filter { g -> connectionId in g.memberHostIds || g.windows.any { it.hostId == connectionId } }
            .forEach { links += Link(Kind.PANE_GROUP, it.name) }
        dashboardGroupsContaining(app, connectionId).forEach { links += Link(Kind.DASHBOARD, it) }
        return links
    }

    /** Delete confirmation text: one line per kind, naming what goes with the host. */
    fun summary(context: Context, links: List<Link>): String {
        if (links.isEmpty()) return ""
        val lines = Kind.values().mapNotNull { kind ->
            val names = links.filter { it.kind == kind }.map { it.label }
            if (names.isEmpty()) null else context.getString(
                when (kind) {
                    Kind.DOCKER_HOST -> io.github.tabssh.R.string.host_links_docker
                    Kind.HYPERVISOR -> io.github.tabssh.R.string.host_links_hypervisor
                    Kind.PORT_FORWARD -> io.github.tabssh.R.string.host_links_port_forward
                    Kind.PANE_GROUP -> io.github.tabssh.R.string.host_links_pane_group
                    Kind.DASHBOARD -> io.github.tabssh.R.string.host_links_dashboard
                },
                names.joinToString(", ")
            )
        }
        return lines.joinToString("\n")
    }

    /** Pane groups and dashboard groups that list a container host (registry id, not a connection id). */
    suspend fun describeContainerHost(app: TabSSHApplication, hostRowId: Long): List<Link> {
        val memberId = io.github.tabssh.storage.database.entities.ContainerHost.ALIAS_PREFIX + hostRowId
        val links = mutableListOf<Link>()
        app.database.paneGroupDao().getAllList()
            .filter { g -> memberId in g.memberHostIds || g.windows.any { it.hostId == memberId } }
            .forEach { links += Link(Kind.PANE_GROUP, it.name) }
        dashboardGroupsContaining(app, memberId).forEach { links += Link(Kind.DASHBOARD, it) }
        return links
    }

    /** Removes or unlinks everything [describe] listed. Call before deleting the connection itself. */
    suspend fun removeLinks(app: TabSSHApplication, connectionId: String) {
        val db = app.database
        val appContext = app.applicationContext
        db.containerHostDao().getAllList().filter { it.linkedConnectionId == connectionId }
            .forEach { ContainerHostRemover.remove(app, appContext, it) }
        db.hypervisorDao().clearLinkedConnectionId(connectionId)
        db.portForwardDao().getByConnection(connectionId).forEach { db.portForwardDao().deleteById(it.id) }
        stripFromDashboards(app, connectionId)
    }

    private fun dashboardPrefs(context: Context) =
        context.getSharedPreferences(DASHBOARD_PREFS, Context.MODE_PRIVATE)

    private fun groupNames(context: Context): Map<String, String> = try {
        val arr = JSONArray(dashboardPrefs(context).getString(KEY_GROUPS, "[]") ?: "[]")
        (0 until arr.length()).associate { arr.getJSONObject(it).getString("id") to arr.getJSONObject(it).getString("name") }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun dashboardGroupsContaining(context: Context, hostId: String): List<String> {
        val prefs = dashboardPrefs(context)
        val names = groupNames(context)
        return (names.keys + UNGROUPED_ID).mapNotNull { gid ->
            val members = (prefs.getString(KEY_HOSTS_PREFIX + gid, "") ?: "").split(",")
            if (hostId in members) names[gid] ?: context.getString(io.github.tabssh.R.string.host_links_dashboard_ungrouped) else null
        }
    }

    fun stripFromDashboards(context: Context, hostId: String) {
        val prefs = dashboardPrefs(context)
        val editor = prefs.edit()
        (groupNames(context).keys + UNGROUPED_ID).forEach { gid ->
            val members = (prefs.getString(KEY_HOSTS_PREFIX + gid, "") ?: "").split(",").filter { it.isNotBlank() }
            if (hostId in members) editor.putString(KEY_HOSTS_PREFIX + gid, (members - hostId).joinToString(","))
        }
        editor.apply()
    }
}
