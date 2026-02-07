package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Activity for managing connection groups/folders
 * Allows creating, editing, deleting, and reordering groups
 */
class GroupManagementActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateLayout: View
    private lateinit var fab: FloatingActionButton
    private lateinit var adapter: GroupAdapter
    private val groups = mutableListOf<ConnectionGroup>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_management)

        app = application as TabSSHApplication

        setupToolbar()
        setupViews()
        loadGroups()
    }

    private fun setupToolbar() {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Manage Groups"
        }
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.recycler_view_groups)
        emptyStateLayout = findViewById(R.id.layout_empty_state)
        fab = findViewById(R.id.fab_add_group)

        // Setup RecyclerView
        adapter = GroupAdapter(
            groups = groups,
            onGroupClick = { group -> editGroup(group) },
            onGroupDelete = { group -> deleteGroup(group) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Setup drag-to-reorder
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition
                
                // Swap items
                val temp = groups[fromPosition]
                groups[fromPosition] = groups[toPosition]
                groups[toPosition] = temp
                
                adapter.notifyItemMoved(fromPosition, toPosition)
                
                // Update sort orders
                updateSortOrders()
                
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)

        // Setup FAB
        fab.setOnClickListener {
            showCreateGroupDialog()
        }

        // Setup empty state button
        findViewById<View>(R.id.button_create_group).setOnClickListener {
            showCreateGroupDialog()
        }
    }

    private fun loadGroups() {
        lifecycleScope.launch {
            try {
                app.database.connectionGroupDao().getAllGroups().collect { groupList ->
                    groups.clear()
                    groups.addAll(groupList)
                    
                    runOnUiThread {
                        adapter.notifyDataSetChanged()
                        updateEmptyState()
                    }
                    
                    Logger.d("GroupManagementActivity", "Loaded ${groupList.size} groups")
                }
            } catch (e: Exception) {
                Logger.e("GroupManagementActivity", "Failed to load groups", e)
                runOnUiThread {
                    Toast.makeText(this@GroupManagementActivity, "Failed to load groups", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateEmptyState() {
        if (groups.isEmpty()) {
            emptyStateLayout.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyStateLayout.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun showCreateGroupDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_group, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_group_name)
        val colorInput = dialogView.findViewById<TextInputEditText>(R.id.edit_group_color)
        val iconInput = dialogView.findViewById<TextInputEditText>(R.id.edit_group_icon)

        AlertDialog.Builder(this)
            .setTitle("Create Group")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Group name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val color = colorInput.text.toString().trim().ifBlank { null }
                val icon = iconInput.text.toString().trim().ifBlank { null }

                createGroup(name, color, icon)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editGroup(group: ConnectionGroup) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_group, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.edit_group_name)
        val colorInput = dialogView.findViewById<TextInputEditText>(R.id.edit_group_color)
        val iconInput = dialogView.findViewById<TextInputEditText>(R.id.edit_group_icon)

        // Pre-fill with existing values
        nameInput.setText(group.name)
        colorInput.setText(group.color ?: "")
        iconInput.setText(group.icon ?: "")

        AlertDialog.Builder(this)
            .setTitle("Edit Group")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Group name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val color = colorInput.text.toString().trim().ifBlank { null }
                val icon = iconInput.text.toString().trim().ifBlank { null }

                updateGroup(group, name, color, icon)
            }
            .setNeutralButton("Delete") { _, _ ->
                deleteGroup(group)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createGroup(name: String, color: String?, icon: String?) {
        lifecycleScope.launch {
            try {
                val group = ConnectionGroup(
                    name = name,
                    color = color,
                    icon = icon,
                    sortOrder = groups.size,
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis()
                )

                app.database.connectionGroupDao().insertGroup(group)

                runOnUiThread {
                    Toast.makeText(this@GroupManagementActivity, "Group created", Toast.LENGTH_SHORT).show()
                }

                Logger.i("GroupManagementActivity", "Created group: $name")
            } catch (e: Exception) {
                Logger.e("GroupManagementActivity", "Failed to create group", e)
                runOnUiThread {
                    Toast.makeText(this@GroupManagementActivity, "Failed to create group", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateGroup(group: ConnectionGroup, name: String, color: String?, icon: String?) {
        lifecycleScope.launch {
            try {
                val updatedGroup = group.copy(
                    name = name,
                    color = color,
                    icon = icon,
                    modifiedAt = System.currentTimeMillis()
                )

                app.database.connectionGroupDao().updateGroup(updatedGroup)

                runOnUiThread {
                    Toast.makeText(this@GroupManagementActivity, "Group updated", Toast.LENGTH_SHORT).show()
                }

                Logger.i("GroupManagementActivity", "Updated group: $name")
            } catch (e: Exception) {
                Logger.e("GroupManagementActivity", "Failed to update group", e)
                runOnUiThread {
                    Toast.makeText(this@GroupManagementActivity, "Failed to update group", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteGroup(group: ConnectionGroup) {
        lifecycleScope.launch {
            try {
                // Check if group has connections
                val connectionCount = app.database.connectionGroupDao().getConnectionCountInGroup(group.id)
                
                if (connectionCount > 0) {
                    runOnUiThread {
                        AlertDialog.Builder(this@GroupManagementActivity)
                            .setTitle("Delete Group?")
                            .setMessage("This group contains $connectionCount connection(s). Connections will be moved to 'Ungrouped'.")
                            .setPositiveButton("Delete") { _, _ ->
                                performDelete(group)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                } else {
                    performDelete(group)
                }
            } catch (e: Exception) {
                Logger.e("GroupManagementActivity", "Failed to check group connections", e)
            }
        }
    }

    private fun performDelete(group: ConnectionGroup) {
        lifecycleScope.launch {
            try {
                // Unassign all connections from this group
                val dao = app.database.connectionDao()
                val connections = dao.getConnectionsByGroup(group.id)
                connections.forEach { connection ->
                    dao.updateConnection(connection.copy(groupId = null))
                }

                // Delete the group
                app.database.connectionGroupDao().deleteGroup(group)

                runOnUiThread {
                    Toast.makeText(this@GroupManagementActivity, "Group deleted", Toast.LENGTH_SHORT).show()
                }

                Logger.i("GroupManagementActivity", "Deleted group: ${group.name}")
            } catch (e: Exception) {
                Logger.e("GroupManagementActivity", "Failed to delete group", e)
                runOnUiThread {
                    Toast.makeText(this@GroupManagementActivity, "Failed to delete group", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateSortOrders() {
        lifecycleScope.launch {
            try {
                groups.forEachIndexed { index, group ->
                    app.database.connectionGroupDao().updateGroupSortOrder(group.id, index)
                }
                Logger.d("GroupManagementActivity", "Updated sort orders")
            } catch (e: Exception) {
                Logger.e("GroupManagementActivity", "Failed to update sort orders", e)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * RecyclerView adapter for connection groups
     */
    private class GroupAdapter(
        private val groups: List<ConnectionGroup>,
        private val onGroupClick: (ConnectionGroup) -> Unit,
        private val onGroupDelete: (ConnectionGroup) -> Unit
    ) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {

        class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val nameText: TextView = itemView.findViewById(R.id.text_group_name)
            val detailsText: TextView = itemView.findViewById(R.id.text_group_details)
            val colorIndicator: View = itemView.findViewById(R.id.view_color_indicator)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_group, parent, false)
            return GroupViewHolder(view)
        }

        override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
            val group = groups[position]

            holder.nameText.text = group.name

            // Show color indicator if color is set
            if (group.color != null) {
                try {
                    val color = android.graphics.Color.parseColor(group.color)
                    holder.colorIndicator.setBackgroundColor(color)
                    holder.colorIndicator.visibility = View.VISIBLE
                } catch (e: Exception) {
                    holder.colorIndicator.visibility = View.GONE
                }
            } else {
                holder.colorIndicator.visibility = View.GONE
            }

            // Show details (icon if set)
            holder.detailsText.text = group.icon ?: "📁"

            holder.itemView.setOnClickListener {
                onGroupClick(group)
            }
        }

        override fun getItemCount(): Int = groups.size
    }
}
