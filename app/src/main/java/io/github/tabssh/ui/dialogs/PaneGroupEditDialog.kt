package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectableHost
import io.github.tabssh.storage.database.entities.PaneGroup
import io.github.tabssh.storage.registry.ConnectableHostRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Create/edit dialog for a saved Panes group — a name plus an ordered pick
 * of up to 6 terminal-capable (SSH/Telnet/Mosh) connections.
 */
object PaneGroupEditDialog {

    private const val MAX_MEMBERS = 6

    fun show(
        context: Context,
        app: TabSSHApplication,
        scope: CoroutineScope,
        existing: PaneGroup?,
        onSaved: () -> Unit
    ) {
        scope.launch {
            // Re-fetch live before showing the picker so Cloud Account instances
            // are current (never trust a stale cache for the picker or launch path).
            withContext(Dispatchers.IO) {
                ConnectableHostRegistry.refreshAll(app.database, app)
            }
            val hosts = withContext(Dispatchers.IO) {
                app.database.connectableHostDao().getAllList()
            }

            val dialogView = LayoutInflater.from(context)
                .inflate(R.layout.dialog_edit_pane_group, null)
            val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_pane_group_name)
            val membersRecycler =
                dialogView.findViewById<RecyclerView>(R.id.recycler_pane_group_members)

            nameInput.setText(existing?.name ?: "")

            val selected = mutableListOf<ConnectableHost>()
            if (existing != null) {
                for (id in existing.memberHostIds) {
                    hosts.find { it.id == id }?.let { selected.add(it) }
                }
            }

            val memberAdapter = PaneMemberSelectionAdapter(hosts, selected) {
                Toast.makeText(context, R.string.pane_group_max_members_toast, Toast.LENGTH_SHORT).show()
            }
            membersRecycler.layoutManager = LinearLayoutManager(context)
            membersRecycler.adapter = memberAdapter

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(
                    if (existing == null) R.string.pane_group_add_title else R.string.pane_group_edit_title
                )
                .setView(dialogView)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
            dialog.show()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(context, R.string.xcpng_name_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (selected.isEmpty()) {
                    Toast.makeText(context, R.string.pane_group_min_members_toast, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                val now = System.currentTimeMillis()
                val paneGroup = PaneGroup(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    layout = existing?.layout ?: "",
                    memberHostIds = selected.map { it.id },
                    sortOrder = existing?.sortOrder ?: 0,
                    createdAt = existing?.createdAt ?: now,
                    modifiedAt = now
                )
                scope.launch {
                    withContext(Dispatchers.IO) {
                        app.database.paneGroupDao().insert(paneGroup)
                    }
                    onSaved()
                }
            }
        }
    }

    private class PaneMemberSelectionAdapter(
        private val hosts: List<ConnectableHost>,
        private val selected: MutableList<ConnectableHost>,
        private val onMaxExceeded: () -> Unit
    ) : RecyclerView.Adapter<PaneMemberSelectionAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.connection_checkbox)
            val name: TextView = view.findViewById(R.id.connection_name)
            val host: TextView = view.findViewById(R.id.connection_host)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cluster_connection, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = hosts.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val connectableHost = hosts[position]
            val order = selected.indexOf(connectableHost)
            holder.name.text = if (order >= 0) {
                "${order + 1}. ${connectableHost.name}"
            } else {
                connectableHost.name
            }
            holder.host.text = connectableHost.hostPreview

            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = order >= 0
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (selected.size >= MAX_MEMBERS) {
                        holder.checkbox.isChecked = false
                        onMaxExceeded()
                        return@setOnCheckedChangeListener
                    }
                    selected.add(connectableHost)
                } else {
                    selected.remove(connectableHost)
                }
                notifyDataSetChanged()
            }

            holder.itemView.setOnClickListener {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
            }
        }
    }
}
