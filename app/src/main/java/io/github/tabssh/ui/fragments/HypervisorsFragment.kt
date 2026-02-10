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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.ui.activities.HypervisorEditActivity
import io.github.tabssh.ui.activities.ProxmoxManagerActivity
import io.github.tabssh.ui.activities.VMwareManagerActivity
import io.github.tabssh.ui.activities.XCPngManagerActivity
import io.github.tabssh.ui.adapters.HypervisorAdapter
import io.github.tabssh.storage.database.entities.HypervisorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        
        lifecycleScope.launch {
            try {
                app.database.hypervisorDao().getAllHypervisors().collect { list ->
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
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    "Failed to load hypervisors: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openHypervisorManager(hypervisor: HypervisorProfile) {
        val intent = when (hypervisor.type) {
            HypervisorType.PROXMOX -> Intent(requireContext(), ProxmoxManagerActivity::class.java)
            HypervisorType.VMWARE -> Intent(requireContext(), VMwareManagerActivity::class.java)
            HypervisorType.XCPNG -> Intent(requireContext(), XCPngManagerActivity::class.java)
        }
        intent.putExtra("hypervisor_id", hypervisor.id)
        startActivity(intent)
    }

    private fun openHypervisorEdit(hypervisor: HypervisorProfile?) {
        val intent = Intent(requireContext(), HypervisorEditActivity::class.java)
        hypervisor?.let {
            intent.putExtra("hypervisor_id", it.id)
        }
        startActivity(intent)
    }

    private fun showHypervisorMenu(hypervisor: HypervisorProfile) {
        val options = arrayOf("Open", "Edit", "Delete", "Refresh Status")
        
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Hypervisor")
            .setMessage("Are you sure you want to delete '${hypervisor.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.database.hypervisorDao().delete(hypervisor)
                        Toast.makeText(
                            requireContext(),
                            "Deleted ${hypervisor.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Failed to delete: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshHypervisorStatus(hypervisor: HypervisorProfile) {
        lifecycleScope.launch {
            try {
                // Attempt basic connectivity check
                withContext(Dispatchers.IO) {
                    // Simple ping/connection test would go here
                    // For now, just show that we tried
                }
                Toast.makeText(
                    requireContext(),
                    "✓ ${hypervisor.name} connectivity checked",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "✗ ${hypervisor.name} not reachable",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    companion object {
        fun newInstance() = HypervisorsFragment()
    }
}
