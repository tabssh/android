package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.PortForward
import io.github.tabssh.sync.tombstone.TombstoneRecorder
import io.github.tabssh.ui.adapters.PortForwardAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone manager for saved SSH port-forward rules.
 *
 * Rules live in the database (see [PortForward]) and are independent of any
 * live terminal session — the [io.github.tabssh.ssh.forwarding.PortForwardCoordinator]
 * opens (or reuses) an SSH connection when a rule is started. The screen
 * observes the rule list as a Flow and exposes add / edit / delete plus a
 * per-row start/stop toggle.
 */
class PortForwardingActivity : AppCompatActivity() {

    private val app: TabSSHApplication
        get() = application as TabSSHApplication

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var adapter: PortForwardAdapter

    // Optional: when launched from a terminal tab, a new forward defaults to
    // that saved connection.
    private var prefillConnectionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_port_forwarding)

        prefillConnectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)

        setupToolbar()
        setupRecyclerView()
        setupFab()
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

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recycler_forwards)
        emptyState = findViewById(R.id.empty_state)
        recyclerView.layoutManager = LinearLayoutManager(this)

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
