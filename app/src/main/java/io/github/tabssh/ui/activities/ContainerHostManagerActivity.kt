package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.dialogs.PruneConfirmDialog
import io.github.tabssh.ui.dialogs.RegistryCredentialDialog
import io.github.tabssh.ui.fragments.containers.ContainerTabSpec
import io.github.tabssh.ui.fragments.containers.ContainerTabs
import io.github.tabssh.ui.utils.ContainerEngineLabels
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Per-host container manager.
 *
 * Destinations come from [ContainerTabs], which derives them from the host
 * engine's capabilities — Dashboard, Containers, Stacks, Images, Volumes,
 * Networks in that order, each present only when the engine has the concept.
 * They are therefore built after the engine probe succeeds, not in onCreate.
 *
 * The transport is acquired ONCE via ContainerSessionManager and shared with
 * the fragments through [sessionFlow]; fragments re-load when [refreshFlow]
 * ticks. A failed probe loads no tabs at all: the screen shows one blocking
 * card naming the reason with a Retest action (IDEA.md § Container host
 * management).
 */
class ContainerHostManagerActivity : TabSSHActivity() {

    companion object {
        const val EXTRA_HOST_ID = "container_host_id"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var connectingState: LinearLayout
    private lateinit var errorState: ScrollView
    private lateinit var textErrorTitle: TextView
    private lateinit var textError: TextView

    /** Destinations for this host's engine; empty until the probe succeeds. */
    private var tabs: List<ContainerTabSpec> = emptyList()

    /** The shared per-host session; null until acquisition completes. */
    private val mutableSessionFlow = MutableStateFlow<ContainerSessionManager.ContainerSession?>(null)
    val sessionFlow: StateFlow<ContainerSessionManager.ContainerSession?> =
        mutableSessionFlow.asStateFlow()

    /** Tick to make every visible fragment re-load its data. */
    val refreshFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    var hostId: Long = -1L
        private set

    // The retry button and the "retest transport" menu item both call
    // acquireSession(); without this a double tap starts two handshakes that
    // race to publish into sessionFlow.
    private var acquiring = false

    /** Guards prune against a second tap while the first is still running. */
    private var pruning = false

    /** False once the activity is tearing down — dialogs must not be shown then. */
    private val isAlive: Boolean
        get() = !isFinishing && !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Images tab's inspect dialog can surface Config.Env from image
        // metadata — block screenshots and recents the same as the other
        // Docker screens that can show env vars.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_container_host_manager)

        app = application as TabSSHApplication
        hostId = intent.getLongExtra(EXTRA_HOST_ID, -1L)
        if (hostId == -1L) {
            finish()
            return
        }

        toolbar = findViewById(R.id.toolbar)
        tabLayout = findViewById(R.id.tab_layout)
        viewPager = findViewById(R.id.view_pager)
        connectingState = findViewById(R.id.connecting_state)
        errorState = findViewById(R.id.error_state)
        textErrorTitle = findViewById(R.id.text_error_title)
        textError = findViewById(R.id.text_error)
        findViewById<MaterialButton>(R.id.button_retry).setOnClickListener {
            acquireSession(force = true)
        }

        setSupportActionBar(toolbar)

        acquireSession(force = false)
    }

    /**
     * Build the destinations this engine actually has. Called once per
     * activity: a Retest re-runs the probe against the same host row, and the
     * engine is only editable from the host editor, which recreates this
     * screen.
     */
    private fun setupPager(engine: ContainerEngine) {
        if (tabs.isNotEmpty()) return
        tabs = ContainerTabs.forEngine(engine)
        viewPager.adapter = ContainerPagerAdapter(this)
        // Keep neighbors alive so switching between destinations is instant.
        viewPager.offscreenPageLimit = 2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.setText(tabs[position].titleRes)
        }.attach()
    }

    private fun acquireSession(force: Boolean) {
        if (acquiring) return
        acquiring = true
        connectingState.visibility = View.VISIBLE
        errorState.visibility = View.GONE
        tabLayout.visibility = View.GONE
        viewPager.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val result = ContainerSessionManager.acquire(app, hostId, force)
                if (!isAlive) return@launch
                when (result) {
                    is ContainerResult.Success -> showTabs(result.value)
                    else -> showBlockingError(result)
                }
            } finally {
                acquiring = false
            }
        }
    }

    /** Probe succeeded — build the engine's destinations and publish the session. */
    private fun showTabs(session: ContainerSessionManager.ContainerSession) {
        supportActionBar?.title = session.host.name
        supportActionBar?.subtitle = getString(
            ContainerEngineLabels.transportMode(session.mode)
        )
        setupPager(session.host.engineType())
        connectingState.visibility = View.GONE
        errorState.visibility = View.GONE
        tabLayout.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE
        mutableSessionFlow.value = session
    }

    /**
     * Probe failed — no tab can hold real data, so none are loaded. The card
     * states the reason in the transport layer's own words; the heading
     * classifies it from the ContainerResult type rather than introducing a
     * second error taxonomy.
     */
    private fun showBlockingError(result: ContainerResult<*>) {
        connectingState.visibility = View.GONE
        tabLayout.visibility = View.GONE
        viewPager.visibility = View.GONE
        errorState.visibility = View.VISIBLE
        textErrorTitle.setText(
            when (result) {
                is ContainerResult.PermissionDenied -> R.string.container_permission_title
                is ContainerResult.NotFound -> R.string.container_probe_not_found_title
                is ContainerResult.EngineNotInstalled -> R.string.container_probe_not_installed_title
                is ContainerResult.TransportUnavailable -> R.string.container_probe_unavailable_title
                else -> R.string.container_probe_failed_title
            }
        )
        textError.text = ContainerErrorPresenter.messageFor(this, result)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_container_manager, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                refreshFlow.tryEmit(Unit)
                true
            }
            R.id.action_registry_credentials -> {
                RegistryCredentialDialog.show(this, app)
                true
            }
            R.id.action_retest_transport -> {
                acquireSession(force = true)
                true
            }
            R.id.action_prune_images -> {
                prune(
                    R.string.container_prune_images_title,
                    R.string.container_prune_images_message
                ) { it.pruneImages() }
                true
            }
            R.id.action_prune_volumes -> {
                prune(
                    R.string.container_prune_volumes_title,
                    R.string.container_prune_volumes_message
                ) { it.pruneVolumes() }
                true
            }
            R.id.action_prune_networks -> {
                prune(
                    R.string.container_prune_networks_title,
                    R.string.container_prune_networks_message
                ) { it.pruneNetworks() }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Confirm, then run one prune operation and refresh the fragments. */
    private fun prune(
        titleRes: Int,
        messageRes: Int,
        operation: suspend (io.github.tabssh.containers.transport.ContainerTransport) -> ContainerResult<Unit>
    ) {
        val session = sessionFlow.value ?: return
        PruneConfirmDialog.show(this, titleRes, messageRes) {
            if (pruning) return@show
            pruning = true
            lifecycleScope.launch {
                try {
                    val result = operation(session.transport)
                    // Prune can run for minutes on a large host — the user may
                    // well have navigated away before it returns.
                    if (!isAlive) return@launch
                    when (result) {
                        is ContainerResult.Success -> {
                            Toast.makeText(
                                this@ContainerHostManagerActivity,
                                R.string.container_prune_done,
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshFlow.tryEmit(Unit)
                        }
                        else -> ContainerErrorPresenter.present(this@ContainerHostManagerActivity, result)
                    }
                } finally {
                    pruning = false
                }
            }
        }
    }

    private inner class ContainerPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = tabs.size

        override fun createFragment(position: Int): Fragment = tabs[position].create()
    }
}
