package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectableHost
import io.github.tabssh.storage.database.entities.PaneGroup
import io.github.tabssh.storage.database.entities.PaneWindowConfig
import io.github.tabssh.storage.registry.ConnectableHostRegistry
import io.github.tabssh.ui.views.PanesSplitDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Create/edit dialog for a saved Panes group — two steps:
 * 1. group name + window count (2-6).
 * 2. one editor row per window — host picker (NOT exclusive; the same host
 *    may be chosen for more than one window), optional working directory,
 *    optional custom title. Row order is preserved as grid fill order.
 *
 * "Window" here is the per-slot tiling-window-manager term (see
 * `PaneWindow`/`PaneWindowConfig`) — the group/feature itself stays "Panes"
 * everywhere else (dialog titles, DB table, sync/backup keys).
 */
object PaneGroupEditDialog {

    private const val MIN_WINDOWS = 2
    private const val MAX_WINDOWS = 6

    fun show(
        context: Context,
        app: TabSSHApplication,
        scope: CoroutineScope,
        existing: PaneGroup?,
        onSaved: () -> Unit
    ) {
        scope.launch {
            // Show the picker from whatever's already cached — refreshAll()
            // hits live cloud-provider APIs and was previously awaited here,
            // adding a ~2s stall to every tap on "+". Only block on a live
            // refresh when the cache is empty (first-ever use, nothing to
            // show yet); otherwise refresh in the background so the next
            // open picks up fresh data without holding up this one.
            var hosts = withContext(Dispatchers.IO) {
                app.database.connectableHostDao().getAllList()
            }
            if (hosts.isEmpty()) {
                withContext(Dispatchers.IO) {
                    ConnectableHostRegistry.refreshAll(app.database, app)
                }
                hosts = withContext(Dispatchers.IO) {
                    app.database.connectableHostDao().getAllList()
                }
            } else {
                scope.launch(Dispatchers.IO) {
                    ConnectableHostRegistry.refreshAll(app.database, app)
                }
            }
            if (hosts.isEmpty()) {
                Toast.makeText(context, R.string.terminal_connection_not_found, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val existingWindows = existing?.resolvedWindows().orEmpty()
            showStep1(context, app, scope, existing, hosts, existingWindows, onSaved)
        }
    }

    private fun showStep1(
        context: Context,
        app: TabSSHApplication,
        scope: CoroutineScope,
        existing: PaneGroup?,
        hosts: List<ConnectableHost>,
        existingWindows: List<PaneWindowConfig>,
        onSaved: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_edit_pane_group, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_pane_group_name)
        val countSpinner = dialogView.findViewById<Spinner>(R.id.spinner_pane_group_window_count)

        nameInput.setText(existing?.name ?: "")

        val counts = (MIN_WINDOWS..MAX_WINDOWS).toList()
        countSpinner.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item, counts
        )
        val initialCount = existingWindows.size.coerceIn(MIN_WINDOWS, MAX_WINDOWS)
        countSpinner.setSelection(counts.indexOf(initialCount).coerceAtLeast(0))

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(
                if (existing == null) R.string.pane_group_add_title else R.string.pane_group_edit_title
            )
            .setView(dialogView)
            .setPositiveButton(R.string.next, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(context, R.string.xcpng_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val windowCount = counts[countSpinner.selectedItemPosition]
            // Bug fix: dismissing this dialog and immediately (synchronously)
            // showing the Step 2 dialog raced the first dialog's window
            // teardown against the second dialog's IME focus request — the
            // Step 2 EditTexts visually gained focus (blinking cursor) but
            // the soft keyboard never actually appeared. Deferring the
            // Step 2 show to this dialog's dismiss callback lets its window
            // finish tearing down first.
            dialog.setOnDismissListener {
                showStep2(context, app, scope, existing, hosts, existingWindows, name, windowCount, onSaved)
            }
            dialog.dismiss()
        }
    }

    private fun showStep2(
        context: Context,
        app: TabSSHApplication,
        scope: CoroutineScope,
        existing: PaneGroup?,
        hosts: List<ConnectableHost>,
        existingWindows: List<PaneWindowConfig>,
        name: String,
        windowCount: Int,
        onSaved: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_edit_pane_group_step2, null)
        val windowsRecycler =
            dialogView.findViewById<RecyclerView>(R.id.recycler_pane_group_windows)
        val splitDirectionSection =
            dialogView.findViewById<View>(R.id.group_pane_split_direction)
        val splitDirectionRadioGroup =
            dialogView.findViewById<android.widget.RadioGroup>(R.id.radio_pane_split_direction)
        val radioVertical =
            dialogView.findViewById<android.widget.RadioButton>(R.id.radio_pane_split_vertical)

        // Split direction only makes sense for exactly 2 windows — see
        // PanesSplitDirection in PanesGridView.kt.
        if (windowCount == 2) {
            splitDirectionSection.visibility = View.VISIBLE
            if ((existing?.splitDirection ?: PanesSplitDirection.HORIZONTAL) == PanesSplitDirection.VERTICAL) {
                radioVertical.isChecked = true
            }
        }

        // Seed each slot from the existing window at that index, if any —
        // preserves prior host/workingDir/customTitle when just editing the
        // count or another slot.
        val slots = (0 until windowCount).map { index ->
            existingWindows.getOrNull(index) ?: PaneWindowConfig(hostId = hosts.first().id)
        }.toMutableList()

        val slotAdapter = PaneWindowSlotAdapter(hosts, slots)
        windowsRecycler.layoutManager = LinearLayoutManager(context)
        windowsRecycler.adapter = slotAdapter

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.pane_group_step2_title_fmt, name))
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (slots.any { it.hostId.isBlank() }) {
                Toast.makeText(context, R.string.pane_group_window_host_required_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val splitDirection = if (windowCount == 2 && splitDirectionRadioGroup.checkedRadioButtonId == R.id.radio_pane_split_vertical) {
                PanesSplitDirection.VERTICAL
            } else {
                PanesSplitDirection.HORIZONTAL
            }
            dialog.dismiss()
            val now = System.currentTimeMillis()
            val paneGroup = PaneGroup(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name,
                layout = existing?.layout ?: "",
                memberHostIds = slots.map { it.hostId },
                windows = slots.toList(),
                splitDirection = splitDirection,
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

    /**
     * One editable row per window slot. Host selection is intentionally
     * NOT exclusive — the same [ConnectableHost] may be picked for more
     * than one slot (e.g. one host opened twice with different working
     * directories), so this adapter never removes a host from other rows'
     * choices.
     */
    private class PaneWindowSlotAdapter(
        private val hosts: List<ConnectableHost>,
        private val slots: MutableList<PaneWindowConfig>
    ) : RecyclerView.Adapter<PaneWindowSlotAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.text_pane_window_slot_label)
            val hostSpinner: Spinner = view.findViewById(R.id.spinner_pane_window_host)
            val workingDir: TextInputEditText = view.findViewById(R.id.edit_pane_window_working_dir)
            val customTitle: TextInputEditText = view.findViewById(R.id.edit_pane_window_custom_title)
            var workingDirWatcher: android.text.TextWatcher? = null
            var customTitleWatcher: android.text.TextWatcher? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pane_window_slot, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = slots.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val slot = slots[position]
            holder.label.text = holder.itemView.context.getString(
                R.string.pane_group_window_n_fmt, position + 1
            )

            holder.hostSpinner.adapter = ArrayAdapter(
                holder.itemView.context,
                android.R.layout.simple_spinner_dropdown_item,
                hosts.map { it.name }
            )
            val hostIndex = hosts.indexOfFirst { it.id == slot.hostId }.coerceAtLeast(0)
            holder.hostSpinner.setSelection(hostIndex)
            holder.hostSpinner.onItemSelectedListener = null
            holder.hostSpinner.setOnItemSelectedListener(
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long
                    ) {
                        val row = holder.bindingAdapterPosition
                        if (row == RecyclerView.NO_POSITION) return
                        slots[row] = slots[row].copy(hostId = hosts[pos].id)
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            )

            holder.workingDir.removeTextChangedListener(holder.workingDirWatcher)
            holder.workingDir.setText(slot.workingDir ?: "")
            holder.workingDirWatcher = holder.workingDir.doAfterTextChanged { text ->
                val row = holder.bindingAdapterPosition
                if (row == RecyclerView.NO_POSITION) return@doAfterTextChanged
                slots[row] = slots[row].copy(workingDir = text?.toString()?.trim().takeUnless { it.isNullOrBlank() })
            }

            holder.customTitle.removeTextChangedListener(holder.customTitleWatcher)
            holder.customTitle.setText(slot.customTitle ?: "")
            holder.customTitleWatcher = holder.customTitle.doAfterTextChanged { text ->
                val row = holder.bindingAdapterPosition
                if (row == RecyclerView.NO_POSITION) return@doAfterTextChanged
                slots[row] = slots[row].copy(customTitle = text?.toString()?.trim().takeUnless { it.isNullOrBlank() })
            }
        }
    }
}
