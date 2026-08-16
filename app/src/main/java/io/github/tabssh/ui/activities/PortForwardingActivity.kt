package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.NetworkRoute
import io.github.tabssh.storage.database.entities.PortForward
import io.github.tabssh.sync.tombstone.TombstoneRecorder
import io.github.tabssh.ui.adapters.NetworkRouteAdapter
import io.github.tabssh.ui.adapters.PortForwardAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified "Routing & Forwarding" hub.
 *
 * Two sections stacked in one scroll view: reusable [NetworkRoute] definitions
 * (proxies and SSH jump hosts, plus the global default route selector) above
 * the saved [PortForward] rules. Both lists observe the database as Flows and
 * expose add / edit / delete.
 */
class PortForwardingActivity : AppCompatActivity() {

    private val app: TabSSHApplication
        get() = application as TabSSHApplication

    // Routes section
    private lateinit var recyclerRoutes: RecyclerView
    private lateinit var emptyRoutes: View
    private lateinit var routeAdapter: NetworkRouteAdapter
    private lateinit var cardDefaultRoute: MaterialCardView
    private lateinit var textDefaultRouteValue: TextView
    private lateinit var btnAddRoute: MaterialButton

    // Forwards section
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var adapter: PortForwardAdapter

    // Latest route list, kept so the default-route label and chooser can resolve
    // an id to its name without another DB round trip.
    private var currentRoutes: List<NetworkRoute> = emptyList()

    // Optional: when launched from a terminal tab, a new forward defaults to
    // that saved connection.
    private var prefillConnectionId: String? = null

    private val routeEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // The route list Flow refreshes the UI automatically; nothing to do
            // here beyond letting the observer re-render.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_port_forwarding)

        prefillConnectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)

        setupToolbar()
        setupRoutesSection()
        setupForwardsSection()
        setupFab()
        observeRoutes()
        observeForwards()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.port_forwarding_title)
        }
    }

    // -------------------------------------------------------------------------
    // Network Routes
    // -------------------------------------------------------------------------

    private fun setupRoutesSection() {
        recyclerRoutes = findViewById(R.id.recycler_routes)
        emptyRoutes = findViewById(R.id.empty_routes)
        cardDefaultRoute = findViewById(R.id.card_default_route)
        textDefaultRouteValue = findViewById(R.id.text_default_route_value)
        btnAddRoute = findViewById(R.id.btn_add_route)

        recyclerRoutes.layoutManager = LinearLayoutManager(this)
        recyclerRoutes.isNestedScrollingEnabled = false
        routeAdapter = NetworkRouteAdapter(
            onEdit = { openRouteEditor(it.id) },
            onDelete = { confirmDeleteRoute(it) },
            onToggleEnabled = { route, enabled -> setRouteEnabled(route, enabled) }
        )
        recyclerRoutes.adapter = routeAdapter

        btnAddRoute.setOnClickListener { openRouteEditor(null) }
        cardDefaultRoute.setOnClickListener { chooseDefaultRoute() }
    }

    private fun observeRoutes() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.networkRouteDao().getAll().collectLatest { routes ->
                    currentRoutes = routes
                    routeAdapter.submitList(routes)
                    emptyRoutes.visibility = if (routes.isEmpty()) View.VISIBLE else View.GONE
                    recyclerRoutes.visibility = if (routes.isEmpty()) View.GONE else View.VISIBLE
                    renderDefaultRoute()
                }
            }
        }
    }

    private fun renderDefaultRoute() {
        val defaultId = app.preferencesManager.getDefaultRouteId()
        val name = defaultId
            ?.let { id -> currentRoutes.firstOrNull { it.id == id }?.name }
        textDefaultRouteValue.text = name ?: getString(R.string.route_default_direct)
    }

    private fun chooseDefaultRoute() {
        // Index 0 is the "direct / no default" choice; the rest map to routes.
        val labels = mutableListOf(getString(R.string.route_default_direct))
        labels += currentRoutes.map { it.name }

        val currentId = app.preferencesManager.getDefaultRouteId()
        val checked = if (currentId == null) 0
        else currentRoutes.indexOfFirst { it.id == currentId }.let { if (it >= 0) it + 1 else 0 }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_default_choose_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                val newId = if (which == 0) null else currentRoutes[which - 1].id
                app.preferencesManager.setDefaultRouteId(newId)
                renderDefaultRoute()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setRouteEnabled(route: NetworkRoute, enabled: Boolean) {
        if (route.enabled == enabled) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                app.database.networkRouteDao().update(
                    route.copy(enabled = enabled, modifiedAt = System.currentTimeMillis())
                )
            }
        }
    }

    private fun confirmDeleteRoute(route: NetworkRoute) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_delete_title)
            .setMessage(getString(R.string.route_delete_message, routeName(route)))
            .setPositiveButton(R.string.delete) { _, _ -> deleteRoute(route) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteRoute(route: NetworkRoute) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                app.database.networkRouteDao().delete(route)
                // If it was the global default, clear the dangling reference.
                if (app.preferencesManager.getDefaultRouteId() == route.id) {
                    app.preferencesManager.setDefaultRouteId(null)
                }
            }
            renderDefaultRoute()
        }
    }

    private fun openRouteEditor(routeId: String?) {
        val intent = Intent(this, NetworkRouteEditActivity::class.java)
        if (routeId != null) {
            intent.putExtra(NetworkRouteEditActivity.EXTRA_ROUTE_ID, routeId)
        }
        routeEditorLauncher.launch(intent)
    }

    private fun routeName(route: NetworkRoute): String =
        route.name.ifBlank { route.getSummary() }

    // -------------------------------------------------------------------------
    // Port Forwards
    // -------------------------------------------------------------------------

    private fun setupForwardsSection() {
        recyclerView = findViewById(R.id.recycler_forwards)
        emptyState = findViewById(R.id.empty_state)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.isNestedScrollingEnabled = false

        adapter = PortForwardAdapter(
            onToggle = { toggle(it) },
            onEdit = { openEditor(it.id) },
            onDelete = { confirmDelete(it) }
        )
        recyclerView.adapter = adapter
    }

    private fun setupFab() {
        fab = findViewById(R.id.fab_add_forward)
        fab.setOnClickListener { openEditor(null) }
    }

    /**
     * Observe the saved rules and, on every emission, refresh the running-state
     * overlay and the saved-connection name map so rows render fully.
     */
    private fun observeForwards() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.portForwardDao().getAll().collectLatest { forwards ->
                    val names = withContext(Dispatchers.IO) {
                        app.database.connectionDao().getAllConnectionsList()
                            .associate { it.id to it.getDisplayName() }
                    }
                    adapter.setConnectionNames(names)
                    adapter.submitList(forwards)
                    refreshRunningState(forwards)
                    renderEmptyState(forwards.isEmpty())
                }
            }
        }
    }

    private fun refreshRunningState(forwards: List<PortForward>) {
        val running = forwards.asSequence()
            .map { it.id }
            .filter { app.portForwardCoordinator.isRunning(it) }
            .toSet()
        adapter.setRunningIds(running)
    }

    private fun renderEmptyState(isEmpty: Boolean) {
        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    /**
     * Start a stopped forward or stop a running one. All coordinator calls are
     * suspend and run off the main thread; results surface as a Snackbar.
     */
    private fun toggle(pf: PortForward) {
        lifecycleScope.launch {
            if (app.portForwardCoordinator.isRunning(pf.id)) {
                app.portForwardCoordinator.stop(pf.id)
                showMessage(getString(R.string.port_forward_stopped, displayName(pf)))
            } else {
                val result = app.portForwardCoordinator.start(pf)
                result.onSuccess {
                    showMessage(getString(R.string.port_forward_started, displayName(pf)))
                }.onFailure { error ->
                    Logger.w("PortForwardingActivity", "Start failed: ${error.message}")
                    showMessage(
                        getString(
                            R.string.port_forward_start_failed,
                            error.message ?: getString(R.string.port_forward_status_stopped)
                        )
                    )
                }
            }
            // Reflect the new running state on the toggled row.
            adapter.setRunningIds(currentRunningIds())
        }
    }

    private fun currentRunningIds(): Set<String> =
        adapter.currentList.asSequence()
            .map { it.id }
            .filter { app.portForwardCoordinator.isRunning(it) }
            .toSet()

    private fun confirmDelete(pf: PortForward) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.port_forward_delete_title)
            .setMessage(getString(R.string.port_forward_delete_message, displayName(pf)))
            .setPositiveButton(R.string.delete) { _, _ -> delete(pf) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(pf: PortForward) {
        lifecycleScope.launch {
            // Stop any live tunnel first so we don't leak the session.
            if (app.portForwardCoordinator.isRunning(pf.id)) {
                app.portForwardCoordinator.stop(pf.id)
            }
            withContext(Dispatchers.IO) {
                app.database.portForwardDao().delete(pf)
                TombstoneRecorder.record(
                    this@PortForwardingActivity, TombstoneRecorder.PORT_FORWARD, pf.id
                )
            }
        }
    }

    private fun openEditor(forwardId: String?) {
        val intent = Intent(this, PortForwardEditActivity::class.java)
        if (forwardId != null) {
            intent.putExtra(PortForwardEditActivity.EXTRA_FORWARD_ID, forwardId)
        } else if (prefillConnectionId != null) {
            intent.putExtra(PortForwardEditActivity.EXTRA_PREFILL_CONNECTION_ID, prefillConnectionId)
        }
        startActivity(intent)
    }

    private fun displayName(pf: PortForward): String =
        pf.name.ifBlank { pf.getSummary() }

    private fun showMessage(message: String) {
        Snackbar.make(recyclerView, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
    }
}
