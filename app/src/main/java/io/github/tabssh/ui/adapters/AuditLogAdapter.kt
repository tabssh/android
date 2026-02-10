package io.github.tabssh.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.storage.database.entities.AuditLogEntry

/**
 * Adapter for displaying audit log entries
 */
class AuditLogAdapter(private var logs: List<AuditLogEntry>) : 
    RecyclerView.Adapter<AuditLogAdapter.ViewHolder>() {
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textTimestamp: TextView = view.findViewById(R.id.text_log_timestamp)
        val textAction: TextView = view.findViewById(R.id.text_log_action)
        val textDetails: TextView = view.findViewById(R.id.text_log_details)
        val textStatus: TextView = view.findViewById(R.id.text_log_status)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audit_log, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = logs[position]
        
        // Format timestamp
        val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.US)
        holder.textTimestamp.text = dateFormat.format(java.util.Date(log.timestamp))
        
        // Action with emoji
        holder.textAction.text = when {
            log.eventType.startsWith("AUTH") -> "🔐 ${log.eventType}"
            log.eventType.startsWith("CONNECT") -> "🔌 ${log.eventType}"
            log.eventType.startsWith("SFTP") -> "📁 ${log.eventType}"
            log.eventType.startsWith("CONFIG") -> "⚙️ ${log.eventType}"
            log.eventType.startsWith("ERROR") -> "❌ ${log.eventType}"
            else -> log.eventType
        }
        
        // Details
        holder.textDetails.text = log.command ?: "No additional details"
        holder.textDetails.visibility = if (log.command.isNullOrEmpty()) View.GONE else View.VISIBLE
        
        // Status with color (use command/eventType as status indicator)
        holder.textStatus.text = log.eventType
        holder.textStatus.setTextColor(when {
            log.eventType.contains("SUCCESS") -> 0xFF4CAF50.toInt() // Green
            log.eventType.contains("FAILURE") || log.eventType.contains("ERROR") -> 0xFFF44336.toInt() // Red
            log.eventType.contains("DISCONNECT") -> 0xFFFF9800.toInt() // Orange
            else -> 0xFF757575.toInt() // Gray
        })
    }
    
    override fun getItemCount() = logs.size
    
    fun updateLogs(newLogs: List<AuditLogEntry>) {
        logs = newLogs
        notifyDataSetChanged()
    }
}
