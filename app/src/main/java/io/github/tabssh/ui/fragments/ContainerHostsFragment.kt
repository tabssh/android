package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.ui.activities.ContainerHostEditActivity
import io.github.tabssh.ui.activities.ContainerHostManagerActivity
import io.github.tabssh.ui.adapters.ContainerHostAdapter
import io.github.tabssh.ui.dialogs.ContainerActionSheet
import io.github.tabssh.ui.utils.ContainerText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * Container host list — first Infra sub-tab, covering every engine
 * (Docker, Incus, Podman, LXC/LXD) in one list; each row names its engine.
 * Same structure as HypervisorsFragment: DAO Flow → DiffUtil adapter, FAB /
 * empty-state add. Row overflow button and long-press open the same action
 * sheet (Open / Test connection / Edit / Delete — destructive last).
 */
class ContainerHostsFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: android.widget.TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var buttonAddFirst: Button

    private lateinit var adapter: ContainerHostAdapter

    /** Guards against overlapping forced transport re-tests from repeat taps. */
    private var testInFlight = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_container_hosts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = tabSSHApp

        setupViews(view)
        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        loadContainerHosts()
    }

    private fun setupViews(view: View) {
        toolbar = view.findViewById(R.id.toolbar)
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        recyclerView = view.findViewById(R.id.recycler_container_hosts)
        emptyState = view.findViewById(R.id.empty_state)
        errorState = view.findViewById(R.id.error_state)
        textError = view.findViewById(R.id.text_error)
        progressBar = view.findViewById(R.id.progress_bar)
        fabAdd = view.findViewById(R.id.fab_add)
        buttonAddFirst = view.findViewById(R.id.button_add_first)

        view.findViewById<Button>(R.id.button_retry).setOnClickListener { loadContainerHosts() }
    }

    private fun setupToolbar() {
        toolbar.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_container_hosts, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_refresh -> {
                        loadContainerHosts()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setupRecyclerView() {
        adapter = ContainerHostAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { host ->
            openContainerHostManager(host)
        }

        adapter.setOnItemLongClickListener { host ->
            showContainerHostMenu(host)
        }

        adapter.setOnMoreClickListener { host ->
            showContainerHostMenu(host)
        }
    }

    private fun setupClickListeners() {
        fabAdd.setOnClickListener {
            openContainerHostEdit(null)
        }

        buttonAddFirst.setOnClickListener {
            openContainerHostEdit(null)
        }

        // Pull-to-refresh mirrors the toolbar refresh action; the centered
        // progress indicator is the loading feedback, so stop the spinner.
        swipeRefresh.setColorSchemeColors(
            MaterialColors.getColor(swipeRefresh, com.google.android.material.R.attr.colorPrimary)
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            MaterialColors.getColor(swipeRefresh, com.google.android.material.R.attr.colorSurface)
        )
        swipeRefresh.setOnRefreshListener {
            swipeRefresh.isRefreshing = false
            loadContainerHosts()
        }
    }

    private fun loadContainerHosts() {
        progressBar.visibility = View.VISIBLE

        // viewLifecycleOwner: the collect below touches view fields, so the
        // Flow must die with the view tree (HypervisorsFragment pattern).
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Connection names resolve linked_connection_id → display name.
                val names = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getAllConnectionsList()
                        .associate { it.id to it.name }
                }
                app.database.containerHostDao().getAllHosts().collect { list ->
                    if (!isAdded) return@collect

                    adapter.updateList(list, names)

                    // Toggle the swipe container, not the recycler — the
                    // wrapper keeps its layout weight even with a GONE child.
                    errorState.visibility = View.GONE
                    if (list.isEmpty()) {
                        swipeRefresh.visibility = View.GONE
                        emptyState.visibility = View.VISIBLE
                    } else {
                        swipeRefresh.visibility = View.VISIBLE
                        emptyState.visibility = View.GONE
                    }

                    progressBar.visibility = View.GONE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isAdded) return@launch

                progressBar.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    getString(R.string.container_error_detail_fmt, ContainerText.display(e.message)),
                    Toast.LENGTH_SHORT
                ).show()

                // Distinct from the empty state — a failed load is not the
                // same thing as "no container hosts configured".
                swipeRefresh.visibility = View.GONE
                emptyState.visibility = View.GONE
                errorState.visibility = View.VISIBLE
                textError.text = getString(
                    R.string.container_error_detail_fmt, ContainerText.display(e.message)
                )
            }
        }
    }

    private fun openContainerHostManager(host: ContainerHost) {
        if (!isAdded) return

        val intent = Intent(requireContext(), ContainerHostManagerActivity::class.java)
        intent.putExtra(ContainerHostManagerActivity.EXTRA_HOST_ID, host.id)
        startActivity(intent)
    }

    private fun openContainerHostEdit(host: ContainerHost?) {
        if (!isAdded) return

        val intent = Intent(requireContext(), ContainerHostEditActivity::class.java)
        host?.let {
            intent.putExtra(ContainerHostEditActivity.EXTRA_HOST_ID, it.id)
        }
        startActivity(intent)
    }

    private fun showContainerHostMenu(host: ContainerHost) {
        if (!isAdded) return

        ContainerActionSheet.show(
            requireContext(),
            host.name,
            null,
            listOf(
                ContainerActionSheet.Action(R.drawable.ic_forward, getString(R.string.container_option_open)) {
                    openContainerHostManager(host)
                },
                ContainerActionSheet.Action(R.drawable.ic_connection, getString(R.string.container_option_retest)) {
                    retestTransport(host)
                },
                ContainerActionSheet.Action(R.drawable.ic_edit, getString(R.string.edit)) {
                    openContainerHostEdit(host)
                },
                ContainerActionSheet.Action(R.drawable.ic_clear, getString(R.string.delete), destructive = true) {
                    deleteContainerHost(host)
                }
            )
        )
    }

    private fun deleteContainerHost(host: ContainerHost) {
        if (!isAdded) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.container_delete_host_title)
            .setMessage(getString(R.string.container_delete_host_message, host.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                // Captured at click time — the IO block below must not call
                // requireContext() after a potential detach.
                val appContext = requireContext().applicationContext
                // viewLifecycleOwner: scope ends at onDestroyView so the toast
                // below cannot fire against a dead view tree.
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            // Convention cascade — the docker_* child tables have
                            // no real FKs, so clean them up alongside the host.
                            ContainerSessionManager.release(app, host.id)
                            // Custom-endpoint password lives only in the
                            // Keystore — clear it so a future row id reuse
                            // can't inherit this host's secret.
                            io.github.tabssh.crypto.storage.ContainerHostPasswordStore
                                .clear(appContext, host.id)
                            // Tombstone every cascaded child before the bulk
                            // delete removes it — deleteForHost() gives back
                            // no rows, so the natural keys must be captured first.
                            app.database.composeStackDao().getStacksForHostList(host.id).forEach { stack ->
                                io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                                    appContext, io.github.tabssh.sync.tombstone.TombstoneRecorder.COMPOSE_STACK,
                                    io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(stack))
                            }
                            app.database.singleContainerConfigDao().getConfigsForHostList(host.id).forEach { config ->
                                io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                                    appContext, io.github.tabssh.sync.tombstone.TombstoneRecorder.SINGLE_CONTAINER_CONFIG,
                                    io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(config))
                            }
                            app.database.containerAutoUpdatePolicyDao().getPoliciesForHost(host.id).first().forEach { policy ->
                                io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                                    appContext, io.github.tabssh.sync.tombstone.TombstoneRecorder.CONTAINER_AUTO_UPDATE_POLICY,
                                    io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(policy))
                            }
                            app.database.composeStackDao().deleteForHost(host.id)
                            app.database.singleContainerConfigDao().deleteForHost(host.id)
                            app.database.containerAutoUpdatePolicyDao().deleteForHost(host.id)
                            app.database.containerHostDao().delete(host)
                            // Cascade: drop the connectable-hosts registry row and strip the id from saved pane groups
                            io.github.tabssh.storage.registry.ConnectableHostRegistry.removeContainerHost(app.database, host.id)
                            io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                                appContext, io.github.tabssh.sync.tombstone.TombstoneRecorder.CONTAINER_HOST,
                                io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(host))
                        }
                        if (!isAdded) return@launch
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.container_host_deleted),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!isAdded) return@launch
                        // A Keystore/cipher failure message can echo the value
                        // it was handed — sanitize and cap before display.
                        Toast.makeText(
                            requireContext(),
                            getString(
                                R.string.container_error_detail_fmt, ContainerText.display(e.message)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun retestTransport(host: ContainerHost) {
        if (!isAdded) return
        // acquire(force = true) tears down and redials the session: repeated
        // taps would stack full SSH handshakes against the same host and race
        // each other's teardown.
        if (testInFlight) return
        testInFlight = true

        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = ContainerSessionManager.acquire(app, host.id, force = true)
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                when (result) {
                    is io.github.tabssh.containers.transport.ContainerResult.Success ->
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.container_transport_ok, result.value.mode),
                            Toast.LENGTH_SHORT
                        ).show()
                    else -> io.github.tabssh.ui.dialogs.ContainerErrorPresenter
                        .present(requireContext(), result)
                }
            } finally {
                testInFlight = false
            }
        }
    }

    companion object {
        fun newInstance() = ContainerHostsFragment()
    }
}
