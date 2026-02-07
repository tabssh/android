package io.github.tabssh.ui.fragments

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.performance.MetricsCollector
import io.github.tabssh.performance.MetricsHistory
import io.github.tabssh.performance.PerformanceMetrics
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real-time server performance monitoring with charts
 */
class PerformanceFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    
    // UI Components
    private lateinit var spinnerConnection: Spinner
    private lateinit var chartCpu: LineChart
    private lateinit var textMemoryPercent: TextView
    private lateinit var textMemoryDetails: TextView
    private lateinit var textDiskPercent: TextView
    private lateinit var textDiskDetails: TextView
    private lateinit var textNetworkRx: TextView
    private lateinit var textNetworkTx: TextView
    private lateinit var textNetworkTotal: TextView
    private lateinit var textLoad1min: TextView
    private lateinit var textLoad5min: TextView
    private lateinit var textLoad15min: TextView
    private lateinit var textLoadProcesses: TextView
    private lateinit var layoutEmptyState: View
    private lateinit var progressLoading: CircularProgressIndicator
    private lateinit var fabControl: FloatingActionButton
    
    // Data
    private var allConnections = listOf<ConnectionProfile>()
    private var selectedConnection: ConnectionProfile? = null
    private var sshConnection: SSHConnection? = null
    private var metricsCollector: MetricsCollector? = null
    private var metricsHistory = MetricsHistory()
    
    // Monitoring state
    private var isMonitoring = false
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 5000L // 5 seconds
    
    private val monitoringRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                collectAndUpdateMetrics()
                handler.postDelayed(this, refreshInterval)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_performance, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        app = requireActivity().application as TabSSHApplication
        
        // Initialize views
        spinnerConnection = view.findViewById(R.id.spinner_connection)
        chartCpu = view.findViewById(R.id.chart_cpu)
        textMemoryPercent = view.findViewById(R.id.text_memory_percent)
        textMemoryDetails = view.findViewById(R.id.text_memory_details)
        textDiskPercent = view.findViewById(R.id.text_disk_percent)
        textDiskDetails = view.findViewById(R.id.text_disk_details)
        textNetworkRx = view.findViewById(R.id.text_network_rx)
        textNetworkTx = view.findViewById(R.id.text_network_tx)
        textNetworkTotal = view.findViewById(R.id.text_network_total)
        textLoad1min = view.findViewById(R.id.text_load_1min)
        textLoad5min = view.findViewById(R.id.text_load_5min)
        textLoad15min = view.findViewById(R.id.text_load_15min)
        textLoadProcesses = view.findViewById(R.id.text_load_processes)
        layoutEmptyState = view.findViewById(R.id.layout_empty_state)
        progressLoading = view.findViewById(R.id.progress_loading)
        fabControl = view.findViewById(R.id.fab_control)
        
        setupCpuChart()
        setupConnectionSpinner()
        setupFabControl()
        loadConnections()
        
        Logger.d("PerformanceFragment", "Fragment created")
    }

    private fun setupCpuChart() {
        chartCpu.apply {
            description.isEnabled = false
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            setPinchZoom(false)
            setDrawGridBackground(false)
            
            // X-axis configuration
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                setDrawLabels(false)
                textColor = Color.GRAY
            }
            
            // Left Y-axis configuration
            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = 100f
                setDrawGridLines(true)
                gridColor = Color.LTGRAY
                textColor = Color.GRAY
            }
            
            // Right Y-axis
            axisRight.isEnabled = false
            
            // Legend
            legend.isEnabled = false
        }
    }

    private fun setupConnectionSpinner() {
        spinnerConnection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && position <= allConnections.size) {
                    selectedConnection = allConnections[position - 1]
                    onConnectionSelected()
                } else {
                    selectedConnection = null
                    stopMonitoring()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedConnection = null
                stopMonitoring()
            }
        }
    }

    private fun setupFabControl() {
        fabControl.setOnClickListener {
            if (isMonitoring) {
                stopMonitoring()
            } else {
                startMonitoring()
            }
        }
    }

    private fun loadConnections() {
        lifecycleScope.launch {
            try {
                app.database.connectionDao().getAllConnections().collect { connections ->
                    allConnections = connections
                    updateConnectionSpinner()
                }
            } catch (e: Exception) {
                Logger.e("PerformanceFragment", "Failed to load connections", e)
            }
        }
    }

    private fun updateConnectionSpinner() {
        val items = mutableListOf("Select connection...")
        items.addAll(allConnections.map { "${it.name} (${it.username}@${it.host})" })
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerConnection.adapter = adapter
    }

    private fun onConnectionSelected() {
        val connection = selectedConnection ?: return
        
        // Stop current monitoring if any
        stopMonitoring()
        
        // Create new SSH connection
        lifecycleScope.launch {
            try {
                progressLoading.visibility = View.VISIBLE
                
                val newConnection = SSHConnection(
                    profile = connection,
                    scope = CoroutineScope(Dispatchers.IO),
                    context = requireContext()
                )
                
                // Connect to server
                withContext(Dispatchers.IO) {
                    newConnection.connect()
                }
                
                sshConnection = newConnection
                metricsCollector = MetricsCollector(newConnection)
                metricsHistory.clear()
                
                progressLoading.visibility = View.GONE
                layoutEmptyState.visibility = View.GONE
                
                Logger.d("PerformanceFragment", "Connected to ${connection.name}")
                
                // Auto-start monitoring
                startMonitoring()
                
            } catch (e: Exception) {
                progressLoading.visibility = View.GONE
                Logger.e("PerformanceFragment", "Failed to connect to ${connection.name}", e)
                showError("Failed to connect: ${e.message}")
            }
        }
    }

    private fun startMonitoring() {
        if (sshConnection == null || metricsCollector == null) {
            showError("No active connection")
            return
        }
        
        isMonitoring = true
        fabControl.setImageResource(R.drawable.ic_pause)
        handler.post(monitoringRunnable)
        
        Logger.d("PerformanceFragment", "Started monitoring")
    }

    private fun stopMonitoring() {
        isMonitoring = false
        fabControl.setImageResource(R.drawable.ic_play)
        handler.removeCallbacks(monitoringRunnable)
        
        Logger.d("PerformanceFragment", "Stopped monitoring")
    }

    private fun collectAndUpdateMetrics() {
        val collector = metricsCollector ?: return
        
        lifecycleScope.launch {
            try {
                val result = collector.collectMetrics()
                
                result.onSuccess { metrics ->
                    updateUI(metrics)
                    updateChart(metrics)
                }
                
                result.onFailure { error ->
                    Logger.e("PerformanceFragment", "Failed to collect metrics", error)
                }
                
            } catch (e: Exception) {
                Logger.e("PerformanceFragment", "Error during metrics collection", e)
            }
        }
    }

    private fun updateUI(metrics: PerformanceMetrics) {
        // Memory with color coding
        textMemoryPercent.text = "${metrics.memoryUsage.usedPercent.toInt()}%"
        textMemoryDetails.text = "${metrics.memoryUsage.usedMB} MB / ${metrics.memoryUsage.totalMB} MB"
        textMemoryPercent.setTextColor(when {
            metrics.memoryUsage.usedPercent >= 90 -> requireContext().getColor(android.R.color.holo_red_dark)
            metrics.memoryUsage.usedPercent >= 75 -> requireContext().getColor(android.R.color.holo_orange_dark)
            else -> requireContext().getColor(android.R.color.holo_green_dark)
        })
        
        // Disk with color coding
        textDiskPercent.text = "${metrics.diskUsage.usedPercent.toInt()}%"
        textDiskDetails.text = String.format("%.1f GB / %.1f GB", 
            metrics.diskUsage.usedGB, metrics.diskUsage.totalGB)
        textDiskPercent.setTextColor(when {
            metrics.diskUsage.usedPercent >= 90 -> requireContext().getColor(android.R.color.holo_red_dark)
            metrics.diskUsage.usedPercent >= 75 -> requireContext().getColor(android.R.color.holo_orange_dark)
            else -> requireContext().getColor(android.R.color.holo_green_dark)
        })
        
        // Network
        textNetworkRx.text = "↓ ${formatBytes(metrics.networkStats.rxBytesPerSec)}/s"
        textNetworkTx.text = "↑ ${formatBytes(metrics.networkStats.txBytesPerSec)}/s"
        textNetworkTotal.text = String.format("Total: %.1f MB", 
            metrics.networkStats.totalRxMB + metrics.networkStats.totalTxMB)
        
        // Load with color coding
        textLoad1min.text = String.format("1m: %.2f", metrics.loadAverage.load1min)
        textLoad5min.text = String.format("5m: %.2f", metrics.loadAverage.load5min)
        textLoad15min.text = String.format("15m: %.2f", metrics.loadAverage.load15min)
        textLoadProcesses.text = "${metrics.loadAverage.runningProcesses}/${metrics.loadAverage.totalProcesses} processes"
        
        // Color code load1 (warning if > 1.0, critical if > 2.0)
        textLoad1min.setTextColor(when {
            metrics.loadAverage.load1min >= 2.0 -> requireContext().getColor(android.R.color.holo_red_dark)
            metrics.loadAverage.load1min >= 1.0 -> requireContext().getColor(android.R.color.holo_orange_dark)
            else -> requireContext().getColor(android.R.color.holo_green_dark)
        })
        
        // Color code load1 (warning if > 1.0, critical if > 2.0)
        textLoad1min.setTextColor(when {
            metrics.loadAverage.load1min >= 2.0 -> requireContext().getColor(android.R.color.holo_red_dark)
            metrics.loadAverage.load1min >= 1.0 -> requireContext().getColor(android.R.color.holo_orange_dark)
            else -> requireContext().getColor(android.R.color.holo_green_dark)
        })
        
        // Add to history
        metricsHistory.addCpuMetric(metrics.timestamp, metrics.cpuUsage.totalPercent)
        metricsHistory.addMemoryMetric(metrics.timestamp, metrics.memoryUsage.usedPercent)
        metricsHistory.addNetworkMetric(
            metrics.timestamp,
            metrics.networkStats.rxBytesPerSec,
            metrics.networkStats.txBytesPerSec
        )
    }

    private fun updateChart(metrics: PerformanceMetrics) {
        val entries = metricsHistory.cpuHistory.mapIndexed { index, (_, value) ->
            Entry(index.toFloat(), value)
        }
        
        if (entries.isEmpty()) return
        
        val dataSet = LineDataSet(entries, "CPU Usage").apply {
            color = Color.parseColor("#1976D2")
            setCircleColor(Color.parseColor("#1976D2"))
            circleRadius = 2f
            lineWidth = 2f
            setDrawValues(false)
            setDrawCircles(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.2f
            setDrawFilled(true)
            fillColor = Color.parseColor("#1976D2")
            fillAlpha = 50
        }
        
        chartCpu.data = LineData(dataSet)
        chartCpu.notifyDataSetChanged()
        chartCpu.invalidate()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun showError(message: String) {
        // Simple toast for now - could be enhanced with Snackbar
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        // Stop monitoring when fragment is not visible
        if (isMonitoring) {
            stopMonitoring()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up
        handler.removeCallbacks(monitoringRunnable)
        
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sshConnection?.disconnect()
                }
            } catch (e: Exception) {
                Logger.e("PerformanceFragment", "Error disconnecting SSH", e)
            }
        }
        
        sshConnection = null
        metricsCollector = null
    }

    companion object {
        fun newInstance() = PerformanceFragment()
    }
}
