package io.github.tabssh.ui.fragments

import io.github.tabssh.sync.tombstone.TombstoneRecorder
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.ui.activities.HypervisorEditActivity
import io.github.tabssh.ui.activities.LibvirtManagerActivity
import io.github.tabssh.ui.activities.ProxmoxManagerActivity
import io.github.tabssh.ui.activities.VMwareManagerActivity
import io.github.tabssh.ui.activities.XCPngManagerActivity
import io.github.tabssh.ui.adapters.HypervisorAdapter
import io.github.tabssh.storage.database.entities.HypervisorType
import io.github.tabssh.ui.utils.ContainerText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reachability probe budget for a single hypervisor endpoint. */
private const val PROBE_TIMEOUT_MS = 5_000

/**
 * Fragment for Hypervisor Management (Phase 3)
 * Manages Proxmox, VMware, and XCP-ng connections
 */
class HypervisorsFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var buttonAddFirst: Button
    
    private lateinit var adapter: HypervisorAdapter
    private val hypervisors = mutableListOf<HypervisorProfile>()
    private var probeInFlight = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_hypervisors, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        app = requireActivity().application as TabSSHApplication
        
        setupViews(view)
        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        loadHypervisors()
    }

    private fun setupViews(view: View) {
        toolbar = view.findViewById(R.id.toolbar)
        recyclerView = view.findViewById(R.id.recycler_hypervisors)
        emptyState = view.findViewById(R.id.empty_state)
        progressBar = view.findViewById(R.id.progress_bar)
        fabAdd = view.findViewById(R.id.fab_add)
        buttonAddFirst = view.findViewById(R.id.button_add_first)
    }

    private fun setupToolbar() {
        toolbar.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_hypervisors, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_refresh -> {
                        loadHypervisors()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setupRecyclerView() {
        adapter = HypervisorAdapter(hypervisors)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        
        adapter.setOnItemClickListener { hypervisor ->
            openHypervisorManager(hypervisor)
        }
        
        adapter.setOnItemLongClickListener { hypervisor ->
            showHypervisorMenu(hypervisor)
        }
    }

    private fun setupClickListeners() {
        fabAdd.setOnClickListener {
            openHypervisorEdit(null)
        }
        
        buttonAddFirst.setOnClickListener {
            openHypervisorEdit(null)
        }
    }

    private fun loadHypervisors() {
        progressBar.visibility = View.VISIBLE

        // viewLifecycleOwner: collect touches recyclerView/emptyState/progressBar
        // (view fields). Using lifecycleScope kept the Flow live after
        // onDestroyView, NPE-ing the next emission.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                app.database.hypervisorDao().getAllHypervisors().collect { list ->
                    // Check if fragment is still attached before updating UI
                    if (!isAdded) return@collect

                    hypervisors.clear()
                    hypervisors.addAll(list)
                    adapter.updateList(hypervisors)

                    // Update UI visibility
                    if (hypervisors.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        emptyState.visibility = View.VISIBLE
                    } else {
                        recyclerView.visibility = View.VISIBLE
                        emptyState.visibility = View.GONE
                    }

                    progressBar.visibility = View.GONE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Check if fragment is still attached before showing toast
                if (!isAdded) return@launch

                progressBar.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.hypervisor_load_failed_fmt, ContainerText.display(e.message)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openHypervisorManager(hypervisor: HypervisorProfile) {
        if (!isAdded) return

        if (hypervisor.type == HypervisorType.OCI) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.hypervisor_oci_moved_title)
                .setMessage(R.string.hypervisor_oci_moved_message)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val intent = when (hypervisor.type) {
            HypervisorType.PROXMOX -> Intent(requireContext(), ProxmoxManagerActivity::class.java)
            HypervisorType.VMWARE  -> Intent(requireContext(), VMwareManagerActivity::class.java)
            HypervisorType.XCPNG   -> Intent(requireContext(), XCPngManagerActivity::class.java)
            HypervisorType.LIBVIRT -> Intent(requireContext(), LibvirtManagerActivity::class.java)
        }
        intent.putExtra("hypervisor_id", hypervisor.id)
        startActivity(intent)
    }

    private fun openHypervisorEdit(hypervisor: HypervisorProfile?) {
        if (!isAdded) return

        val intent = Intent(requireContext(), HypervisorEditActivity::class.java)
        hypervisor?.let {
            intent.putExtra("hypervisor_id", it.id)
        }
        startActivity(intent)
    }

    private fun showHypervisorMenu(hypervisor: HypervisorProfile) {
        if (!isAdded) return

        val options = arrayOf(
            getString(R.string.hypervisor_menu_open),
            getString(R.string.edit),
            getString(R.string.delete),
            getString(R.string.hypervisor_menu_refresh_status)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(hypervisor.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openHypervisorManager(hypervisor)
                    1 -> editHypervisor(hypervisor)
                    2 -> deleteHypervisor(hypervisor)
                    3 -> refreshHypervisorStatus(hypervisor)
                }
            }
            .show()
    }

    private fun editHypervisor(hypervisor: HypervisorProfile) {
        openHypervisorEdit(hypervisor)
    }

    private fun deleteHypervisor(hypervisor: HypervisorProfile) {
        if (!isAdded) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hypervisor_delete_title)
            .setMessage(getString(R.string.hypervisor_delete_message, hypervisor.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                // viewLifecycleOwner: scope must end at onDestroyView so the
                // toast/UI updates below can't fire against a dead view tree.
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val ctx = context ?: return@launch
                        withContext(Dispatchers.IO) {
                            app.database.hypervisorDao().delete(hypervisor)
                            // H6 — Long PK is device-local; tombstone by natural key.
                            TombstoneRecorder.record(app, TombstoneRecorder.HYPERVISOR, TombstoneRecorder.naturalKey(hypervisor))
                            // P1: also drop the Keystore-backed password so the
                            // alias doesn't dangle if the row id ever gets reused.
                            // clear() does Keystore operations — must be on IO.
                            // ctx captured before IO switch — requireContext() is unsafe on IO thread.
                            io.github.tabssh.crypto.storage.HypervisorPasswordStore
                                .clear(ctx, hypervisor.id)
                        }
                        if (!isAdded) return@launch
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.hypervisor_deleted_fmt, hypervisor.name),
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
                                R.string.hypervisor_delete_failed_fmt,
                                ContainerText.display(e.message)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshHypervisorStatus(hypervisor: HypervisorProfile) {
        if (!isAdded) return
        // The probe holds a socket for up to five seconds; repeat taps would
        // stack one connect attempt per tap against the same host.
        if (probeInFlight) return
        probeInFlight = true
        // Capture ctx on the main thread before the IO switch — requireContext()
        // is unsafe to call from a background dispatcher once the fragment has
        // been detached, and the LibvirtApiClient ctor takes a Context.
        val ctx = requireContext()
        // viewLifecycleOwner: scope ends at onDestroyView so the toast below
        // cannot fire against a dead view tree.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val reachable = probeReachable(ctx, hypervisor)
                if (!isAdded) return@launch
                val msg = if (reachable) {
                    getString(R.string.hypervisor_reachable_fmt, hypervisor.name)
                } else {
                    getString(R.string.hypervisor_unreachable_fmt, hypervisor.name)
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            } finally {
                probeInFlight = false
            }
        }
    }

    /** Single connect attempt against [hypervisor]; false on any reachability failure. */
    private suspend fun probeReachable(
        ctx: android.content.Context,
        hypervisor: HypervisorProfile
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (hypervisor.type == HypervisorType.LIBVIRT) {
                // SSH-based — attempt connect/disconnect using LibvirtApiClient
                val client = io.github.tabssh.hypervisor.libvirt.LibvirtApiClient(
                    ctx, hypervisor
                )
                client.connect()
                client.disconnect()
            } else {
                // REST/WebSocket hypervisors — TCP reachability probe on the API port.
                // Wrap in try/finally so the fd is released even if connect() throws
                // (timeout / unreachable / refused) — otherwise the Socket leaks until
                // the GC finalizer runs.
                val socket = java.net.Socket()
                try {
                    socket.connect(
                        java.net.InetSocketAddress(hypervisor.host, hypervisor.port),
                        PROBE_TIMEOUT_MS
                    )
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
            true
        } catch (e: CancellationException) {
            // Never report a cancelled scope as "not reachable".
            throw e
        } catch (e: Exception) {
            io.github.tabssh.utils.logging.Logger.d(
                "HypervisorsFragment",
                "Connectivity check failed for ${hypervisor.name}: ${ContainerText.display(e.message)}"
            )
            false
        }
    }

    companion object {
        fun newInstance() = HypervisorsFragment()
    }
}
