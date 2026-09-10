package io.github.tabssh.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
    private lateinit var listView: ListView
    private var credentials: List<RegistryCredential> = emptyList()

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
                        credentials = list
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
        listView.adapter = SimpleAdapter(
            activity, rows, android.R.layout.simple_list_item_2,
            arrayOf("host", "detail"),
            intArrayOf(android.R.id.text1, android.R.id.text2)
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            RegistryCredentialDialog.showItemMenu(activity, app, credentials[position])
        }
    }

    companion object {
        private const val TAG = "AuthRegistriesFragment"
        fun newInstance() = AuthRegistriesFragment()
    }
}
