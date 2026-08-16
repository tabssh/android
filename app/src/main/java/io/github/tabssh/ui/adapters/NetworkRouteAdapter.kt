package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.NetworkRoute
import io.github.tabssh.storage.database.entities.NetworkRouteType

/**
 * RecyclerView adapter for saved [NetworkRoute] rows (proxies and SSH jump
 * hosts). Mirrors [PortForwardAdapter]: a per-row enable toggle plus an
 * overflow menu for edit / delete, and the whole row opens the editor.
 */
class NetworkRouteAdapter(
    private val onEdit: (NetworkRoute) -> Unit,
    private val onDelete: (NetworkRoute) -> Unit,
    private val onToggleEnabled: (NetworkRoute, Boolean) -> Unit
) : ListAdapter<NetworkRoute, NetworkRouteAdapter.RouteViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_route, parent, false)
        return RouteViewHolder(view)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RouteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val textName: TextView = itemView.findViewById(R.id.text_name)
        private val chipType: Chip = itemView.findViewById(R.id.chip_type)
        private val textSummary: TextView = itemView.findViewById(R.id.text_summary)
        private val textStatus: TextView = itemView.findViewById(R.id.text_status)
        private val switchEnabled: MaterialSwitch = itemView.findViewById(R.id.switch_enabled)
        private val btnMore: MaterialButton = itemView.findViewById(R.id.btn_more)

        fun bind(route: NetworkRoute) {
            val context = itemView.context

            textName.text = route.name.ifBlank { route.getSummary() }
            chipType.text = context.getString(typeLabelRes(route.routeType))
            textSummary.text = route.getSummary()

            val (labelRes, colorAttr) = if (route.enabled) {
                R.string.route_status_enabled to com.google.android.material.R.attr.colorPrimary
            } else {
                R.string.route_status_disabled to android.R.attr.textColorSecondary
            }
            textStatus.text = context.getString(labelRes)
            textStatus.setTextColor(MaterialColors.getColor(itemView, colorAttr))

            // Detach the listener before syncing checked state so recycling a row
            // never fires an unwanted toggle callback.
            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = route.enabled
            switchEnabled.contentDescription = context.getString(
                if (route.enabled) R.string.route_disable else R.string.route_enable
            )
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(route, isChecked)
            }

            btnMore.setOnClickListener { showMenu(route) }
            itemView.setOnClickListener { onEdit(route) }
        }

        private fun typeLabelRes(type: NetworkRouteType): Int = when (type) {
            NetworkRouteType.PROXY_HTTP -> R.string.route_type_proxy_http
            NetworkRouteType.PROXY_SOCKS4 -> R.string.route_type_proxy_socks4
            NetworkRouteType.PROXY_SOCKS5 -> R.string.route_type_proxy_socks5
            NetworkRouteType.JUMP_HOST -> R.string.route_type_jump_host
        }

        private fun showMenu(route: NetworkRoute) {
            val popup = android.widget.PopupMenu(itemView.context, btnMore)
            popup.menu.add(0, MENU_EDIT, 0, R.string.route_edit)
            popup.menu.add(0, MENU_DELETE, 1, R.string.route_delete)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT -> { onEdit(route); true }
                    MENU_DELETE -> { onDelete(route); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<NetworkRoute>() {
        override fun areItemsTheSame(oldItem: NetworkRoute, newItem: NetworkRoute) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: NetworkRoute, newItem: NetworkRoute) =
            oldItem == newItem
    }

    private companion object {
        const val MENU_EDIT = 1
        const val MENU_DELETE = 2
    }
}
