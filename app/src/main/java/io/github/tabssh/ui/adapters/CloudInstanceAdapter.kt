package io.github.tabssh.ui.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.cloud.CloudInstanceState
import io.github.tabssh.databinding.ItemCloudInstanceBinding

/**
 * RecyclerView adapter for [CloudInstanceState] items shown in CloudAccountManagerActivity.
 *
 * Each row shows instance name, region, IP, status indicator, and a single
 * power toggle button whose label switches between "Start" and "Stop"
 * depending on the current status. Restart and Force Restart are shown only
 * for running instances. Connect is shown for running instances with a public IP.
 */
class CloudInstanceAdapter(
    private val onPowerToggle: (CloudInstanceState) -> Unit,
    private val onConnect: (CloudInstanceState) -> Unit,
    private val onRestart: (CloudInstanceState) -> Unit,
    private val onForceRestart: (CloudInstanceState) -> Unit,
    private val onEditCredentials: (CloudInstanceState) -> Unit = {}
) : ListAdapter<CloudInstanceState, CloudInstanceAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(val b: ItemCloudInstanceBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemCloudInstanceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val inst = getItem(position)
        val b = holder.b

        b.textInstanceName.text = inst.name
        b.textInstanceRegion.text = inst.region ?: ""
        b.textInstanceIp.text = buildString {
            inst.ip?.let { append(it) }
            if (!inst.privateIp.isNullOrBlank()) {
                if (isNotEmpty()) append("  /  ")
                append(inst.privateIp)
            }
        }
        b.textInstanceStatus.text = inst.status.uppercase()

        // Status dot color: green=running, red=stopped, amber=transitioning, grey=unknown
        val dotColor = when (inst.status) {
            "running" -> 0xFF4CAF50.toInt()
            "stopped" -> 0xFFF44336.toInt()
            "starting", "stopping", "rebooting" -> 0xFFFF9800.toInt()
            else -> 0xFF9E9E9E.toInt()
        }
        b.viewStatusDot.backgroundTintList = ColorStateList.valueOf(dotColor)

        val isRunning = inst.status == "running"
        val inTransition = inst.status in listOf("starting", "stopping", "rebooting")

        // Power toggle: "Start" when stopped, "Stop" when running, "Wait…" while transitioning
        b.btnPowerToggle.text = when {
            inTransition -> "Wait…"
            isRunning -> "Stop"
            else -> "Start"
        }
        b.btnPowerToggle.isEnabled = !inTransition
        b.btnPowerToggle.setOnClickListener { onPowerToggle(inst) }

        // Connect only visible for running instances with a public IP
        b.btnConnect.isEnabled = isRunning && !inst.ip.isNullOrBlank()
        b.btnConnect.setOnClickListener { onConnect(inst) }

        // Restart and Force Restart row visible only when running; hide the whole row
        // so it takes up no space when the instance is stopped.
        b.rowRestartActions.visibility = if (isRunning) View.VISIBLE else View.GONE
        b.btnRestart.setOnClickListener { onRestart(inst) }
        b.btnForceRestart.setOnClickListener { onForceRestart(inst) }

        // Long-press anywhere on the card opens the SSH credentials editor.
        b.root.setOnLongClickListener { onEditCredentials(inst); true }
    }

    private companion object DiffCallback : DiffUtil.ItemCallback<CloudInstanceState>() {
        override fun areItemsTheSame(old: CloudInstanceState, new: CloudInstanceState): Boolean =
            old.id == new.id

        override fun areContentsTheSame(old: CloudInstanceState, new: CloudInstanceState): Boolean =
            old == new
    }
}
