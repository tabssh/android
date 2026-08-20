package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.transport.DockerCliParsers
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.utils.ContainerText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Compose stack log viewer (TODO.AI.md § D): `compose logs --follow`,
 * ANSI-stripped and auto-scrolling, for either a Room-tracked stack
 * directory or a discovered project addressed by name + config file. A
 * spinner scopes the stream to one service or aggregates all of them,
 * mirroring [ContainerDetailActivity]'s log tab.
 * FLAG_SECURE because log output routinely contains secrets.
 */
class StackLogsActivity : TabSSHActivity() {

    companion object {
        const val EXTRA_HOST_ID = "container_host_id"
        const val EXTRA_STACK_NAME = "compose_stack_name"
        const val EXTRA_STACK_DIR = "compose_stack_dir"
        const val EXTRA_CONFIG_FILE = "compose_config_file"
        const val EXTRA_EXTERNAL_NAME = "compose_external_name"
        private const val MAX_LOG_LINES = 2000
        // Per-line cap — one pathological line must not blow up the TextView.
        private const val MAX_LOG_LINE_CHARS = 4096
        // CSI sequences, OSC sequences (BEL or ST terminated), and stray ESCs.
        private val ANSI_REGEX = Regex(
            "\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?|\u001B"
        )

        /**
         * Compose log lines are fully remote-controlled: strip ANSI first, then
         * drop the C0/C1 controls and bidi overrides ANSI_REGEX does not cover
         * (a lone U+202E would visually reverse the rest of the line).
         */
        internal fun sanitizeLogLine(line: String): String =
            ContainerText.block(ANSI_REGEX.replace(line, ""), MAX_LOG_LINE_CHARS)
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var spinnerService: Spinner
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollLogs: ScrollView
    private lateinit var textLogs: TextView

    private var hostId: Long = 0
    private var stackName: String = ""
    private var stackDir: String? = null
    private var configFile: String? = null
    private var externalName: String? = null
    private var session: ContainerSessionManager.ContainerSession? = null
    private var logsJob: Job? = null
    private val logLines = ArrayDeque<String>()
    private var services: List<String> = emptyList()
    private var suppressSpinnerCallback = false

    /** True while an auto-scroll runnable is already queued — one post per frame, not per line. */
    private var scrollPending = false

    /** False once the activity can no longer host a dialog or touch its views. */
    private fun isAlive(): Boolean = !isFinishing && !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_stack_logs)

        app = application as TabSSHApplication
        hostId = intent.getLongExtra(EXTRA_HOST_ID, 0)
        stackName = intent.getStringExtra(EXTRA_STACK_NAME).orEmpty()
        stackDir = intent.getStringExtra(EXTRA_STACK_DIR)
        configFile = intent.getStringExtra(EXTRA_CONFIG_FILE)
        externalName = intent.getStringExtra(EXTRA_EXTERNAL_NAME)

        toolbar = findViewById(R.id.toolbar)
        spinnerService = findViewById(R.id.spinner_service)
        progressBar = findViewById(R.id.progress_bar)
        scrollLogs = findViewById(R.id.scroll_logs)
        textLogs = findViewById(R.id.text_logs)

        setSupportActionBar(toolbar)
        // The stack name reaches this screen from `docker compose ls` output —
        // sanitize before it becomes the toolbar title.
        toolbar.title = getString(R.string.container_stack_logs_title, ContainerText.display(stackName))

        spinnerService.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (suppressSpinnerCallback) return
                startLogs(services.getOrNull(position - 1))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        acquireSession()
    }

    private fun acquireSession() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = ContainerSessionManager.acquire(app, hostId)
            // acquire() suspends — the activity may be gone by the time it returns.
            if (!isAlive()) return@launch
            when (result) {
                is ContainerResult.Success -> {
                    session = result.value
                    loadServices()
                    startLogs(service = null)
                }
                else -> {
                    progressBar.visibility = View.GONE
                    ContainerErrorPresenter.present(this@StackLogsActivity, result)
                }
            }
        }
    }

    /** Populate the service chooser from `compose ps`; failures just leave it aggregated-only. */
    private fun loadServices() {
        val current = session ?: return
        lifecycleScope.launch {
            val output = psOutput(current)
            if (!isAlive()) return@launch
            services = output?.let { DockerCliParsers.parseComposePsServices(it) }.orEmpty()
            val labels = mutableListOf(getString(R.string.container_stack_logs_all_services))
            // Service names come straight from `compose ps` — sanitize the
            // spinner labels; `services` keeps the raw values the CLI expects.
            labels.addAll(services.map { ContainerText.display(it) })
            val adapter = ArrayAdapter(
                this@StackLogsActivity, android.R.layout.simple_spinner_item, labels
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            suppressSpinnerCallback = true
            spinnerService.adapter = adapter
            spinnerService.setSelection(0, false)
            suppressSpinnerCallback = false
        }
    }

    private suspend fun psOutput(session: ContainerSessionManager.ContainerSession): String? {
        val dir = stackDir
        val name = externalName
        val file = configFile
        val result = if (dir != null) {
            session.transport.composePs(dir)
        } else if (name != null && file != null) {
            session.transport.composePsByProject(name, file)
        } else {
            null
        }
        return result?.valueOrNull()
    }

    private fun startLogs(service: String?) {
        val current = session ?: return
        logsJob?.cancel()
        logLines.clear()
        textLogs.text = ""
        progressBar.visibility = View.VISIBLE
        val flow: Flow<String> = composeLogsFlow(current, service) ?: return
        logsJob = lifecycleScope.launch {
            try {
                flow.collect { line ->
                    progressBar.visibility = View.GONE
                    logLines.addLast(sanitizeLogLine(line))
                    while (logLines.size > MAX_LOG_LINES) {
                        logLines.removeFirst()
                    }
                    textLogs.text = logLines.joinToString("\n")
                    // One queued scroll at a time — a fast stream would
                    // otherwise post a runnable per line and starve the looper.
                    if (!scrollPending) {
                        scrollPending = true
                        scrollLogs.post {
                            scrollPending = false
                            scrollLogs.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
                progressBar.visibility = View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The stream throws into the collector when the SSH session
                // dies mid-follow — surface it instead of crashing the screen.
                if (!isAlive()) return@launch
                progressBar.visibility = View.GONE
                ContainerErrorPresenter.present(
                    this@StackLogsActivity,
                    ContainerResult.Error(
                        ContainerText.display(e.message).ifEmpty {
                            getString(R.string.container_error_title)
                        }
                    )
                )
            }
        }
    }

    private fun composeLogsFlow(
        session: ContainerSessionManager.ContainerSession,
        service: String?
    ): Flow<String>? {
        val dir = stackDir
        val name = externalName
        val file = configFile
        return when {
            dir != null -> session.transport.composeLogs(dir, service, tail = 200)
            name != null && file != null ->
                session.transport.composeLogsByProject(name, file, service, tail = 200)
            else -> null
        }
    }

    override fun onDestroy() {
        logsJob?.cancel()
        super.onDestroy()
    }
}
