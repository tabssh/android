package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
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
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.dialogs.PruneConfirmDialog
import io.github.tabssh.ui.dialogs.RegistryCredentialDialog
import io.github.tabssh.ui.fragments.docker.DockerContainersFragment
import io.github.tabssh.ui.fragments.docker.DockerDashboardFragment
import io.github.tabssh.ui.fragments.docker.DockerImagesFragment
import io.github.tabssh.ui.fragments.docker.DockerNetworksFragment
import io.github.tabssh.ui.fragments.docker.DockerStacksFragment
import io.github.tabssh.ui.fragments.docker.DockerVolumesFragment
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Docker host manager: six destinations ordered by frequency of use —
 * Containers, Stacks, Images, Volumes, Networks, Dashboard — as fragments in
 * a TabLayout + ViewPager2. The transport is acquired ONCE via
 * DockerSessionManager and shared with the fragments through [sessionFlow];
 * fragments re-load when [refreshFlow] ticks. Connect failures render an
 * inline error state with retry instead of a dialog over a blank screen.
 */
class DockerHostManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST_ID = "docker_host_id"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var connectingState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView

    /** The shared per-host session; null until acquisition completes. */
    private val mutableSessionFlow = MutableStateFlow<DockerSessionManager.DockerSession?>(null)
    val sessionFlow: StateFlow<DockerSessionManager.DockerSession?> =
        mutableSessionFlow.asStateFlow()

    /** Tick to make every visible fragment re-load its data. */
    val refreshFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    var hostId: Long = -1L
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Images tab's inspect dialog can surface Config.Env from image
        // metadata — block screenshots and recents the same as the other
        // Docker screens that can show env vars.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_docker_host_manager)

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
        textError = findViewById(R.id.text_error)
        findViewById<MaterialButton>(R.id.button_retry).setOnClickListener {
            acquireSession(force = true)
        }

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupPager()
        acquireSession(force = false)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupPager() {
        viewPager.adapter = DockerPagerAdapter(this)
        // Six destinations — keep neighbors alive so switching is instant.
        viewPager.offscreenPageLimit = 2
        // Ordered by frequency of use: the containers you manage daily first,
        // glanceable engine info (Dashboard) last.
        val titles = intArrayOf(
            R.string.docker_manager_tab_containers,
            R.string.docker_manager_tab_stacks,
            R.string.docker_manager_tab_images,
            R.string.docker_manager_tab_volumes,
            R.string.docker_manager_tab_networks,
            R.string.docker_manager_tab_dashboard
        )
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.setText(titles[position])
        }.attach()
    }

    private fun acquireSession(force: Boolean) {
        connectingState.visibility = View.VISIBLE
        errorState.visibility = View.GONE
        viewPager.visibility = View.GONE
        lifecycleScope.launch {
            when (val result = DockerSessionManager.acquire(app, hostId, force)) {
                is DockerResult.Success -> {
                    supportActionBar?.title = result.value.host.name
                    supportActionBar?.subtitle = result.value.mode
                    connectingState.visibility = View.GONE
                    viewPager.visibility = View.VISIBLE
                    mutableSessionFlow.value = result.value
                }
                else -> {
                    connectingState.visibility = View.GONE
                    errorState.visibility = View.VISIBLE
                    textError.text = DockerErrorPresenter.messageFor(
                        this@DockerHostManagerActivity, result
                    )
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_docker_manager, menu)
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
                    R.string.docker_prune_images_title,
                    R.string.docker_prune_images_message
                ) { it.pruneImages() }
                true
            }
            R.id.action_prune_volumes -> {
                prune(
                    R.string.docker_prune_volumes_title,
                    R.string.docker_prune_volumes_message
                ) { it.pruneVolumes() }
                true
            }
            R.id.action_prune_networks -> {
                prune(
                    R.string.docker_prune_networks_title,
                    R.string.docker_prune_networks_message
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
        operation: suspend (io.github.tabssh.docker.transport.DockerTransport) -> DockerResult<Unit>
    ) {
        val session = sessionFlow.value ?: return
        PruneConfirmDialog.show(this, titleRes, messageRes) {
            lifecycleScope.launch {
                when (val result = operation(session.transport)) {
                    is DockerResult.Success -> {
                        Toast.makeText(
                            this@DockerHostManagerActivity,
                            R.string.docker_prune_done,
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshFlow.tryEmit(Unit)
                    }
                    else -> DockerErrorPresenter.present(this@DockerHostManagerActivity, result)
                }
            }
        }
    }

    private inner class DockerPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 6

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> DockerContainersFragment()
            1 -> DockerStacksFragment()
            2 -> DockerImagesFragment()
            3 -> DockerVolumesFragment()
            4 -> DockerNetworksFragment()
            5 -> DockerDashboardFragment()
            else -> error("Invalid Docker manager tab position $position")
        }
    }
}
