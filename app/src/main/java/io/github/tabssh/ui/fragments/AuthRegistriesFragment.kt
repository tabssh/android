package io.github.tabssh.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.RegistryCredential
import io.github.tabssh.ui.dialogs.RegistryCredentialDialog
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.tabSSHApp
import kotlinx.coroutines.launch

/**
 * Auth tab — Registries sub-tab: private-registry credentials used by the
 * container auto-update checker. Reuses [RegistryCredentialDialog]'s
 * editor/menu/delete flows so the add/edit/delete logic stays in one place;
 * this fragment only owns the embedded list and its empty state.
 */
class AuthRegistriesFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var listView: RecyclerView
    private lateinit var adapter: RegistryCredentialAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_auth_registries, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = tabSSHApp

        setupRegistriesSection(view)
        observeData()
    }

    private fun setupRegistriesSection(view: View) {
        listView = view.findViewById(R.id.list_registries)
        adapter = RegistryCredentialAdapter { credential ->
            RegistryCredentialDialog.showItemMenu(requireActivity() as AppCompatActivity, app, credential)
        }
        listView.layoutManager = LinearLayoutManager(requireContext())
        listView.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btn_add_registry).setOnClickListener {
            RegistryCredentialDialog.showEditor(requireActivity() as AppCompatActivity, app, null)
        }
        view.findViewById<MaterialButton>(R.id.button_registries_empty_cta).setOnClickListener {
            RegistryCredentialDialog.showEditor(requireActivity() as AppCompatActivity, app, null)
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.database.registryCredentialDao().getAllCredentials().collect { list ->
                        renderList(list)
                        Logger.d(TAG, "Loaded ${list.size} registry credentials")
                    }
                }
            }
        }
    }

    private fun renderList(list: List<RegistryCredential>) {
        val view = view ?: return
        val empty = view.findViewById<TextView>(R.id.text_registries_empty)
        val emptyCta = view.findViewById<MaterialButton>(R.id.button_registries_empty_cta)
        val activity = requireActivity() as AppCompatActivity

        if (list.isEmpty()) {
            listView.visibility = View.GONE
            empty.visibility = View.VISIBLE
            emptyCta.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        emptyCta.visibility = View.GONE
        listView.visibility = View.VISIBLE

        val rows = list.map { credential ->
            mapOf(
                "host" to credential.registryHost,
                "detail" to RegistryCredentialDialog.listItemDetail(activity, credential)
            )
        }
        adapter.submitList(list)
    }

    private inner class RegistryCredentialAdapter(
        private val onClick: (RegistryCredential) -> Unit
    ) : ListAdapter<RegistryCredential, RegistryCredentialAdapter.ViewHolder>(CredentialDiff) {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.text_title)
            val detail: TextView = view.findViewById(R.id.text_subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val row = LayoutInflater.from(parent.context).inflate(R.layout.item_list_card, parent, false)
            return ViewHolder(row)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val credential = getItem(position)
            holder.title.text = credential.registryHost
            holder.detail.text = RegistryCredentialDialog.listItemDetail(
                requireActivity() as AppCompatActivity,
                credential
            )
            holder.itemView.setOnClickListener { onClick(credential) }
        }
    }

    private object CredentialDiff : DiffUtil.ItemCallback<RegistryCredential>() {
        override fun areItemsTheSame(old: RegistryCredential, new: RegistryCredential): Boolean = old.id == new.id
        override fun areContentsTheSame(old: RegistryCredential, new: RegistryCredential): Boolean = old == new
    }

    companion object {
        private const val TAG = "AuthRegistriesFragment"
        fun newInstance() = AuthRegistriesFragment()
    }
}
