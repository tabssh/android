package io.github.tabssh.ui.activities

import io.github.tabssh.sync.tombstone.TombstoneRecorder
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import io.github.tabssh.network.ConnectionDiagnostic
import io.github.tabssh.hypervisor.vnc.VncBackgroundSessionStore
import io.github.tabssh.services.VncKeepAliveService
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.ui.adapters.TerminalPagerAdapter
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.ui.views.VncView
import io.github.tabssh.ui.views.SpiceView
import io.github.tabssh.ui.tabs.ConsoleTab
import io.github.tabssh.ui.tabs.ConsoleDisplayMode
import io.github.tabssh.hypervisor.console.rfb.RfbConstants
import io.github.tabssh.hypervisor.spice.SpiceConstants
import io.github.tabssh.ui.keyboard.ConsoleKeyMapper
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityTabTerminalBinding
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.hypervisor.console.ConsoleDisconnectReason
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.ui.tabs.SSHTab
import io.github.tabssh.ui.tabs.Tab
import io.github.tabssh.ui.tabs.TabManager
import io.github.tabssh.ui.tabs.TabManagerListener
import io.github.tabssh.ui.tabs.shortTitle
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.tabssh.utils.showError
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.themes.definitions.BuiltInThemes
import io.github.tabssh.terminal.search.ScrollbackSearchController

/**
 * Main terminal activity with tabbed SSH sessions
 * This is the core innovation of TabSSH - browser-style SSH tabs
 */
// `cont.resume(value, onCancellation)` (used in promptForPassword) is
// flagged @ExperimentalCoroutinesApi. Rather than tag every transitive
// caller, opt-in once at the class level.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TabTerminalActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_CONNECTION_PROFILE_ID = "connection_profile_id"
        const val EXTRA_CONNECTION_PROFILE = "connection_profile"
        const val EXTRA_AUTO_CONNECT = "auto_connect"
        // Issue #165 — used by the Connections-tab "Active Sessions" strip
        // to focus an already-running tab instead of (re)connecting. The
        // activity reads this in handleIntent(), looks up the tab in the
        // shared TabManager, and switches to its index.
        const val EXTRA_TAB_ID = "tab_id"
        // When true, bypass the reattach short-circuit in connectToProfile and always
        // open a brand-new tab. Set by ConnectionLauncher when the user explicitly
        // chooses "New connection" from the reattach dialog.
        const val EXTRA_FORCE_NEW = "force_new"
        // Preference key that persists whether the custom function-key bar is visible.
        const val PREF_KEY_BAR_VISIBLE = "key_bar_visible"
        // Set by LinkHandlerActivity for a tapped sftp:// link: once the transient
        // quick-connect profile embedded in this intent finishes connecting, open
        // SFTPActivity at this remote path instead of just landing on the terminal.
        // Never set for normal (non-link) connects.
        const val EXTRA_OPEN_SFTP_PATH = "open_sftp_path"
        // Custom-keyboard-bar key ids handled as activity-level actions rather
        // than terminal/console input — handleCustomKeyPress() never routes
        // these to sendConsoleKeyPress() even on a graphical console tab.
        private val CONSOLE_BAR_ACTION_KEY_IDS = setOf(
            "PASTE", "CLIPBOARD", "MENU", "TOGGLE", "STOP_RECORDING", "PREFIX"
        )

        fun createIntent(
            context: Context,
            profile: ConnectionProfile,
            autoConnect: Boolean = true,
            forceNew: Boolean = false
        ): Intent {
            return Intent(context, TabTerminalActivity::class.java).apply {
                putExtra(EXTRA_CONNECTION_PROFILE_ID, profile.id)
                // Also embed the full profile as JSON so unsaved (quick-connect) profiles work
                putExtra(EXTRA_CONNECTION_PROFILE,
                    kotlinx.serialization.json.Json.encodeToString(ConnectionProfile.serializer(), profile))
                putExtra(EXTRA_AUTO_CONNECT, autoConnect)
                if (forceNew) putExtra(EXTRA_FORCE_NEW, true)
            }
        }
    }
    
    private lateinit var binding: ActivityTabTerminalBinding
    private lateinit var app: TabSSHApplication
    private lateinit var tabManager: TabManager

    // file:// "Open" round trip for tapped file:// terminal links (see
    // showRemoteFileDialog). Constructed here, before onCreate finishes, so
    // it can observe this activity's lifecycle for the post-edit resume
    // check.
    private val remoteFileOpener = io.github.tabssh.sftp.RemoteFileOpener(this) {
        (application as TabSSHApplication).preferencesManager.getFileOpenSizeLimitMb()
    }

    /**
     * Set to `true` while a Reconnect-from-disconnect-dialog flow is in
     * flight. The reconnect path must close the dead tab before opening
     * the new one (so the strip slot is reused), but `closeTab` drives
     * `onTabClosed` → `if (count == 0) finish()` — which would tear
     * down this activity AND cancel the just-launched reconnect
     * coroutine via lifecycleScope. With this flag set, the
     * onTabClosed handler skips the auto-finish; the reconnect
     * coroutine clears the flag in its finally{} and finishes the
     * activity itself if the connection failed and no tabs remain.
     *
     * @Volatile because onTabClosed posts to the main looper but the
     * flip happens from coroutine continuations that may run on
     * worker threads.
     */
    @Volatile private var isReconnecting = false

    /**
     * Profile IDs for which a silent mosh→SSH fallback has already been
     * attempted in the current activity lifetime. Prevents a fallback loop
     * if SSH itself also fails — the second failure goes to the normal
     * reconnect dialog instead of looping back into another silent retry.
     * In-memory only: a fresh activity (or app restart) gets a clean slate
     * so the user can retry mosh later (e.g. after switching networks).
     */
    private val moshFallbackAttempted: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    // True when this onCreate invocation is a config-change recreation (e.g. rotation).
    // Used to suppress "Reattached" toasts that would fire on every rotate.
    private var isRecreated = false

    // Held as a field so onDestroy can call tabManager.removeListener — see
    // setupTabManager() for the construction site and the leak rationale.
    private var tabManagerListener: TabManagerListener? = null

    // UI components
    private var terminalView: TerminalView? = null
    private var viewPager: ViewPager2? = null
    private var pagerAdapter: TerminalPagerAdapter? = null
    private var tabLayoutMediator: TabLayoutMediator? = null
    private var swipeEnabled: Boolean = true
    // Edge-zone swipe: when > 0, a horizontal tab swipe only starts if the
    // finger goes down within this many pixels of the left or right edge;
    // touches that begin in the middle fall through to the terminal (so
    // vim/tmux horizontal gestures and text selection aren't hijacked). 0
    // disables the gate (swipe from anywhere, legacy behaviour). Set from
    // the "tab_swipe_edge_dp" preference in setupTerminalView().
    private var tabSwipeEdgePx: Int = 0
    // True only while text selection is active — fully suspends swipe
    // regardless of where the finger goes down (see edge-zone listener).
    private var swipeSuspendedForSelection: Boolean = false
    // Per-gesture state for the edge-zone reject feedback (haptic + glow) in
    // applyEdgeSwipeGate() — a mid-screen swipe that gets rejected by the
    // edge-zone check otherwise fails completely silently, which reads as
    // "broken" rather than "by design". Down coords are raw (screen-space)
    // because the gate runs in dispatchTouchEvent. Reset on every ACTION_DOWN.
    private var edgeGateDownX: Float = 0f
    private var edgeGateDownY: Float = 0f
    private var edgeGateRejectedForGesture: Boolean = false
    private var edgeGateFeedbackFiredForGesture: Boolean = false
    // Set to true while updateViewPagerAdapter() is swapping the adapter.
    // ViewPager2 fires onPageSelected(0) when a new adapter is assigned
    // (it resets to page 0), and setCurrentItem() fires onPageSelected for
    // the target page. Both callbacks queue onActiveTabChanged handlers that
    // fight each other and produce a bounce loop. Suppressing both during
    // the swap and re-syncing TabManager once manually breaks the cycle.
    private var isUpdatingAdapter = false
    
    // Performance overlay
    private var performanceOverlay: io.github.tabssh.ui.views.PerformanceOverlayView? = null
    private var performanceUpdateJob: Job? = null
    
    // Custom keyboard
    private var customKeyboardVisible: Boolean = true
    /** Coroutine that mirrors the active tab's multiplexer StateFlow to the PREFIX key visual state. */
    private var multiplexerObserverJob: kotlinx.coroutines.Job? = null
    /**
     * True while the PREFIX key is "armed" — user tapped it once and is about
     * to tap the tmux/screen command key. A second tap on PRE cancels (disarms)
     * without sending anything. Any other key tap sends the prefix then the key.
     * Mirrors the latch behaviour of CTL/ALT on the keyboard bar.
     */
    private var prefixArmed = false

    /**
     * The multiplexer type string that was active when [prefixArmed] was set.
     * Stored so the arm-handler can recall it without querying the tab again
     * (the tab's detection state could theoretically change between arm and fire).
     */
    private var prefixArmedType: String? = null

    /**
     * Latched CTL/ALT/SFT id (bar's [MultiRowKeyboardView] modifier state)
     * pending for the next key sent to a graphical console tab (RFB/SPICE).
     * TerminalView tracks its own pending-modifier state internally
     * ([TerminalView.setPendingModifier]); VncView/SpiceView have no such
     * concept, so [sendConsoleKeyPress] brackets the next key with this
     * modifier and clears it — same one-shot-latch contract as the
     * terminal path.
     */
    private var consolePendingModifier: String? = null

    // Find-in-scrollback
    private var searchController: ScrollbackSearchController? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Logger.d("TabTerminalActivity", "onCreate")
        isRecreated = savedInstanceState != null

        app = application as TabSSHApplication
        binding = ActivityTabTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupTabManager()
        setupTabLayout()
        setupTerminalView()
        setupTerminalGestures()  // NEW: Edge tap gestures for menu/toolbar
        setupFunctionKeys()
        setupCustomKeyboard()
        setupPerformanceOverlay()
        setupBackPressHandler()
        setupMenuFab()
        setupBottomActionBar()  // NEW: Bottom toolbar setup
        setupSearchOverlay()
        applyTerminalUiPrefs()
        // Host-key verification dialogs are wired application-wide via
        // TabSSHApplication.wireGlobalHostKeyCallbacks(). The previous
        // per-activity setup was kept around briefly while migrating; it
        // was redundant once the central wiring landed and is now removed.

        // Handle intent
        handleIntent(intent)

        Logger.i("TabTerminalActivity", "Terminal activity created")
    }

    // singleTop: new intent arrives while this instance is at the top of the
    // stack (e.g. notification tap, widget tap, or MainActivity re-launching
    // while the terminal is already visible). Route through handleIntent() so
    // the reattach short-circuit in connectToProfile() fires as usual.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun setupBackPressHandler() {
        // BACK handler reads IME visibility via WindowInsetsCompat at
        // press-time (see handleBackToMainActivity). An earlier
        // OnGlobalLayoutListener that mirrored IME state into a field
        // was removed — the field was never read, and the listener fired
        // on every layout pass while the activity was alive.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Logger.i("TabTerminalActivity", "BACK pressed — handler invoked")
                handleBackToMainActivity()
            }
        })
    }

    /**
     * BACK behaviour: hide IME if shown, otherwise surface MainActivity and
     * finish this activity. SSH tabs and their TermuxBridges survive because
     * onDestroy() no longer calls tabManager.cleanup() — the TabManager is
     * Application-scoped and outlives any individual activity instance. The
     * next TabTerminalActivity (started from MainActivity or a notification)
     * picks the tabs back up in setupTabManager() and rebinds the ViewPager.
     *
     * REORDER_TO_FRONT on the MainActivity intent handles the edge case where
     * MainActivity is not already in the back stack (e.g. launched from a
     * widget) — it brings or creates a MainActivity instead of leaving a
     * blank back stack after finish().
     */
    private fun handleBackToMainActivity() {
        // Dismiss the search overlay first if it is visible.
        val sc = searchController
        if (sc != null && sc.isVisible) {
            sc.dismiss()
            Logger.d("TabTerminalActivity", "BACK: dismissed search overlay")
            return
        }

        val terminalView = getActiveTerminalView()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        // Snapshot the mutable terminalView ref so a concurrent assignment
        // (tab swap on a different thread) cannot null it between the IME
        // visibility check and hideSoftInputFromWindow().
        val tv = terminalView
        if (tv != null) {
            val imeShown = androidx.core.view.ViewCompat.getRootWindowInsets(tv)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            if (imeShown) {
                imm.hideSoftInputFromWindow(tv.windowToken, 0)
                Logger.d("TabTerminalActivity", "BACK: hid IME, staying in terminal")
                return
            }
        }

        Logger.i(
            "TabTerminalActivity",
            "BACK: finishing activity, ${tabManager.getTabCount()} tab(s) survive in TabManager"
        )
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        finish()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.terminal_activity_title)
        }
    }
    
    private fun setupTabManager() {
        // Use the Application-scoped TabManager so tabs survive activity
        // destruction. Back-button finishes the activity but the SSH
        // session was already pooled in SSHSessionManager (prior fix), so
        // the only thing missing for true reattach was a TabManager that
        // also outlives the activity. Now the activity is a *view* over
        // the shared tab list, not its owner — recreating the activity
        // rebinds existing TermuxBridges to fresh TerminalViews, and the
        // emulator buffer (which lives on the bridge) shows up restored.
        //
        // `ui_max_tabs` pref is read by the app-scoped manager at first
        // construction; the per-activity coerce is no longer applied
        // here (the lazy {} block in TabSSHApplication uses the default
        // 10, which matches the historical behaviour before this pref
        // was wired up — fine for now, can be reworked if users hit it).
        tabManager = app.tabManager

        // Stored as a field (not anonymous-inline) so onDestroy can call
        // tabManager.removeListener(it). Anonymous listeners hold an
        // implicit `this@TabTerminalActivity` reference and prevented the
        // activity from being collected across reconnect cycles.
        tabManagerListener = object : TabManagerListener {
            override fun onTabCreated(tab: SSHTab) {
                Handler(Looper.getMainLooper()).post {
                    addTabToUI(tab)
                    Logger.d("TabTerminalActivity", "Tab created: ${tab.profile.getDisplayName()}")
                }
            }

            override fun onTabClosed(tab: SSHTab, index: Int) {
                Handler(Looper.getMainLooper()).post {
                    // Drop any cached remote history for this tab so the map
                    // does not grow without bound across many open/close cycles.
                    historyCache.remove(tab.tabId)
                    removeTabFromUI(index)

                    // Release the SSH session in SSHSessionManager once
                    // the last tab for this profile is gone. Without
                    // this, the foreground-service "N active sessions"
                    // notification stays stuck — `SSHTab.disconnect()`
                    // closes only the per-tab channel; the underlying
                    // Session is owned by SSHSessionManager and lives
                    // on until something explicitly calls
                    // closeConnection(). closeConnection() fires both
                    // onConnectionClosed (service recomputes count)
                    // AND onConnectionStateChanged(..., DISCONNECTED)
                    // (wireGlobalNotifications swaps the "Connected to
                    // X" toast for "Disconnected from X").
                    val profileId = tab.profile.id
                    val anyRemaining = tabManager.getAllTabs().any { it.profile.id == profileId }
                    if (!anyRemaining) {
                        // closeConnection is now suspend — dispatch to IO so
                        // the Handler.post callback (Main thread) doesn't block
                        // on JSch session teardown.
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                app.sshSessionManager.closeConnection(profileId)
                            } catch (e: Exception) {
                                Logger.w("TabTerminalActivity", "Failed to release SSH session for $profileId on tab close", e)
                            }
                        }
                    }

                    // Close activity if no tabs remain — UNLESS we're
                    // mid-reconnect, in which case a new tab is about to
                    // be created. See `isReconnecting` doc.
                    if (tabManager.getTabCount() == 0 && !isReconnecting) {
                        finish()
                    }
                }
            }

            override fun onGraphicalTabClosed(tab: io.github.tabssh.ui.tabs.Tab, index: Int) {
                // Vnc/Console counterpart of onTabClosed: rebuild the pager
                // and finish when the last tab is gone. Centralised here so a
                // close initiated OUTSIDE this activity (the notification-shade
                // Disconnect action) updates the UI exactly like a toolbar
                // close — closeConsoleTab no longer does its own UI work.
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    removeTabFromUI(index)
                    if (tabManager.getTabCount() == 0 && !isReconnecting) {
                        finish()
                    }
                }
            }

            override fun onActiveTabChanged(index: Int) {
                Handler(Looper.getMainLooper()).post {
                    // Guard against the swipe feedback loop: when the user swiped
                    // to this page, onPageSelected already fired and ViewPager is
                    // settled on the target. Calling setCurrentItem here would
                    // re-fire onPageSelected → tabManager.switchToTab → this
                    // callback → setCurrentItem indefinitely. Skip if the pager
                    // is already on the requested page.
                    if (viewPager?.currentItem != index) {
                        switchToTab(index)
                    }
                }
            }

            override fun onTabConnectionStateChanged(tab: SSHTab, state: ConnectionState) {
                Handler(Looper.getMainLooper()).post {
                    updateTabIcon(tab, state)
                }
            }
        }
        tabManager.addListener(tabManagerListener!!)

        // VNC/SPICE tabs have no TabManagerListener coverage (it is SSH-typed
        // by design) — wire their close-policy observers separately.
        wireConsoleClosePolicy()

        // Pick up tabs that already exist in the shared manager — they
        // were created by a previous activity instance and outlived its
        // destruction. The listener's onTabCreated only fires for NEW
        // tabs (post-addListener), so existing ones need an explicit
        // rebuild or the ViewPager renders empty until the user creates
        // one. Calling updateViewPagerAdapter() once is enough — it
        // pulls the whole tab list from the shared manager.
        //
        // Posted (not called inline) so the ViewPager + TabLayout are
        // fully attached to the window first; addTabToUI's update path
        // touches both.
        // Sealed list, not getAllTabs() — that one returns SSH tabs only, so an
        // activity launched for a VNC/SPICE tab (VncHostsActivity creates the tab
        // then starts this activity) saw an empty list, skipped the adapter
        // rebuild, and left the console page unbound — RfbClient.start() is only
        // called from the pager's bind, so the framebuffer stayed black.
        val existingTabs = tabManager.getAllTabsSealed()
        if (existingTabs.isNotEmpty()) {
            Logger.i(
                "TabTerminalActivity",
                "Reattaching to ${existingTabs.size} existing tab(s) from shared TabManager"
            )
            Handler(Looper.getMainLooper()).post {
                updateViewPagerAdapter()
            }
        }
    }

    private fun setupMenuFab() {
        binding.fabMenu.setOnClickListener {
            showTerminalMenu()
        }
    }
    
    /**
     * Setup bottom action bar with slide-up functionality
     */
    private fun setupBottomActionBar() {
        // `show_bottom_nav` preference (default false). Per UX feedback the
        // bar sandwiched between the custom multi-row keyboard and the Android
        // keyboard felt redundant — same actions are reachable from the
        // terminal long-press menu. Users who liked it can re-enable it in
        // Settings → Terminal → Custom Keyboard → Show bottom nav bar.
        val persistent = app.preferencesManager.getBoolean("show_bottom_nav", false)
        binding.bottomActionBar.visibility = if (persistent) View.VISIBLE else View.GONE

        binding.btnKeyboard.setOnClickListener {
            toggleKeyboard()
            if (!persistent) hideBottomActionBar()
        }
        binding.btnSnippets.setOnClickListener {
            showSnippetsDialog()
            if (!persistent) hideBottomActionBar()
        }
        binding.btnFiles.setOnClickListener {
            openFileManager()
            if (!persistent) hideBottomActionBar()
        }
        binding.btnPaste.setOnClickListener {
            pasteFromClipboard()
            if (!persistent) hideBottomActionBar()
        }
        binding.btnMenu.setOnClickListener {
            showTerminalMenu()
            if (!persistent) hideBottomActionBar()
        }
    }


    /**
     * Setup edge tap gestures for showing UI elements. Lint flags this
     * as ClickableViewAccessibility because we can't override
     * performClick() on a CoordinatorLayout instance we didn't subclass —
     * we delegate via `v.performClick()` inside the listener instead,
     * which is the framework-recommended fallback.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupTerminalGestures() {
        // Get the root view
        val rootView = binding.root
        
        rootView.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                val width = rootView.width
                val height = rootView.height

                // Tap on left edge (first 10%) shows menu FAB temporarily
                if (x < width * 0.1f && y < height * 0.5f) {
                    showMenuFabTemporarily()
                    v.performClick()
                    return@setOnTouchListener true
                }

                // Tap on bottom edge (last 10%) shows bottom action bar
                if (y > height * 0.9f) {
                    toggleBottomActionBar()
                    v.performClick()
                    return@setOnTouchListener true
                }
            }
            false
        }
    }
    
    private fun showMenuFabTemporarily() {
        // Fallback for users who've hidden the multi-row keyboard bar
        // (the primary menu entry is the ☰ key there). Edge tap on the
        // top-left reveals the FAB for 3 seconds; tapping the FAB opens
        // the same bottom-sheet menu the keyboard key opens.
        //
        // Use View.postDelayed so the queued runnable is dropped automatically
        // when the view is detached (activity destroyed) — a Handler on the
        // main Looper would keep firing into a dead activity and leak the
        // binding reference for the full 3-second window.
        binding.fabMenu.visibility = View.VISIBLE
        binding.fabMenu.postDelayed({
            if (!isFinishing && !isDestroyed) {
                binding.fabMenu.visibility = View.GONE
            }
        }, 3000)
    }
    
    private fun toggleBottomActionBar() {
        if (binding.bottomActionBar.visibility == View.VISIBLE) {
            hideBottomActionBar()
        } else {
            showBottomActionBar()
        }
    }
    
    private fun showBottomActionBar() {
        binding.bottomActionBar.visibility = View.VISIBLE

        // Auto-hide after 5 seconds. View.postDelayed (not Handler) so the
        // runnable is dropped when the view detaches on activity destroy.
        binding.bottomActionBar.postDelayed({
            if (!isFinishing && !isDestroyed) hideBottomActionBar()
        }, 5000)
    }
    
    private fun hideBottomActionBar() {
        binding.bottomActionBar.visibility = View.GONE
    }
    
    private fun showTerminalMenu() {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_terminal_menu, null)

        // Snapshot tab state at open time so the list is stable while the sheet is open.
        val tabs = tabManager.getAllTabs()
        val activeIndex = tabManager.getActiveTabIndex()

        // Build the tab rows programmatically inside the RecyclerView.
        val tabsRecyclerView = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tabs_recycler_view)
        tabsRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        tabsRecyclerView.isNestedScrollingEnabled = false

        val tabAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

            inner class TabRowHolder(val row: android.widget.LinearLayout) :
                androidx.recyclerview.widget.RecyclerView.ViewHolder(row)

            override fun onCreateViewHolder(
                parent: android.view.ViewGroup,
                viewType: Int
            ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val ctx = parent.context
                val dp = ctx.resources.displayMetrics.density

                // Outer row: horizontal LinearLayout, 48dp min height, 16dp horizontal padding.
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    minimumHeight = (48 * dp).toInt()
                    setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    val rippleAttrs = intArrayOf(android.R.attr.selectableItemBackground)
                    val typedArray = ctx.obtainStyledAttributes(rippleAttrs)
                    background = typedArray.getDrawable(0)
                    typedArray.recycle()
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                // State dot icon: 20×20dp.
                val stateIcon = android.widget.ImageView(ctx).apply {
                    id = android.view.View.generateViewId()
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (20 * dp).toInt(),
                        (20 * dp).toInt()
                    ).also { it.marginEnd = (12 * dp).toInt() }
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }

                // Tab name label: takes all remaining horizontal space.
                val nameLabel = android.widget.TextView(ctx).apply {
                    id = android.view.View.generateViewId()
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    textSize = 16f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    val textColorAttrs = intArrayOf(android.R.attr.textColorPrimary)
                    val ta = ctx.obtainStyledAttributes(textColorAttrs)
                    setTextColor(ta.getColorStateList(0))
                    ta.recycle()
                }

                // Right-side status icon: 16×16dp, visible only to distinguish active vs navigable.
                val rightIcon = android.widget.ImageView(ctx).apply {
                    id = android.view.View.generateViewId()
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (16 * dp).toInt(),
                        (16 * dp).toInt()
                    ).also { it.marginStart = (8 * dp).toInt() }
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }

                row.addView(stateIcon)
                row.addView(nameLabel)
                row.addView(rightIcon)

                // Tag the child views by position index so onBindViewHolder can find them.
                row.tag = Triple(stateIcon, nameLabel, rightIcon)
                return TabRowHolder(row)
            }

            override fun onBindViewHolder(
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                position: Int
            ) {
                val tab = tabs[position]
                val rowHolder = holder as TabRowHolder
                val ctx = rowHolder.row.context
                val (stateIcon, nameLabel, rightIcon) = rowHolder.row.tag as Triple<*, *, *>
                val stateView = stateIcon as android.widget.ImageView
                val nameView = nameLabel as android.widget.TextView
                val rightView = rightIcon as android.widget.ImageView
                val isActive = position == activeIndex

                // Connection-state dot with semantic tint.
                val connectionState = tab.connectionState.value
                when (connectionState) {
                    ConnectionState.CONNECTED -> {
                        stateView.setImageResource(R.drawable.ic_connected)
                        stateView.imageTintList = android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(ctx, R.color.connected)
                        )
                        stateView.contentDescription = "Connected"
                    }
                    ConnectionState.CONNECTING -> {
                        stateView.setImageResource(R.drawable.ic_connecting)
                        stateView.imageTintList = android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(ctx, R.color.connecting)
                        )
                        stateView.contentDescription = "Connecting"
                    }
                    ConnectionState.AUTHENTICATING -> {
                        stateView.setImageResource(R.drawable.ic_connecting)
                        stateView.imageTintList = android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(ctx, R.color.connecting)
                        )
                        stateView.contentDescription = "Authenticating"
                    }
                    ConnectionState.ERROR -> {
                        stateView.setImageResource(R.drawable.ic_error)
                        stateView.imageTintList = android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(ctx, R.color.connection_error)
                        )
                        stateView.contentDescription = "Connection error"
                    }
                    ConnectionState.DISCONNECTED -> {
                        stateView.setImageResource(R.drawable.ic_disconnect)
                        val onSurfaceVariantAttrs = intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant)
                        val ta = ctx.obtainStyledAttributes(onSurfaceVariantAttrs)
                        stateView.imageTintList = ta.getColorStateList(0)
                        ta.recycle()
                        stateView.contentDescription = "Disconnected"
                    }
                }

                // Tab name: bold when active.
                nameView.text = tab.profile.getDisplayName()
                nameView.setTypeface(
                    nameView.typeface,
                    if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                // Content description for the whole row assists TalkBack.
                val stateDesc = stateView.contentDescription
                rowHolder.row.contentDescription =
                    "${tab.profile.getDisplayName()}, $stateDesc${if (isActive) ", active tab" else ""}"

                // Right icon: filled checkmark for active row, chevron for others.
                if (isActive) {
                    rightView.setImageResource(R.drawable.ic_connected)
                    rightView.imageTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.connected)
                    )
                    rightView.visibility = android.view.View.VISIBLE
                    rightView.contentDescription = "Active"
                } else {
                    rightView.setImageResource(R.drawable.ic_forward)
                    val onSurfaceVariantAttrs = intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    val ta = ctx.obtainStyledAttributes(onSurfaceVariantAttrs)
                    rightView.imageTintList = ta.getColorStateList(0)
                    ta.recycle()
                    rightView.visibility = android.view.View.VISIBLE
                    rightView.contentDescription = null
                }

                rowHolder.row.setOnClickListener {
                    // Switch by tabId — tabs may have been closed or reordered
                    // while the sheet was open, so the snapshot position could
                    // point to the wrong tab in the live list. tabId also
                    // avoids the SSH-only/unified dual-index-space bug: this
                    // row's `tabs` snapshot came from getAllTabs() (SSH-only),
                    // which is not a valid unified-list index once VNC/console
                    // tabs exist.
                    if (tabManager.switchToTabById(tab.tabId)) {
                        val liveIdx = tabManager.getAllTabsSealed()
                            .indexOfFirst { it.tabId == tab.tabId }
                        if (liveIdx >= 0) switchToTab(liveIdx)
                    }
                    bottomSheet.dismiss()
                }
            }

            override fun getItemCount() = tabs.size
        }
        tabsRecyclerView.adapter = tabAdapter

        // Primary action — new tab.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_new_tab)
            ?.setOnClickListener {
                bottomSheet.dismiss()
                showConnectionSelector()
            }

        // Terminal section.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_toggle_system_keyboard)
            ?.setOnClickListener {
                bottomSheet.dismiss()
                toggleKeyboard()
            }

        val keyBarBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_toggle_key_bar)
        keyBarBtn?.text = if (customKeyboardVisible) "Hide Key Bar" else "Show Key Bar"
        keyBarBtn?.setOnClickListener {
            bottomSheet.dismiss()
            if (customKeyboardVisible) hideCustomKeyboardBar() else showCustomKeyboardBar()
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_find_in_scrollback)
            ?.setOnClickListener {
                bottomSheet.dismiss()
                showSearchOverlay()
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_snippets)
            ?.setOnClickListener {
                bottomSheet.dismiss()
                showSnippetsDialog()
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_toggle_recording)
            ?.apply {
                text = if (tabManager.getActiveTab()?.sessionRecorder?.isRecording() == true) {
                    "Stop Recording"
                } else {
                    "Start Recording"
                }
                setOnClickListener {
                    bottomSheet.dismiss()
                    toggleRecording()
                }
            }

        // Session section.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cluster_broadcast)
            ?.setOnClickListener {
                bottomSheet.dismiss()
                showClusterBroadcastDialog()
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_port_forwarding)
            ?.setOnClickListener {
                bottomSheet.dismiss()
                openPortForwarding()
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_share_session)
            ?.setOnClickListener {
                bottomSheet.dismiss()
                shareSession()
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_close_tab)
            ?.setOnClickListener {
                bottomSheet.dismiss()
                closeCurrentTab()
            }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_disconnect_all)
            ?.setOnClickListener {
                bottomSheet.dismiss()
                disconnectAllTabs()
            }

        // Settings.
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_settings)
            ?.setOnClickListener {
                bottomSheet.dismiss()
                startActivity(Intent(this, SettingsActivity::class.java))
            }

        bottomSheet.setContentView(view)
        bottomSheet.show()
    }
    
    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                // Suppress during adapter swap — see isUpdatingAdapter.
                if (isUpdatingAdapter) return
                val position = tab.position
                tabManager.setActiveTab(position)
                // Guard: TabLayoutMediator fires onTabSelected in response to a
                // ViewPager swipe — the pager is already on this page. Calling
                // setCurrentItem here would restart the scroll animation and
                // re-fire onPageSelected, creating a feedback loop.
                // Only scroll programmatically when the pager isn't already there
                // (i.e. the user tapped the tab bar directly).
                if (viewPager?.currentItem != position) {
                    switchToTab(position)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }
    
    // Edge-zone swipe gate — evaluated at Activity dispatch level so it sees
    // EVERY touch event unconditionally. The previous implementation was a
    // RecyclerView.OnItemTouchListener on ViewPager2's inner RecyclerView,
    // but ViewPager2's RecyclerViewImpl.onInterceptTouchEvent short-circuits
    // as `isAllowedToScroll() && super.onInterceptTouchEvent(ev)` — so the
    // instant the gate set isUserInputEnabled=false on a mid-screen
    // ACTION_DOWN, the listener stopped receiving events entirely (including
    // the ACTION_UP that was supposed to reset the flag) and tab swiping
    // stayed permanently dead after the first mid-screen touch.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        applyEdgeSwipeGate(ev)
        return super.dispatchTouchEvent(ev)
    }

    // Per-gesture gate: on ACTION_DOWN decide whether THIS gesture may swipe
    // tabs — allowed when the finger goes down anywhere along the full height
    // of the left/right edge strip (strip width = tabSwipeEdgePx from the
    // tab_swipe_edge_dp preference; 0 = swipe from anywhere) — then toggle
    // ViewPager2.isUserInputEnabled accordingly and reset it on UP/CANCEL.
    private fun applyEdgeSwipeGate(ev: android.view.MotionEvent) {
        val pager = viewPager ?: return
        if (!swipeEnabled || pager.visibility != View.VISIBLE) return
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // Activity-dispatch coordinates are window-relative; convert
                // to pager-local via the pager's on-screen position so the
                // edge math is exact regardless of toolbar/tab-bar height.
                val loc = IntArray(2)
                pager.getLocationOnScreen(loc)
                val x = ev.rawX - loc[0]
                val y = ev.rawY - loc[1]
                val insidePager = x >= 0f && x <= pager.width && y >= 0f && y <= pager.height
                // VNC's pointer-forwarding touch model needs the same
                // carve-out SSH text-selection already has: every touch
                // inside a VNC page is potentially a remote click/drag,
                // never a page-turn gesture, so it must never be eaten
                // by a mid-page swipe — even when the SSH "swipe from
                // anywhere" preference (tab_swipe_edge_dp = 0) is
                // active. Force the same 96dp default edge-only width
                // used elsewhere for VNC tabs (and graphical-mode
                // hypervisor console tabs — step 6d); SSH tabs, and
                // text-mode console tabs, keep the user's configured
                // tabSwipeEdgePx (including 0) unchanged.
                // ConsoleTab.isGraphicalMode is read fresh on every
                // ACTION_DOWN (not cached), so a mode flip mid-session
                // (text console → graphical, or back) is picked up by
                // the very next touch.
                val activeTab = tabManager.getActiveTabSealed()
                val forceEdgeOnly = when (activeTab) {
                    is Tab.Vnc -> true
                    is Tab.Console -> activeTab.consoleTab.isGraphicalMode.value
                    else -> false
                }
                val effectiveEdgePx = if (forceEdgeOnly && tabSwipeEdgePx <= 0) {
                    (96 * resources.displayMetrics.density).toInt()
                } else {
                    tabSwipeEdgePx
                }
                val rejectedByEdgeZone = insidePager &&
                    !swipeSuspendedForSelection &&
                    effectiveEdgePx > 0 &&
                    !(x <= effectiveEdgePx || x >= pager.width - effectiveEdgePx)
                val allowed = when {
                    swipeSuspendedForSelection -> false
                    // Touch began outside the pager (toolbar, tab bar, key
                    // bar) — the pager never sees this gesture, so leave
                    // input enabled and start the next gesture clean.
                    !insidePager -> true
                    effectiveEdgePx <= 0 -> true
                    else -> !rejectedByEdgeZone
                }
                pager.isUserInputEnabled = allowed
                edgeGateDownX = ev.rawX
                edgeGateDownY = ev.rawY
                edgeGateRejectedForGesture = rejectedByEdgeZone
                edgeGateFeedbackFiredForGesture = false
                Logger.d("TabTerminalActivity", "edgeSwipeGate ACTION_DOWN — swipeSuspendedForSelection=$swipeSuspendedForSelection, isUserInputEnabled=$allowed")
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                // Fire the reject-feedback cue at most once per gesture,
                // and only once the touch has actually become a real
                // horizontal drag (not a tap or a vertical scroll) —
                // otherwise every rejected tap would glow needlessly.
                if (edgeGateRejectedForGesture && !edgeGateFeedbackFiredForGesture) {
                    val dx = ev.rawX - edgeGateDownX
                    val dy = ev.rawY - edgeGateDownY
                    val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                    if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        edgeGateFeedbackFiredForGesture = true
                        val loc = IntArray(2)
                        pager.getLocationOnScreen(loc)
                        showSwipeEdgeRejectionFeedback(edgeGateDownX - loc[0], pager.width)
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                // Reset to enabled so programmatic paging and the next
                // gesture's own DOWN check start from a known state —
                // unless a text selection is actively suspending swipe.
                if (!swipeSuspendedForSelection) {
                    pager.isUserInputEnabled = true
                } else {
                    Logger.d("TabTerminalActivity", "edgeSwipeGate ACTION_UP/CANCEL — swipe stays suspended (selection active)")
                }
            }
        }
    }

    // Haptic tick + a brief alpha-fade glow at the screen edge nearer the
    // rejected touch — fired once per gesture from applyEdgeSwipeGate() when
    // a mid-screen swipe is genuinely rejected by the edge-zone check (never
    // for selection-suspended swipes, which are a separate, intentional
    // state). Without this cue the rejection is silent and indistinguishable
    // from a bug. CLOCK_TICK is used (not REJECT) because REJECT requires
    // API 30+ and this app's minSdk is 21.
    private fun showSwipeEdgeRejectionFeedback(downX: Float, containerWidth: Int) {
        binding.viewPager.performHapticFeedback(
            android.view.HapticFeedbackConstants.CLOCK_TICK,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        val nearStart = downX <= containerWidth - downX
        val glow = if (nearStart) binding.swipeEdgeGlowStart else binding.swipeEdgeGlowEnd
        glow.animate().cancel()
        glow.alpha = 0f
        glow.animate()
            .alpha(1f)
            .setDuration(90)
            .withEndAction {
                glow.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .setStartDelay(60)
                    .start()
            }
            .start()
    }

    private fun setupTerminalView() {
        // Check if swipe is enabled
        swipeEnabled = app.preferencesManager.getBoolean("swipe_between_tabs", true)

        // Edge-zone width for tab swiping. 0 dp = swipe from anywhere on the
        // page (legacy). A positive value restricts the swipe start to a strip
        // of this width at each side. Default 96 dp (two Material touch
        // targets) — wide enough to hit reliably from either side without
        // requiring pixel-precise aim, while still leaving the page body free
        // for terminal gestures (vim/tmux navigation, text selection). A
        // rejected mid-screen swipe attempt now also surfaces haptic + visual
        // feedback (see showSwipeEdgeRejectionFeedback()) so the gate reads
        // as "by design" rather than "broken".
        val edgeDp = app.preferencesManager.getStringAsInt("tab_swipe_edge_dp", 96)
        tabSwipeEdgePx = if (edgeDp <= 0) 0 else (edgeDp * resources.displayMetrics.density).toInt()

        if (swipeEnabled) {
            // Use ViewPager2 for swipeable tabs
            viewPager = binding.viewPager
            binding.viewPager.visibility = View.VISIBLE
            binding.terminalView.visibility = View.GONE

            // ViewPager2 will be set up when tabs are created
            Logger.d("TabTerminalActivity", "Swipe between tabs enabled")
        } else {
            // Use single TerminalView (classic mode)
            terminalView = binding.terminalView
            binding.viewPager.visibility = View.GONE
            binding.terminalView.visibility = View.VISIBLE

            // Set up terminal view
            binding.terminalView.apply {
                // Load font from preferences
                val fontValue = app.preferencesManager.getString("terminal_font", "jetbrains_mono_nerd")
                setFont(fontValue)

                // Load font size from preferences
                val fontSize = app.preferencesManager.getInt("terminal_font_size", 14)
                setFontSize(fontSize)

                reverseScrollDirection = app.preferencesManager.isReverseScrollDirection()

                // URL detection is opt-in via preference; the long-press
                // context menu is ALWAYS available so users have a discoverable
                // way to copy/paste/select/send-text/change-font-size, matching
                // JuiceSSH's terminal long-press menu.
                val urlDetectionEnabled = app.preferencesManager.getBoolean("detect_urls", true)
                if (urlDetectionEnabled) {
                    onUrlDetected = { url ->
                        showUrlDialog(url)
                    }
                }
                // Long-press: show the terminal bottom-sheet menu for new tab,
                // tab list, session controls, and settings.
                val viewRef = this
                onContextMenuRequested = { _, _ ->
                    showTerminalMenu()
                }

                // Selection-mode entered ("Select Text…" + drag, or double-tap)
                // → raise the floating Copy ActionMode for this view.
                onSelectionStarted = {
                    startTerminalSelectionActionMode(viewRef)
                }

                // Tap-outside-to-dismiss clears local selection state without
                // going through the ActionMode's Cancel button — finish the
                // ActionMode here too, or swipe-suspend gets stuck permanently.
                onSelectionEnded = {
                    selectionActionMode?.finish()
                }

                // Issue #168 — edge-swipe tab switching. Acts as a backup
                // path for users who turned off ViewPager2 swipe-mode and
                // would otherwise have no touch gesture for tab switching.
                // Honors the same tab_swipe_edge_dp preference as pager
                // mode (0 = fling from anywhere along the terminal).
                // Direction: -1 = previous tab, +1 = next tab.
                edgeSwipeDp = edgeDp
                onEdgeSwipe = { direction ->
                    val count = tabManager.getTabCount()
                    if (count > 1) {
                        if (direction < 0) tabManager.switchToPreviousTab()
                        else               tabManager.switchToNextTab()
                        switchToTab(tabManager.getActiveTabIndex())
                    }
                }
                
                // Set up custom gesture support
                val gesturesEnabled = app.preferencesManager.getBoolean("enable_custom_gestures", false)
                if (gesturesEnabled) {
                    val multiplexerTypeStr = app.preferencesManager.getString("gesture_multiplexer_type", "tmux")
                    val multiplexerType = when (multiplexerTypeStr) {
                        "tmux" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.TMUX
                        "screen" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.SCREEN
                        "zellij" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.ZELLIJ
                        else -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.TMUX
                    }
                    
                    val customPrefix = app.preferencesManager.getMultiplexerPrefix(multiplexerTypeStr)
                    enableGestureSupport(multiplexerType, customPrefix)

                    // Set up command callback
                    onCommandSent = { command ->
                        // Send command to active terminal
                        tabManager.getActiveTab()?.let { tab ->
                            tab.termuxBridge.sendText(String(command, Charsets.UTF_8))
                            android.widget.Toast.makeText(
                                this@TabTerminalActivity,
                                "Gesture command sent",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                // Terminal view will be connected to active tab's terminal
            }

            Logger.d("TabTerminalActivity", "Single terminal view mode (swipe disabled)")
        }

        // Apply current theme to terminal
        applyCurrentTheme()
    }

    /**
     * Apply the current terminal theme to all terminal views
     */
    private fun applyCurrentTheme() {
        val themeId = app.preferencesManager.getTheme()
        var theme = app.themeManager.getThemeById(themeId) ?: BuiltInThemes.dracula()

        // `accessibility_high_contrast` (default off) overrides whatever
        // theme was selected with pure black/white. The user's choice still
        // wins for everything else (font, spacing, cursor) — only the
        // foreground/background pair is swapped. Cheap, predictable,
        // honest implementation that doesn't pretend to do AAA contrast
        // tuning.
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
        if (prefs.getBoolean("accessibility_high_contrast", false)) {
            theme = theme.copy(
                background = 0xFF000000.toInt(),
                foreground = 0xFFFFFFFF.toInt(),
                cursor = 0xFFFFFFFF.toInt(),
            )
        }

        Logger.d("TabTerminalActivity", "Applying theme: ${theme.name}" +
            if (prefs.getBoolean("accessibility_high_contrast", false)) " (high contrast)" else "")

        binding.terminalView.applyTheme(theme)
        pagerAdapter?.setTheme(theme)
    }

    /**
     * Show dialog for a detected terminal link. The dialog kind and its
     * actions depend on the link's scheme (see TerminalLinkClassifier) — the
     * user may have long-pressed by accident (terminals are touch-noisy), so
     * nothing is ever auto-launched.
     */
    private fun showUrlDialog(url: String) {
        if (isFinishing || isDestroyed) return
        when (val action = io.github.tabssh.utils.TerminalLinkClassifier.classify(url)) {
            is io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.Ssh ->
                showSshLinkDialog(action)
            is io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.Sftp ->
                showSftpLinkDialog(action)
            is io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.RemoteFile ->
                showRemoteFileDialog(action)
            is io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.ExternalScheme ->
                showExternalSchemeDialog(action)
            is io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.Browser ->
                showBrowserLinkDialog(action.url)
            is io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.NotALink ->
                // Non-allowlisted remote-supplied scheme (intent:, javascript:,
                // content:, …) — reject outright: no dialog, no Intent. Redact
                // before logging; the URL may carry userinfo.
                Logger.w("TabTerminalActivity", "Rejected non-allowlisted link scheme: ${Logger.urlForLogging(action.url)}")
        }
    }

    /**
     * http(s)/www. links — unchanged flow: opens in the device's browser.
     */
    private fun showBrowserLinkDialog(url: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_open_url_title)
            .setMessage(getString(R.string.terminal_open_url_message, url))
            .setPositiveButton(R.string.docker_option_open) { _, _ ->
                openUrl(url)
            }
            .setNeutralButton(R.string.copy) { _, _ ->
                copyToClipboard("URL", url)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * ssh:// links — parsed into user/host/port and handled entirely in-app:
     * Connect starts a new tab against a transient (unsaved) ConnectionProfile,
     * the same "quick connect" path MainActivity uses for ad-hoc connections.
     */
    private fun showSshLinkDialog(action: io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.Ssh) {
        val display = (action.username?.let { "$it@" } ?: "") + action.host +
            if (action.port != 22) ":${action.port}" else ""
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_ssh_link_title)
            .setMessage(getString(R.string.terminal_ssh_link_message, display))
            .setPositiveButton(R.string.connect_button) { _, _ ->
                connectSshLink(action)
            }
            .setNeutralButton(R.string.copy) { _, _ ->
                copyToClipboard("SSH link", action.url)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Starts a new session for a tapped ssh:// link via a transient (never
     * persisted) ConnectionProfile — mirrors MainActivity.quickConnect().
     * No stored password/key exists for it, so KEYBOARD_INTERACTIVE auth
     * makes connectToProfile() prompt for a password like any quick connect.
     */
    private fun connectSshLink(action: io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.Ssh) {
        val username = action.username?.takeIf { it.isNotBlank() }
            ?: app.preferencesManager.getDefaultUsername().trim().takeIf { it.isNotBlank() }
            ?: "root"
        val profile = io.github.tabssh.storage.database.entities.ConnectionProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = "$username@${action.host}",
            host = action.host,
            port = action.port,
            username = username,
            authType = AuthType.KEYBOARD_INTERACTIVE.name,
            keyId = null,
            groupId = null
        )
        val intent = createIntent(this, profile, autoConnect = true, forceNew = true)
        startActivity(intent)
        Logger.i("TabTerminalActivity", "Connecting SSH link: $username@${action.host}:${action.port}")
    }

    /**
     * sftp:// links tapped inside the terminal — same transient quick-connect
     * as ssh:// links, but the new session opens straight into the SFTP
     * browser at the link's path instead of landing on the terminal.
     */
    private fun showSftpLinkDialog(action: io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.Sftp) {
        val display = (action.username?.let { "$it@" } ?: "") + action.host +
            if (action.port != 22) ":${action.port}" else ""
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_sftp_link_title)
            .setMessage(getString(R.string.terminal_sftp_link_message, display, action.path))
            .setPositiveButton(R.string.connect_button) { _, _ ->
                connectSftpLink(action)
            }
            .setNeutralButton(R.string.copy) { _, _ ->
                copyToClipboard("SFTP link", action.url)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Starts a new session for a tapped sftp:// link via a transient (never
     * persisted) ConnectionProfile — same shape as connectSshLink(), plus
     * EXTRA_OPEN_SFTP_PATH so handleIntent() opens SFTPActivity once the
     * connection comes up instead of just landing on the terminal.
     */
    private fun connectSftpLink(action: io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.Sftp) {
        val username = action.username?.takeIf { it.isNotBlank() }
            ?: app.preferencesManager.getDefaultUsername().trim().takeIf { it.isNotBlank() }
            ?: "root"
        val profile = io.github.tabssh.storage.database.entities.ConnectionProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = "$username@${action.host}",
            host = action.host,
            port = action.port,
            username = username,
            authType = AuthType.KEYBOARD_INTERACTIVE.name,
            keyId = null,
            groupId = null
        )
        val intent = createIntent(this, profile, autoConnect = true, forceNew = true).apply {
            putExtra(EXTRA_OPEN_SFTP_PATH, action.path)
        }
        startActivity(intent)
        Logger.i("TabTerminalActivity", "Connecting SFTP link: $username@${action.host}:${action.port}${action.path}")
    }

    /**
     * file:// links — the path is on the remote host's filesystem, never
     * this device. "Open" (download + hand off to an external app) and
     * "Open in SFTP" only appear when the active tab has a live connection
     * to fetch the file over.
     */
    private fun showRemoteFileDialog(action: io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.RemoteFile) {
        val activeTab = tabManager.getActiveTab()
        val canBrowse = activeTab != null && activeTab.isConnected()
        val items = if (canBrowse) {
            arrayOf("Open", "Open in SFTP", "Copy path")
        } else {
            arrayOf("Copy path")
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_remote_file_title)
            .setMessage(getString(R.string.terminal_remote_file_message, action.path))
            .setItems(items) { _, which ->
                if (which < 0 || which >= items.size) return@setItems
                when (items[which]) {
                    "Open" -> openRemoteFileExternally(activeTab!!, action.path)
                    "Open in SFTP" -> openSftpAtPath(activeTab!!, action.path)
                    "Copy path" -> copyToClipboard("Path", action.path)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * file:// "Open" round trip for a file:// link tapped in the terminal —
     * downloads it over the active tab's connection to cacheDir/file-links/,
     * then hands it to an external app via FileProvider (RemoteFileOpener).
     * Opens an ad-hoc SFTPManager for the tab's SSH connection, mirroring
     * RemoteFileEditorActivity's in-app editor.
     */
    private fun openRemoteFileExternally(tab: SSHTab, path: String) {
        val ssh = app.sshSessionManager.getConnection(tab.profile.id)
        if (ssh == null) {
            android.widget.Toast.makeText(this, "Connection not active", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val sftp = io.github.tabssh.sftp.SFTPManager(ssh)
            val connected = withContext(Dispatchers.IO) { sftp.connect() }
            if (!connected) {
                android.widget.Toast.makeText(this@TabTerminalActivity, "SFTP failed to open", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            remoteFileOpener.open(sftp, path, path.substringAfterLast('/'))
        }
    }

    /**
     * Launches the SFTP browser for the active tab's connection, navigated
     * straight to the tapped file:// link's directory.
     */
    private fun openSftpAtPath(tab: SSHTab, path: String) {
        val startDir = path.substringBeforeLast('/', "/").ifEmpty { "/" }
        openSftpAt(tab, startDir)
    }

    /**
     * Launches the SFTP browser for [tab]'s connection at [dirPath] directly
     * (no filename-stripping — callers that already have a directory, such
     * as the sftp:// link handoff in handleIntent(), use this).
     */
    private fun openSftpAt(tab: SSHTab, dirPath: String) {
        val intent = Intent(this, io.github.tabssh.ui.activities.SFTPActivity::class.java).apply {
            putExtra(io.github.tabssh.ui.activities.SFTPActivity.EXTRA_CONNECTION_ID, tab.profile.id)
            putExtra(io.github.tabssh.ui.activities.SFTPActivity.EXTRA_INITIAL_REMOTE_PATH, dirPath)
        }
        startActivity(intent)
        Logger.d("TabTerminalActivity", "Opening SFTP at path: $dirPath")
    }

    /**
     * git:// ftp:// ftps:// svn:// links — "Open" is only offered when
     * resolveActivity() finds a handler for the scheme (Android 11+ package
     * visibility requires the matching <queries> entry in AndroidManifest.xml);
     * otherwise the dialog offers only Copy.
     */
    private fun showExternalSchemeDialog(action: io.github.tabssh.utils.TerminalLinkClassifier.LinkAction.ExternalScheme) {
        val url = action.url
        val hasHandler = try {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).resolveActivity(packageManager) != null
        } catch (e: Exception) {
            false
        }
        val message = if (hasHandler) {
            getString(R.string.terminal_open_link_handler_message, url, action.scheme)
        } else {
            getString(R.string.terminal_open_link_no_handler_message, url, action.scheme)
        }
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_open_link_title)
            .setMessage(message)
            .setNeutralButton(R.string.copy) { _, _ ->
                copyToClipboard("URL", url)
            }
            .setNegativeButton(R.string.cancel, null)
        if (hasHandler) {
            builder.setPositiveButton(R.string.docker_option_open) { _, _ ->
                openUrl(url)
            }
        }
        builder.show()
    }

    /**
     * Open URL in browser
     */
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
            Logger.d("TabTerminalActivity", "Opening URL: $url")
        } catch (e: Exception) {
            Logger.e("TabTerminalActivity", "Failed to open URL: $url", e)
            showError("Failed to open URL", "Error")
        }
    }

    /**
     * Copy arbitrary link text (URL, SSH link, or remote path) to clipboard.
     */
    private fun copyToClipboard(label: String, text: String) {
        // Terminal links aren't usually secret; mark non-sensitive so they
        // don't get hidden behind the system clipboard preview shield.
        io.github.tabssh.utils.ClipboardHelper.copy(this, label, text, sensitive = false)
        Toast.makeText(this, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
        // Log label + length only — the copied text may be a path or link
        // that reveals private infrastructure details, never the contents.
        Logger.d("TabTerminalActivity", "Copied $label to clipboard (${text.length} chars)")
    }

    /**
     * Show comprehensive SSH connection error dialog
     */
    private fun showSSHConnectionErrorDialog(
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile,
        errorInfo: io.github.tabssh.ssh.connection.SSHConnectionErrorInfo
    ) {
        // Bail if the activity is already gone — the connect coroutine can
        // resume after onDestroy/finish (back press during connect, or an
        // earlier error already called finish()), and Dialog.show() against
        // a dead window token throws BadTokenException. The notification
        // path still surfaces the error to the user.
        if (isFinishing || isDestroyed) {
            Logger.w("TabTerminalActivity", "Skipping SSH error dialog — activity already gone")
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_ssh_connection_error, null)
        
        // Populate connection details
        dialogView.findViewById<TextView>(R.id.text_connection_name)?.text = 
            "Connection: ${profile.name ?: profile.getDisplayName()}"
        dialogView.findViewById<TextView>(R.id.text_connection_host)?.text = 
            "Host: ${profile.host}:${profile.port}"
        dialogView.findViewById<TextView>(R.id.text_connection_username)?.text = 
            "Username: ${profile.username}"
        dialogView.findViewById<TextView>(R.id.text_auth_type)?.text = 
            "Auth: ${profile.authType}"
        
        // Set error message
        dialogView.findViewById<TextView>(R.id.text_error_message)?.text = errorInfo.userMessage
        
        // Set technical details
        val technicalDetails = dialogView.findViewById<TextView>(R.id.text_technical_details)
        technicalDetails?.text = errorInfo.technicalDetails
        
        // Set solutions
        val solutionsText = errorInfo.possibleSolutions.joinToString("\n")
        dialogView.findViewById<TextView>(R.id.text_solutions)?.text = solutionsText
        
        // Toggle technical details visibility
        val showTechnicalButton = dialogView.findViewById<TextView>(R.id.text_show_technical)
        showTechnicalButton?.setOnClickListener {
            if (technicalDetails?.visibility == View.GONE) {
                technicalDetails.visibility = View.VISIBLE
                showTechnicalButton.text = "▼ Hide Technical Details"
            } else {
                technicalDetails?.visibility = View.GONE
                showTechnicalButton.text = "▶ Show Technical Details"
            }
        }
        
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.terminal_ssh_failed_title, errorInfo.errorType))
            .setView(dialogView)
            .setOnCancelListener { finish() }
            .create()
        
        // Copy Error button
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_copy_error)
            ?.setOnClickListener {
                val fullError = buildString {
                    appendLine("=== SSH Connection Error ===")
                    appendLine()
                    appendLine("Connection: ${profile.name ?: profile.getDisplayName()}")
                    appendLine("Host: ${profile.host}:${profile.port}")
                    appendLine("Username: ${profile.username}")
                    appendLine("Auth: ${profile.authType}")
                    appendLine()
                    appendLine("Error Type: ${errorInfo.errorType}")
                    appendLine()
                    appendLine("Message:")
                    appendLine(errorInfo.userMessage)
                    appendLine()
                    appendLine("Technical Details:")
                    appendLine(errorInfo.technicalDetails)
                    appendLine()
                    appendLine("Possible Solutions:")
                    errorInfo.possibleSolutions.forEach {
                        appendLine(it)
                    }
                }
                
                io.github.tabssh.utils.ClipboardHelper.copy(this, "SSH Error", fullError, sensitive = false)
                Toast.makeText(this, "Error details copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        
        // Edit Connection button
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_edit_connection)
            ?.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, io.github.tabssh.ui.activities.ConnectionEditActivity::class.java).apply {
                    putExtra(io.github.tabssh.ui.activities.ConnectionEditActivity.EXTRA_CONNECTION_ID, profile.id)
                }
                startActivity(intent)
                finish() // Close TabTerminalActivity
            }
        
        // Retry button
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_retry)
            ?.setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    // forceNew=true: the failed tab is gone; always open a fresh session.
                    connectToProfile(profile, forceNew = true)
                }
            }
        
        // Close button
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_close)
            ?.setOnClickListener {
                dialog.dismiss()
                finish() // Close TabTerminalActivity
            }
        
        dialog.show()
    }
    
    /**
     * Cluster broadcast — prompts for a command, then sends it (with a
     * trailing newline so it actually executes) to every open SSH tab
     * after a confirm step. Distinct from ClusterCommandActivity, which
     * targets stored profiles rather than the currently-open tabs.
     */
    private fun showClusterBroadcastDialog() {
        val tabs = tabManager.getAllTabs()
        if (tabs.isEmpty()) {
            Toast.makeText(this, "No active sessions", Toast.LENGTH_SHORT).show()
            return
        }
        // setMessage and setView both occupy the dialog body — using both silently
        // drops the message. Count is in the title; hint on the field adds context.
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val input = io.github.tabssh.ui.dialogs.DialogFields.addText(
            form,
            hint = getString(R.string.cluster_broadcast_command_hint, tabs.size),
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            monospace = true
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.terminal_cluster_broadcast_title, tabs.size))
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.next) { _, _ ->
                val cmd = input.text.toString()
                if (cmd.isBlank()) {
                    Toast.makeText(this, "Empty command", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Confirm step — the user explicitly asked for it. Lists
                // the host names so a slip ("did I really pick prod?") is
                // catchable before the keys hit the wire.
                val hostList = tabs.joinToString("\n") { "• ${it.profile.getDisplayName()}" }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.terminal_confirm_broadcast_title)
                    .setMessage(
                        "Send to ${tabs.size} session(s)?\n\n" +
                        "Command: $cmd\n\n" +
                        hostList
                    )
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.terminal_send_to_all) { _, _ ->
                        val payload = (cmd + "\n").toByteArray(Charsets.UTF_8)
                        // Route via each tab's TermuxBridge so the write hits
                        // the writeScope (Dispatchers.IO) + writeLock funnel
                        // instead of the main thread. Writing directly to
                        // JSch's ChannelOutputStream here threw
                        // NetworkOnMainThreadException, the catch swallowed
                        // it silently, and the toast lied "Sent to N".
                        var sent = 0
                        tabs.forEach { tab ->
                            try {
                                tab.termuxBridge.write(payload)
                                sent++
                            } catch (e: Exception) {
                                Logger.w("TabTerminalActivity", "Cluster send to ${tab.profile.getDisplayName()} failed", e)
                            }
                        }
                        Toast.makeText(this, "Sent to $sent / ${tabs.size}", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .show()
    }

    /**
     * Find-in-scrollback: inflate the overlay bar on first use, then show it.
     * Subsequent calls just show the existing overlay (preserving any query).
     */
    private fun setupSearchOverlay() {
        val overlayRoot = binding.searchOverlay.root
        val tv = getActiveTerminalView() ?: terminalView ?: return
        searchController = ScrollbackSearchController(
            context         = this,
            overlayRoot     = overlayRoot,
            terminalView    = tv,
            scope           = lifecycleScope,
            activeTabProvider = { tabManager.getActiveTab() }
        )
    }

    private fun showSearchOverlay() {
        // Lazily initialise the controller the first time it is needed — at
        // onCreate() time there is no active TerminalView yet (tabs haven't been
        // created), so setupSearchOverlay() called from onCreate() always produces
        // a null controller. Re-attempt here against the live active view.
        if (searchController == null) setupSearchOverlay()
        val controller = searchController ?: run {
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show()
            return
        }
        controller.show()
    }

    /**
     * Copy the visible terminal screen + recent scrollback to clipboard.
     */
    private fun copyTerminalScreen() {
        val tab = tabManager.getActiveTab()
        if (tab == null) {
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show()
            return
        }
        val visible = try { tab.getTerminalContent() } catch (e: Exception) { "" }
        if (visible.isBlank()) {
            Toast.makeText(this, "Nothing on screen to copy", Toast.LENGTH_SHORT).show()
            return
        }
        io.github.tabssh.utils.ClipboardHelper.copy(this, "Terminal", visible, sensitive = false)
        Toast.makeText(this, "Terminal screen copied", Toast.LENGTH_SHORT).show()
    }

    /**
     * Drag-to-select range copy (issue #73). Driven from the long-press
     * context menu's "Select text…" item: enter selection mode on the
     * active TerminalView at the long-press point, then start a
     * floating ActionMode with a Copy button. Single shared
     * `selectionActionMode` reference so `exitSelection` can finish
     * the bar from any code path (tap-outside, tab switch, activity
     * teardown).
     */
    private var selectionActionMode: android.view.ActionMode? = null

    private fun beginSelection(x: Float, y: Float) {
        val view = getActiveTerminalView() ?: run {
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show()
            return
        }
        // beginWordSelectionAtTouch: expands to the word under the finger,
        // pre-grabs the focus handle so the user can drag immediately (the
        // long-press gesture leaves an active touch stream — without pre-grab
        // the first MOVE event would fall through to GestureDetector and scroll
        // instead of extending the selection). Also fires onSelectionStarted →
        // startTerminalSelectionActionMode. Do NOT call startTerminalSelectionActionMode
        // directly here — a second call finishes the ActionMode just created by
        // the callback, resetting selectionActive and leaving the bar broken.
        view.beginWordSelectionAtTouch(x, y)
    }

    private fun startTerminalSelectionActionMode(view: TerminalView) {
        // Dismiss any stale bar first — guards against the user
        // entering selection twice without finishing the previous one
        // (e.g. switching tabs while a bar is still up).
        selectionActionMode?.finish()

        val callback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: Menu): Boolean {
                mode.title = null
                menu.add(0, 1, 0, "Copy")
                    .setIcon(android.R.drawable.ic_menu_set_as)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, 2, 1, "Select All")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, 3, 2, "Paste")
                    .setIcon(android.R.drawable.ic_input_add)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, 4, 3, "Cancel")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> {
                        val text = view.getSelectedText()
                        if (text.isNullOrEmpty()) {
                            Toast.makeText(this@TabTerminalActivity, "Nothing selected", Toast.LENGTH_SHORT).show()
                        } else {
                            io.github.tabssh.utils.ClipboardHelper.copy(this@TabTerminalActivity, "Terminal selection", text, sensitive = false)
                            Toast.makeText(this@TabTerminalActivity, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        mode.finish()
                        return true
                    }
                    2 -> {
                        view.selectAll()
                        return true
                    }
                    3 -> {
                        // Paste directly to the captured `view` — do NOT use
                        // getActiveTerminalView() here. mode.finish() may trigger
                        // a RecyclerView relayout (and ACTION_MODE_FINISHED events),
                        // during which findViewHolderForAdapterPosition returns null,
                        // causing a silent no-op. `view` is captured in this closure
                        // and is always the correct TerminalView to paste into.
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        val text = clipboard.primaryClip?.getItemAt(0)
                            ?.coerceToText(this@TabTerminalActivity)?.toString()
                        if (text.isNullOrEmpty()) {
                            Toast.makeText(this@TabTerminalActivity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        } else {
                            view.pasteText(text)
                        }
                        mode.finish()
                        return true
                    }
                    4 -> {
                        mode.finish()
                        return true
                    }
                }
                return false
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                // Null out BEFORE exitSelectionMode() — it fires
                // onSelectionEnded, which calls selectionActionMode?.finish().
                // Nulling first makes that a no-op instead of a re-entrant
                // finish() on the mode currently being destroyed.
                if (selectionActionMode === mode) selectionActionMode = null
                view.exitSelectionMode()
                // Re-enable swipe now that text selection is done.
                swipeSuspendedForSelection = false
                viewPager?.isUserInputEnabled = true
                Logger.d("TabTerminalActivity", "onDestroyActionMode — swipeSuspendedForSelection=false, isUserInputEnabled=true")
            }
        }
        selectionActionMode = if (android.os.Build.VERSION.SDK_INT >= 23) {
            view.startActionMode(callback, android.view.ActionMode.TYPE_FLOATING)
        } else {
            view.startActionMode(callback)
        }
        if (selectionActionMode != null) {
            // Disable swipe while the user is selecting text so a horizontal
            // drag does not accidentally switch tabs mid-selection.
            swipeSuspendedForSelection = true
            viewPager?.isUserInputEnabled = false
            Logger.d("TabTerminalActivity", "startActionMode succeeded — swipeSuspendedForSelection=true, isUserInputEnabled=false")
        } else {
            // startActionMode() can transiently return null (e.g. window
            // losing focus mid-gesture). Without a live ActionMode there is
            // no onDestroyActionMode to ever flip swipeSuspendedForSelection
            // back — leaving it unset here would permanently disable swipe
            // for the rest of the activity's lifetime. Bail out of the
            // selection attempt instead.
            Logger.w("TabTerminalActivity", "startActionMode returned null — aborting selection, swipe stays enabled")
            view.exitSelectionMode()
        }
    }

    /**
     * Apply terminal-screen UI prefs that aren't covered by the global
     * Application-level lifecycle (which handles FLAG_SECURE and
     * keep-screen-on). Called once after the views are bound; cheap to
     * re-run via onResume if the prefs change while the user is in
     * Settings.
     */
    private fun applyTerminalUiPrefs() {
        val prefs = app.preferencesManager

        // Fullscreen mode: hide system bars while a tab is active. Uses
        // WindowInsetsController on API 30+, falls back to flag-based on
        // older versions. Android 16 (API 36) requires the modern API.
        if (prefs.getBoolean("ui_fullscreen_mode", false)) {
            try {
                val controller = androidx.core.view.WindowCompat
                    .getInsetsController(window, window.decorView)
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } catch (e: Exception) {
                Logger.w("TabTerminalActivity", "Fullscreen apply failed: ${e.message}")
            }
        } else {
            try {
                val controller = androidx.core.view.WindowCompat
                    .getInsetsController(window, window.decorView)
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } catch (e: Exception) {
                // best effort
            }
        }

        // Custom keyboard bar is always visible — it carries CTL/ALT/ESC/
        // arrows/symbols that the system IME does not provide and the user
        // expects in every terminal. The old `ui_show_function_keys` pref and
        // its hidden function-key row were removed entirely (redundant with
        // the custom keyboard). Manual show/hide is still available via
        // showCustomKeyboardBar / hideCustomKeyboardBar (toolbar action).

        // Line-spacing and scroll-direction — push to all bound terminal views
        // so every tab gets the current setting, not just the one visible at
        // the moment this method runs.
        val spacing = prefs.getStringAsInt("terminal_line_spacing", 120)
        val reversed = prefs.isReverseScrollDirection()
        try {
            val adapter = pagerAdapter
            if (adapter != null) {
                adapter.setLineSpacingPercent(spacing)
                adapter.setReverseScrollDirection(reversed)
            } else {
                // Classic (non-swipe) mode: one TerminalView in the layout.
                terminalView?.setLineSpacingPercent(spacing)
                terminalView?.let { it.reverseScrollDirection = reversed }
            }
        } catch (e: Exception) {
            Logger.w("TabTerminalActivity", "Terminal pref apply failed: ${e.message}")
        }

        // `terminal_word_wrap` (default ON) → DECAWM on the LOCAL emulator.
        // We inject the standard ANSI sequence straight into Termux's
        // processor — no traffic to the remote. OFF gives vt100 semantics:
        // cursor sits at the right edge for over-long lines (same as
        // `setterm -linewrap off` on Linux).
        val wrap = prefs.getBoolean("terminal_word_wrap", true)
        val seq = if (wrap) "\u001B[?7h".toByteArray() else "\u001B[?7l".toByteArray()
        try {
            tabManager.getAllTabs().forEach { it.termuxBridge.injectLocally(seq) }
        } catch (e: Exception) {
            Logger.w("TabTerminalActivity", "Word-wrap apply failed: ${e.message}")
        }

        // `accessibility_large_touch_targets` (default off): bump the
        // minimum tap target on every interactive child of the root view
        // from Material's 48dp to 64dp. Walks the view hierarchy once;
        // matters mainly for buttons/icons in the bottom toolbar and the
        // floating menu FAB.
        if (prefs.getBoolean("accessibility_large_touch_targets", false)) {
            val targetPx = (64 * resources.displayMetrics.density).toInt()
            applyLargeTouchTargets(binding.root, targetPx)
        }
    }

    private fun applyLargeTouchTargets(view: android.view.View, minPx: Int) {
        // Buttons / image buttons / FABs → bump min height + width.
        if (view is android.widget.Button ||
            view is android.widget.ImageButton ||
            view is com.google.android.material.floatingactionbutton.FloatingActionButton) {
            view.minimumHeight = minPx
            view.minimumWidth = minPx
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyLargeTouchTargets(view.getChildAt(i), minPx)
            }
        }
    }

    /**
     * Share current session info
     */
    private fun shareSession() {
        val currentTab = tabManager.getActiveTab()
        val profile = currentTab?.profile
        
        val shareText = buildString {
            append("TabSSH Session\n\n")
            profile?.let { p ->
                append("Host: ${p.host}\n")
                append("Port: ${p.port}\n")
                append("User: ${p.username}\n")
            }
        }
        
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        
        startActivity(Intent.createChooser(sendIntent, "Share session info"))
    }
    
    private fun setupPerformanceOverlay() {
        // Check if performance overlay is enabled in settings
        val showOverlay = app.preferencesManager.getBoolean("show_performance_overlay", false)
        
        if (showOverlay) {
            // Create overlay view
            performanceOverlay = io.github.tabssh.ui.views.PerformanceOverlayView(this)
            
            // Add to root view
            binding.root.addView(performanceOverlay)
            
            // Start updating metrics
            // lifecycleScope defaults to Main — withContext(Main) would be a redundant no-op.
            performanceUpdateJob = lifecycleScope.launch {
                app.performanceManager.performanceMetrics.collect { metrics ->
                    performanceOverlay?.updateMetrics(metrics)
                }
            }
            
            Logger.d("TabTerminalActivity", "Performance overlay enabled")
        }
    }
    
    private fun setupFunctionKeys() {
        // Bottom action bar
        binding.btnKeyboard.setOnClickListener { toggleKeyboard() }
        binding.btnSnippets.setOnClickListener { showSnippetsDialog() }
        binding.btnFiles.setOnClickListener { openFileManager() }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Issue #165 — Connections-tab "Active Sessions" tap. Look up the
        // running tab in the shared TabManager and switch to it. No new
        // connection is created; if the tab is gone (race with cleanup),
        // fall through to the normal connection-by-profile path below.
        // VNC-tab-swipe integration step 6c — VNC/console tabs are created
        // directly by their entry-point activities (VncHostsActivity etc.)
        // against the shared app-scoped TabManager, then focused here via
        // EXTRA_TAB_ID, same as the SSH "Active Sessions" path above. Must
        // search the sealed list, not the SSH-only getAllTabs().
        val tabId = intent.getStringExtra(EXTRA_TAB_ID)
        if (tabId != null) {
            val idx = tabManager.getAllTabsSealed().indexOfFirst { it.tabId == tabId }
            if (idx >= 0) {
                Logger.d("TabTerminalActivity", "Focusing tab $tabId at index $idx")
                tabManager.setActiveTab(idx)
                switchToTab(idx)
                return
            } else {
                Logger.w("TabTerminalActivity", "EXTRA_TAB_ID=$tabId not found; falling through")
            }
        }

        // Check for widget connection intent
        val widgetConnectionId = intent.getStringExtra("connection_id")
        if (widgetConnectionId != null) {
            Logger.d("TabTerminalActivity", "Handling widget connection: $widgetConnectionId")
            lifecycleScope.launch {
                val profile = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getConnectionById(widgetConnectionId)
                }
                if (profile != null) {
                    connectToProfile(profile)
                } else {
                    Logger.w("TabTerminalActivity", "Widget connection profile not found: $widgetConnectionId")
                    Toast.makeText(this@TabTerminalActivity, "Connection not found", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        // Handle normal connection intent
        val connectionProfileId = intent.getStringExtra(EXTRA_CONNECTION_PROFILE_ID)
        val connectionProfileJson = intent.getStringExtra(EXTRA_CONNECTION_PROFILE)
        val autoConnect = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, true)
        val forceNew = intent.getBooleanExtra(EXTRA_FORCE_NEW, false)

        Logger.d("TabTerminalActivity", "Intent extras: profileId=$connectionProfileId, autoConnect=$autoConnect, forceNew=$forceNew")

        if (connectionProfileId != null) {
            lifecycleScope.launch {
                // Always fetch from DB first to get latest changes (including identity)
                // Only fall back to embedded JSON for quick-connect (unsaved) profiles
                var profile: ConnectionProfile? = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getConnectionById(connectionProfileId)
                }

                // If not in DB, try embedded JSON (for quick-connect)
                if (profile == null && connectionProfileJson != null) {
                    try {
                        profile = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                            .decodeFromString(ConnectionProfile.serializer(), connectionProfileJson)
                        Logger.d("TabTerminalActivity", "Using embedded profile (quick-connect)")
                    } catch (e: Exception) {
                        Logger.w("TabTerminalActivity", "Failed to decode profile JSON", e)
                    }
                }

                if (profile != null) {
                    Logger.d("TabTerminalActivity", "Found profile: ${profile.name}, identityId: ${profile.identityId}")
                    if (autoConnect) {
                        connectToProfile(profile, forceNew = forceNew)
                        // sftp:// link handoff (see EXTRA_OPEN_SFTP_PATH) — only
                        // open SFTP if the connect above actually produced a
                        // live tab; a failed/cancelled connect leaves nothing
                        // to browse and the user already saw why.
                        val openSftpPath = intent.getStringExtra(EXTRA_OPEN_SFTP_PATH)
                        if (openSftpPath != null) {
                            val connectedTab = tabManager.getAllTabs()
                                .firstOrNull { it.profile.id == profile.id && it.isConnected() }
                            if (connectedTab != null) {
                                openSftpAt(connectedTab, openSftpPath)
                            } else {
                                Logger.w("TabTerminalActivity", "sftp:// link: connect did not produce a live tab, not opening SFTP")
                            }
                        }
                    } else {
                        // autoConnect=false means "surface the existing session" (e.g. notification tap).
                        // connectToProfile already handles the reattach short-circuit, but we must
                        // not open a NEW connection. Find a live tab and switch to it; if none
                        // exists the session is dead — close this activity so the user lands on
                        // MainActivity rather than a blank terminal screen.
                        val existing = tabManager.getAllTabs().firstOrNull {
                            it.profile.id == profile.id && it.isConnected()
                        }
                        if (existing != null) {
                            // Switch by tabId, not a position from the SSH-only
                            // getAllTabs() — that position is not a valid index
                            // into the unified list once VNC/console tabs exist.
                            Logger.i("TabTerminalActivity", "Surfacing live tab ${existing.tabId} for ${profile.name}")
                            if (tabManager.switchToTabById(existing.tabId)) {
                                val idx = tabManager.getAllTabsSealed().indexOfFirst { it.tabId == existing.tabId }
                                if (idx >= 0) switchToTab(idx)
                            }
                        } else {
                            Logger.w(
                                "TabTerminalActivity",
                                "Notification tap: no live tab for ${profile.name} — closing activity"
                            )
                            finish()
                        }
                    }
                } else {
                    Logger.e("TabTerminalActivity", "Profile not found for ID: $connectionProfileId")
                    Toast.makeText(this@TabTerminalActivity, "Connection profile not found", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Logger.w("TabTerminalActivity", "No connection profile ID in intent")
        }
    }
    
    private suspend fun connectToProfile(profile: ConnectionProfile, forceNew: Boolean = false) {
        try {
            // Reattach short-circuit: if the TabManager already has one or more live tabs
            // for this profile, surface one instead of dialing a new session.
            //
            // Skipped when forceNew=true — callers that explicitly open a NEW tab (the
            // connection selector, workspace restore, reconnect-after-close) set this so
            // the user always gets a fresh session rather than being bounced to an existing one.
            //
            // Disconnected stale tabs always fall through to the normal connect path.
            if (!forceNew) {
                val liveTabs = tabManager.getAllTabs().filter {
                    it.profile.id == profile.id && it.isConnected()
                }
                if (liveTabs.isNotEmpty()) {
                    Logger.i(
                        "TabTerminalActivity",
                        "Reattaching: ${liveTabs.size} live tab(s) for ${profile.getDisplayName()}"
                    )
                    if (liveTabs.size == 1) {
                        // Single live tab — surface it directly by tabId, not
                        // a position from the SSH-only getAllTabs() (not a
                        // valid unified-list index once VNC/console tabs exist).
                        val liveTabId = liveTabs[0].tabId
                        runOnUiThread {
                            if (tabManager.switchToTabById(liveTabId)) {
                                val idx = tabManager.getAllTabsSealed().indexOfFirst { it.tabId == liveTabId }
                                if (idx >= 0) switchToTab(idx)
                            }
                            if (!isRecreated) {
                                android.widget.Toast.makeText(
                                    this,
                                    "Reattached to ${profile.name}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        return
                    }
                    // Multiple live tabs for the same profile — ask the user which one to surface.
                    // "Open new" dismisses the dialog and falls through to create another session.
                    val chosen = kotlinx.coroutines.suspendCancellableCoroutine<SSHTab?> { cont ->
                        runOnUiThread {
                            val labels = liveTabs.mapIndexed { i, tab ->
                                "Session ${i + 1}: ${tab.getShortTitle()}"
                            }.toTypedArray()
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                                .setTitle(getString(R.string.terminal_multiple_sessions_title, profile.name))
                                .setItems(labels) { _, which -> cont.resumeWith(Result.success(liveTabs[which])) }
                                .setNegativeButton(R.string.terminal_open_new) { _, _ -> cont.resumeWith(Result.success(null)) }
                                .setOnCancelListener { cont.resumeWith(Result.success(null)) }
                                .show()
                        }
                    }
                    if (chosen != null) {
                        // Surface the chosen tab by id — see the single-live-tab
                        // branch above for why an index from getAllTabs() can't
                        // be used here.
                        val chosenTabId = chosen.tabId
                        runOnUiThread {
                            if (tabManager.switchToTabById(chosenTabId)) {
                                val idx = tabManager.getAllTabsSealed().indexOfFirst { it.tabId == chosenTabId }
                                if (idx >= 0) switchToTab(idx)
                            }
                        }
                        return
                    }
                    // User chose "Open new" — fall through to normal connect path below.
                }
            }

            Logger.i("TabTerminalActivity", "🚀 Starting connection to ${profile.getDisplayName()}")
            runOnUiThread {
                android.widget.Toast.makeText(this, "Connecting to ${profile.name}...", android.widget.Toast.LENGTH_SHORT).show()
            }

            // Resolve linked identity if set (for effective credentials)
            val linkedIdentity = if (profile.identityId != null) {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        app.database.identityDao().getIdentityById(profile.identityId!!)?.also {
                            Logger.i("TabTerminalActivity", "Using identity '${it.name}' for connection")
                        }
                    } catch (e: Exception) {
                        Logger.w("TabTerminalActivity", "Error loading linked identity", e)
                        null
                    }
                }
            } else null

            // Use effective credentials: identity overrides profile
            val effectiveUsername = linkedIdentity?.username?.takeIf { it.isNotBlank() } ?: profile.username
            val effectiveAuthType = linkedIdentity?.authType ?: AuthType.fromString(profile.authType)
            val effectiveKeyId = linkedIdentity?.keyId ?: profile.keyId

            // Check authentication requirements and prompt for password if needed
            val hasStoredPassword = withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Check identity password first, then profile password
                val identityPw = linkedIdentity?.let {
                    app.securePasswordManager.retrievePassword("identity_${it.id}") ?: it.password
                }
                identityPw != null || app.securePasswordManager.retrievePassword(profile.id) != null
            }

            // Check if SSH key is available when PUBLIC_KEY auth is configured
            val keyAvailable = if (effectiveKeyId != null) {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val keyExists = app.database.keyDao().getKeyById(effectiveKeyId) != null
                    val jschBytes = app.keyStorage.retrieveJSchBytes(effectiveKeyId)
                    val privateKey = if (jschBytes == null) {
                        app.keyStorage.retrievePrivateKey(effectiveKeyId)
                    } else null
                    keyExists && (jschBytes != null || privateKey != null)
                }
            } else false

            // Determine if we need to prompt for password:
            // 1. KEYBOARD_INTERACTIVE with no password and no key
            // 2. PUBLIC_KEY auth but key is not available (fallback to password)
            // 3. PASSWORD auth with no stored password
            val needPasswordPrompt = when {
                effectiveAuthType == AuthType.KEYBOARD_INTERACTIVE && !hasStoredPassword && !keyAvailable -> true
                effectiveAuthType == AuthType.PUBLIC_KEY && !keyAvailable && !hasStoredPassword -> {
                    Logger.w("TabTerminalActivity", "SSH key not available (keyId=$effectiveKeyId), falling back to password")
                    true
                }
                effectiveAuthType == AuthType.PASSWORD && !hasStoredPassword -> true
                else -> false
            }

            if (needPasswordPrompt) {
                val promptMessage = if (effectiveAuthType == AuthType.PUBLIC_KEY && !keyAvailable) {
                    "SSH key not found. Enter password for $effectiveUsername@${profile.host}"
                } else {
                    "Password required for $effectiveUsername@${profile.host}"
                }
                val enteredPassword = promptForPassword(promptMessage)
                if (enteredPassword == null) {
                    Logger.i("TabTerminalActivity", "User cancelled password prompt - closing activity")
                    Toast.makeText(this, "Connection cancelled", Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // Store password for the profile (SSHConnection will look it up)
                    app.securePasswordManager.storePassword(
                        profile.id, enteredPassword, SecurePasswordManager.StorageLevel.SESSION_ONLY
                    )
                }
            }

            // Wave 2.3 — Telnet branch (no auth, no JSch).
            if (profile.protocol.equals("telnet", ignoreCase = true)) {
                connectTelnetProfile(profile)
                return
            }

            // Create SSH connection
            Logger.d("TabTerminalActivity", "Creating SSH connection via SSHSessionManager")
            val sshConnection = app.sshSessionManager.connectToServer(profile)

            if (sshConnection != null) {
                Logger.i("TabTerminalActivity", "SSH connection established, creating tab")
                
                // Create new tab with the connection (using user's preferred cursor style)
                val cursorStyle = app.preferencesManager.getCursorStyleInt()
                val tab = tabManager.createTab(profile, cursorStyle, app.preferencesManager.getTranscriptRows())

                if (tab != null) {
                    Logger.d("TabTerminalActivity", "Tab created successfully: ${tab.tabId}")

                    // Auto-start recording if enabled
                    if (app.preferencesManager.getBoolean("auto_record_sessions", false)) {
                        Logger.d("TabTerminalActivity", "Starting session recording")
                        tab.sessionRecorder = io.github.tabssh.terminal.recording.SessionRecorder(
                            this,
                            profile.getDisplayName()
                        )
                        tab.sessionRecorder?.startRecording()
                        updateRecordingKeyIndicator()
                    }

                    // Yield to the main dispatcher so the Handler.post { addTabToUI() }
                    // queued by onTabCreated() runs before we call tab.connect(). Both
                    // are posted to the same main looper — onTabCreated's post was
                    // enqueued first, so it executes before our withContext(Main) body.
                    withContext(kotlinx.coroutines.Dispatchers.Main) {}

                    // Connect the tab's terminal to SSH streams.
                    // For mosh modes ("auto"/"on"): bootstrap mosh-server FIRST
                    // without opening a shell channel — avoids the SSH shell
                    // flashing lastlog on screen then getting wiped when
                    // mosh-client takes over. The mosh-server's own login shell
                    // is the only one; lastlog/MOTD prints normally through it.
                    // For "off" (or telnet): open the SSH shell directly.
                    Logger.i("TabTerminalActivity", "🔌 Connecting terminal to SSH streams...")
                    // Mosh cannot carry a RemoteCommand: mosh-server always starts a
                    // login shell, so handing off would silently drop the command and
                    // land the user in a plain shell (docker exec tabs, forced-command
                    // jails, SFTP-only accounts). Those profiles stay on the SSH channel.
                    val moshMode = if (!profile.remoteCommand.isNullOrBlank()) "off" else profile.moshMode
                    val binaryAvailable = io.github.tabssh.protocols.mosh.MoshNativeClient.resolveBinary(this) != null
                    val connected: Boolean
                    if (moshMode != "off" && binaryAvailable) {
                        // Mosh path: init connection tracking, bootstrap, then decide.
                        tab.initConnectionForMosh(sshConnection)
                        val moshCmd = profile.advancedSettings?.let { raw ->
                            try { org.json.JSONObject(raw).optString("moshServerCommand")
                                .takeIf { it.isNotBlank() } }
                            catch (_: Exception) { null }
                        }
                        showToast("Connecting… (trying Mosh)")
                        val handoff = io.github.tabssh.protocols.mosh.MoshHandoff.bootstrap(
                            sshConnection, profile.username, profile.host,
                            commandOverride = moshCmd
                        )
                        if (handoff is io.github.tabssh.protocols.mosh.MoshHandoff.Result.Success) {
                            val moshOk = tab.connectMosh(
                                this, handoff.info.host, handoff.info.port, handoff.info.keyBase64
                            )
                            if (moshOk) {
                                Logger.i("TabTerminalActivity", "✅ MOSH attached for ${profile.getDisplayName()}")
                                showToast("Mosh: ${profile.getDisplayName()}")
                                connected = true
                            } else {
                                // mosh-client process failed to start — fall back.
                                // The mosh-server we just bootstrapped now has no
                                // client and would linger on the host until its
                                // network timeout; reap it now while the SSH
                                // session is still open (best-effort).
                                Logger.w("TabTerminalActivity", "Mosh attach failed; falling back to SSH")
                                io.github.tabssh.protocols.mosh.MoshHandoff.reapServer(
                                    sshConnection, handoff.info.serverPid, handoff.info.port
                                )
                                connected = tab.connect(sshConnection)
                                showToast(if (moshMode == "on") "Mosh failed — using SSH" else "Connected to ${profile.getDisplayName()}")
                            }
                        } else {
                            val errMsg = (handoff as? io.github.tabssh.protocols.mosh.MoshHandoff.Result.Error)?.message
                            Logger.w("TabTerminalActivity", "Mosh bootstrap failed: $errMsg")
                            connected = tab.connect(sshConnection)
                            showToast(when {
                                moshMode == "on" -> "Mosh not available — using SSH"
                                else -> "Connected to ${profile.getDisplayName()}"
                            })
                        }
                    } else if (moshMode != "off" && !binaryAvailable) {
                        // Mosh mode requested but no native binary for this ABI.
                        // Open SSH shell; for "on" offer the server-side handoff.
                        connected = tab.connect(sshConnection)
                        if (connected && moshMode == "on") {
                            showToast("Connected to ${profile.getDisplayName()}")
                            runOnUiThread { showMoshHandoff() }
                        } else {
                            showToast("Connected to ${profile.getDisplayName()}")
                        }
                    } else {
                        // "off" mode — plain SSH shell.
                        connected = tab.connect(sshConnection)
                        showToast("Connected to ${profile.getDisplayName()}")
                    }
                    if (connected) {
                        Logger.i("TabTerminalActivity", "TERMINAL CONNECTED SUCCESSFULLY to ${profile.getDisplayName()}")

                        // Auto-switch to the newly connected tab so the user lands
                        // on it immediately. createTab() already advanced the
                        // TabManager's activeTabIndex; we just need to reflect that
                        // in the UI. Resolve the tab's position in the unified list
                        // by tabId rather than getActiveTabIndex() (in case another
                        // tab was added concurrently) or getAllTabs().indexOf(tab)
                        // (SSH-only list — not a valid unified-list index once
                        // VNC/console tabs exist).
                        val newIdx = tabManager.getAllTabsSealed().indexOfFirst { it.tabId == tab.tabId }
                        runOnUiThread {
                            if (newIdx >= 0) switchToTab(newIdx)
                        }

                        // Update connection statistics (count + timestamp)
                        try {
                            app.database.connectionDao().updateLastConnected(profile.id)
                            Logger.d("TabTerminalActivity", "Updated connection count for ${profile.getDisplayName()}")
                        } catch (e: Exception) {
                            Logger.e("TabTerminalActivity", "Failed to update connection stats", e)
                        }

                        // Observe non-fatal warnings (e.g. X11 server not found).
                        // Shown as a one-time Snackbar so as not to block the terminal.
                        // Cancel any prior collector before launching a new one — prevents
                        // N parallel collectors accumulating after N successful connects.
                        warningsJob?.cancel()
                        warningsJob = lifecycleScope.launch {
                            sshConnection.warnings.collect { message ->
                                runOnUiThread {
                                    val root = window.decorView.rootView
                                    Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
                                }
                            }
                        }

                        // Per-host status notification is owned by
                        // SSHConnectionService (one notification per active
                        // host, dynamic title). Don't post the legacy
                        // "Connected to … Logged in as root" toast-style one
                        // here — it'd duplicate the service-posted row.
                    } else {
                        Logger.e("TabTerminalActivity", "Failed to connect terminal to SSH for ${profile.getDisplayName()}")
                        showError("Failed to connect terminal", "Error")
                        
                        // Check for detailed error info
                        val errorInfo = sshConnection.detailedError.value
                        if (errorInfo != null) {
                            Logger.e("TabTerminalActivity", "Error details: ${errorInfo.userMessage}")
                            runOnUiThread {
                                showSSHConnectionErrorDialog(profile, errorInfo)
                            }
                            
                            // Show error notification
                            io.github.tabssh.utils.NotificationHelper.showConnectionError(
                                this,
                                profile.getDisplayName(),
                                errorInfo.userMessage
                            )
                        }
                    }
                } else {
                    // createTab() returns null only when the tab limit (ui_max_tabs)
                    // is reached. Give the user actionable feedback instead of a
                    // generic "Failed to create terminal tab" message.
                    val limit = tabManager.getTabCount()
                    Logger.e("TabTerminalActivity", "Tab limit reached ($limit tabs open) — cannot open ${profile.getDisplayName()}")
                    showError(
                        "Tab limit reached ($limit tabs open). Close a tab before opening a new one.",
                        "Cannot open tab"
                    )
                    // Tear down the SSH connection we just established — it can never
                    // be wired to a tab and would otherwise leak until process exit.
                    try { sshConnection.disconnect() } catch (_: Exception) {}
                    // No tab was created — no terminal to show; close the activity so
                    // the user isn't left on a blank screen with no way to recover.
                    finish()
                }
            } else {
                // Connection failed - try to get detailed error from last connection attempt
                Logger.e("TabTerminalActivity", "SSH connection returned NULL for ${profile.getDisplayName()}")

                // Get the connection that failed (it may still exist even though connect() returned null)
                val failedConnection = app.sshSessionManager.getConnection(profile.id)
                val errorInfo = failedConnection?.detailedError?.value

                if (errorInfo != null) {
                    runOnUiThread {
                        showSSHConnectionErrorDialog(profile, errorInfo)
                    }

                    // Show error notification
                    io.github.tabssh.utils.NotificationHelper.showConnectionError(
                        this,
                        profile.getDisplayName(),
                        errorInfo.userMessage
                    )
                } else {
                    // Fallback to simple toast if no detailed error available
                    showError("Connection failed: ssh ${profile.username}@${profile.host}:${profile.port}", "Error")

                    // Show generic error notification
                    io.github.tabssh.utils.NotificationHelper.showConnectionError(
                        this,
                        profile.getDisplayName(),
                        "Connection failed: ssh ${profile.username}@${profile.host}:${profile.port}"
                    )

                    // No tab, no session — close the activity so the user is not
                    // left on a blank screen.
                    Logger.i("TabTerminalActivity", "Closing activity due to connection failure")
                    finish()
                }
            }
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Activity is going away mid-connect. Make sure we don't leave a
            // half-authenticated SSH session orphan in SSHSessionManager —
            // closeConnection is idempotent. Re-throw cancellation so the
            // launching scope sees it correctly.
            Logger.i("TabTerminalActivity", "connectToProfile cancelled for ${profile.getDisplayName()} — closing any orphan SSH session")
            try { app.sshSessionManager.closeConnection(profile.id) } catch (_: Throwable) {}
            throw e
        } catch (e: Exception) {
            Logger.e("TabTerminalActivity", "Error connecting to ${profile.getDisplayName()}", e)

            // Try to create a detailed error info from the exception
            val errorInfo = io.github.tabssh.ssh.connection.SSHConnectionErrorInfo(
                errorType = "Connection Error",
                userMessage = e.message ?: "Unknown error occurred",
                technicalDetails = buildString {
                    appendLine("Exception: ${e.javaClass.simpleName}")
                    appendLine("Message: ${e.message}")
                    appendLine("\nStack Trace:")
                    appendLine(e.stackTraceToString())
                },
                possibleSolutions = listOf(
                    "• Try restarting the app",
                    "• Check connection settings",
                    "• Verify network connectivity",
                    "• Check app logs for more details"
                ),
                exception = e
            )
            
            runOnUiThread {
                showSSHConnectionErrorDialog(profile, errorInfo)
            }
            
            // Show error notification
            io.github.tabssh.utils.NotificationHelper.showConnectionError(
                this,
                profile.getDisplayName(),
                errorInfo.userMessage
            )
        }
    }
    
    /**
     * Wave 2.3 — Telnet connect path (no JSch, no auth).
     */
    private suspend fun connectTelnetProfile(profile: ConnectionProfile) {
        Logger.i("TabTerminalActivity", "Telnet connect to ${profile.getDisplayName()}")
        val telnet = io.github.tabssh.ssh.connection.TelnetConnection(profile.host, profile.port.takeIf { it > 0 } ?: 23)

        val cursorStyle = app.preferencesManager.getCursorStyleInt()
        val tab = tabManager.createTab(profile, cursorStyle, app.preferencesManager.getTranscriptRows())
        if (tab == null) {
            Logger.e("TabTerminalActivity", "Failed to create tab for ${profile.getDisplayName()}")
            showError("Failed to create terminal tab", "Error")
            finish()
            return
        }
        withContext(kotlinx.coroutines.Dispatchers.Main) {}

        val ok = tab.connect(telnet)
        if (ok) {
            showToast("Connected (telnet) to ${profile.getDisplayName()}")
            try {
                app.database.connectionDao().updateLastConnected(profile.id)
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Failed to update telnet connection stats", e)
            }
            // Per-host status notification is owned by SSHConnectionService
            // (also for telnet — the service tracks the wrapper state). No
            // legacy showConnectionSuccess here.
        } else {
            showError("Telnet connection to ${profile.host}:${profile.port} failed", "Connection Error")
            io.github.tabssh.utils.NotificationHelper.showConnectionError(
                this, profile.getDisplayName(), "Connection failed: telnet ${profile.host}:${profile.port.takeIf { it > 0 } ?: 23}"
            )
            finish()
        }
    }

    private fun addTabToUI(tab: SSHTab) {
        if (swipeEnabled) {
            // Rebuild ViewPager2 adapter with updated tabs
            updateViewPagerAdapter()
            Logger.d("TabTerminalActivity", "Added tab to ViewPager2: ${tab.profile.getDisplayName()}")
        } else {
            // Classic mode: add tab to TabLayout only
            val tabLayout = binding.tabLayout
            val newTab = tabLayout.newTab()

            newTab.text = tab.getShortTitle()
            newTab.tag = tab.tabId

            tabLayout.addTab(newTab)

            // Select the new tab
            newTab.select()

            // Attach the terminal to the view
            terminalView?.attachTerminalEmulator(tab.termuxBridge)

            Logger.d("TabTerminalActivity", "Added tab to UI: ${tab.profile.getDisplayName()}")
        }
    }

    /**
     * Update ViewPager2 adapter with current tabs
     */
    private fun updateViewPagerAdapter() {
        // Finish any in-flight text-selection ActionMode before the adapter
        // swap below recycles its anchor TerminalView — a detached
        // TYPE_FLOATING ActionMode is not guaranteed by the framework to
        // fire onDestroyActionMode, which would otherwise leave
        // swipeSuspendedForSelection stuck true forever (permanently
        // disabling tab-switch swipes until the activity is recreated).
        selectionActionMode?.finish()

        val allTabs = tabManager.getAllTabsSealed()

        // Get font preferences
        val fontSize = app.preferencesManager.getInt("terminal_font_size", 14)
        val fontValue = app.preferencesManager.getString("terminal_font", "jetbrains_mono_nerd")

        // Create URL detection callback if enabled
        val urlDetectionCallback = if (app.preferencesManager.getBoolean("detect_urls", true)) {
            { url: String -> showUrlDialog(url) }
        } else {
            null
        }
        
        // Get gesture settings
        val gesturesEnabled = app.preferencesManager.getBoolean("enable_custom_gestures", false)
        val multiplexerTypeStr = app.preferencesManager.getString("gesture_multiplexer_type", "tmux")
        val multiplexerType = when (multiplexerTypeStr) {
            "tmux" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.TMUX
            "screen" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.SCREEN
            "zellij" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.ZELLIJ
            else -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.TMUX
        }
        val customPrefix = app.preferencesManager.getMultiplexerPrefix(multiplexerTypeStr)

        // Create command send callback for gestures
        val commandCallback: ((ByteArray) -> Unit)? = if (gesturesEnabled) {
            { command ->
                tabManager.getActiveTab()?.let { tab ->
                    tab.termuxBridge.sendText(String(command, Charsets.UTF_8))
                    android.widget.Toast.makeText(
                        this,
                        "Gesture command sent",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            null
        }

        if (pagerAdapter == null) {
            // First time setup
            pagerAdapter = TerminalPagerAdapter(
                allTabs,
                fontSize,
                fontValue,
                urlDetectionCallback,
                gesturesEnabled,
                multiplexerType,
                customPrefix,
                commandCallback,
                onSelectionStarted = { tv -> startTerminalSelectionActionMode(tv) },
                onSelectionEnded = { selectionActionMode?.finish() },
                onContextMenuRequested = { _, _ -> showTerminalMenu() },
                reverseScrollDirection = app.preferencesManager.isReverseScrollDirection(),
                lineSpacingPercent = app.preferencesManager.getStringAsInt("terminal_line_spacing", 120)
            )
            // Suppress onPageSelected / onTabSelected while the adapter is
            // being installed. viewPager.adapter resets the pager to page 0,
            // then setCurrentItem scrolls to the target — both fire
            // onPageSelected asynchronously via the Choreographer layout pass,
            // AFTER this synchronous code returns. Registering the callback
            // here (while the flag is still true) and deferring the flag clear
            // via viewPager.post ensures those async events are still suppressed
            // when the layout frame fires.
            isUpdatingAdapter = true
            viewPager?.adapter = pagerAdapter

            // Edge-zone swipe gating is handled by the dispatchTouchEvent
            // override (applyEdgeSwipeGate) — nothing to install on the pager.

            // Jump to the tab that was active before this activity was created.
            val activeIdx = tabManager.getActiveTabIndex().coerceIn(0, (allTabs.size - 1).coerceAtLeast(0))
            viewPager?.setCurrentItem(activeIdx, false)

            // Setup TabLayoutMediator to sync TabLayout with ViewPager2
            tabLayoutMediator?.detach()
            tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                tab.text = allTabs.getOrNull(position)?.shortTitle() ?: "Tab ${position + 1}"
                tab.tag = allTabs.getOrNull(position)?.tabId
            }
            tabLayoutMediator?.attach()

            // Register page change callback while flag is still true so any
            // adapter-reset onPageSelected events (fired in the next layout
            // frame) are suppressed before the flag is cleared.
            viewPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    // Suppress during adapter swap — see isUpdatingAdapter.
                    if (isUpdatingAdapter) return
                    tabManager.switchToTab(position)
                    // Focus the newly visible page's input view so keyboard
                    // input (hardware + IME) works immediately after a swipe,
                    // without requiring an extra tap (console keyboard fix).
                    viewPager?.post { getActiveInputView()?.requestFocus() }
                    Logger.d("TabTerminalActivity", "Swiped to tab $position")
                }
            })

            // Defer the flag clear to after the Choreographer layout pass so
            // the async onPageSelected(0) from the adapter reset is still
            // caught by the guard above.
            viewPager?.post { isUpdatingAdapter = false }
        } else {
            // Recreate adapter with new tabs list. The existing onPageChangeCallback
            // is already registered; isUpdatingAdapter suppresses it during the
            // swap. Both viewPager.adapter (resets to page 0) and setCurrentItem
            // fire onPageSelected asynchronously via the Choreographer layout pass —
            // defer the flag clear via viewPager.post so those async events are still
            // suppressed when the layout frame fires.
            isUpdatingAdapter = true
            pagerAdapter = TerminalPagerAdapter(
                allTabs,
                fontSize,
                fontValue,
                urlDetectionCallback,
                gesturesEnabled,
                multiplexerType,
                customPrefix,
                commandCallback,
                onSelectionStarted = { tv -> startTerminalSelectionActionMode(tv) },
                onSelectionEnded = { selectionActionMode?.finish() },
                onContextMenuRequested = { _, _ -> showTerminalMenu() },
                reverseScrollDirection = app.preferencesManager.isReverseScrollDirection(),
                lineSpacingPercent = app.preferencesManager.getStringAsInt("terminal_line_spacing", 120)
            )
            viewPager?.adapter = pagerAdapter

            // Navigate to wherever TabManager says is active (covers both the
            // "new tab added" and "tab removed" cases correctly).
            val targetPosition = tabManager.getActiveTabIndex().coerceIn(0, (allTabs.size - 1).coerceAtLeast(0))
            viewPager?.setCurrentItem(targetPosition, false)

            // Re-attach mediator
            tabLayoutMediator?.detach()
            tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                tab.text = allTabs.getOrNull(position)?.shortTitle() ?: "Tab ${position + 1}"
                tab.tag = allTabs.getOrNull(position)?.tabId
            }
            tabLayoutMediator?.attach()

            // Defer the flag clear to after the layout pass — same reasoning as
            // the first-time setup path above.
            viewPager?.post { isUpdatingAdapter = false }
        }

        // Push the current theme into the freshly-created adapter so pages
        // are rendered with the correct colors from the first onBindViewHolder
        // call. Without this, pagerAdapter.currentTheme stays null (the default)
        // and each terminal page shows the default theme regardless of what the
        // user has selected. applyCurrentTheme() is idempotent — calling it here
        // costs nothing and guarantees correctness whether this is first setup or
        // a tab-count change triggered recreation.
        applyCurrentTheme()
    }

    private fun removeTabFromUI(index: Int) {
        if (swipeEnabled) {
            // Rebuild ViewPager2 adapter
            updateViewPagerAdapter()
        } else {
            // Classic mode: remove from TabLayout
            val tabLayout = binding.tabLayout
            if (index < tabLayout.tabCount) {
                tabLayout.removeTabAt(index)
            }
        }
    }

    /**
     * Observe [tab]'s multiplexer StateFlow and update the PREFIX key visual
     * state in real time. Cancels any previous observer so only one tab's state
     * drives the keyboard bar at a time.
     *
     * - Green + full alpha: multiplexer attached → prefix sends correctly
     * - Dim + still clickable: no multiplexer → click shows type picker
     *
     * RepeatOnLifecycleWrongUsage is suppressed deliberately: the check
     * assumes repeat-per-call leaks duplicate collectors, but this observer
     * is keyed to the active tab (not the view), must restart on every tab
     * switch, and cancels the previous job before launching — exactly one
     * collector exists at any time.
     */
    @android.annotation.SuppressLint("RepeatOnLifecycleWrongUsage")
    private fun observeMultiplexerState(tab: io.github.tabssh.ui.tabs.SSHTab?) {
        multiplexerObserverJob?.cancel()
        // The picker's item lambda captures the tab it was built for, so a
        // dialog left over from the previous tab would drive a session the
        // user is no longer looking at.
        dismissMultiplexerDialogs()
        if (tab == null) {
            updatePrefixKeyVisual(null)
            return
        }
        multiplexerObserverJob = lifecycleScope.launch {
            // STARTED-scoped: a request that lands while the activity is
            // backgrounded must not show() on a stopped window; the StateFlow
            // replays it on return to the foreground.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    tab.activeMultiplexerTypeFlow.collect { type ->
                        updatePrefixKeyVisual(type)
                    }
                }
                // ASK-mode picker (IDEA.md feature 21): the tab publishes a
                // pending request after connect; show the attach/create dialog
                // when this tab is the one being observed. StateFlow retention
                // means a request raised while another tab was active still
                // surfaces on switch-back.
                launch {
                    tab.multiplexerAskRequestFlow.collect { req ->
                        if (req != null) showMultiplexerAskDialog(tab, req)
                    }
                }
            }
        }
    }

    // Tracks the visible ASK-mode picker so a re-emission (tab switch,
    // config change re-collect) never stacks a second dialog.
    private var multiplexerAskDialog: androidx.appcompat.app.AlertDialog? = null

    // The follow-up name prompt is tracked for the same reason, and because
    // both must be torn down before the window they are attached to.
    private var multiplexerCreateDialog: androidx.appcompat.app.AlertDialog? = null

    /** Tears down any visible ASK-mode dialog; safe to call repeatedly. */
    private fun dismissMultiplexerDialogs() {
        multiplexerCreateDialog?.dismiss()
        multiplexerCreateDialog = null
        multiplexerAskDialog?.dismiss()
        multiplexerAskDialog = null
    }

    /**
     * ASK-mode multiplexer picker: lists the remote's existing sessions with
     * an attach action each, plus "Create new session". Cancel/skip leaves
     * the plain shell (the tab clears its request so the dialog won't recur).
     */
    private fun showMultiplexerAskDialog(
        tab: io.github.tabssh.ui.tabs.SSHTab,
        req: io.github.tabssh.ui.tabs.SSHTab.MultiplexerAskRequest
    ) {
        if (multiplexerAskDialog?.isShowing == true || multiplexerCreateDialog?.isShowing == true) return
        val labels = req.sessions.map { "Attach: $it" } + "Create new session"
        multiplexerAskDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.terminal_multiplexer_sessions_title, req.type, tab.profile.host))
            .setItems(labels.toTypedArray()) { _, which ->
                // setItems auto-dismisses, so drop the reference before the
                // create prompt checks the stacking guard.
                multiplexerAskDialog = null
                if (which < req.sessions.size) {
                    tab.attachMultiplexerSession(req.type, req.sessions[which])
                } else {
                    showMultiplexerCreateDialog(tab, req)
                }
            }
            .setNegativeButton(R.string.terminal_skip) { _, _ -> tab.dismissMultiplexerAsk() }
            .setOnCancelListener { tab.dismissMultiplexerAsk() }
            .show()
    }

    /**
     * Name prompt for the ASK-mode "Create new session" choice, prefilled
     * with the profile's default session name.
     */
    private fun showMultiplexerCreateDialog(
        tab: io.github.tabssh.ui.tabs.SSHTab,
        req: io.github.tabssh.ui.tabs.SSHTab.MultiplexerAskRequest
    ) {
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val input = io.github.tabssh.ui.dialogs.DialogFields.addText(
            form,
            hint = getString(R.string.multiplexer_session_name_hint),
            initial = req.defaultSessionName
        )
        input.setSelection(input.text?.length ?: 0)
        multiplexerCreateDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.terminal_new_multiplexer_session_title, req.type))
            .setMessage(R.string.terminal_new_multiplexer_session_message)
            .setView(form.root)
            .setPositiveButton(R.string.docker_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !name.contains(' ')) {
                    tab.createMultiplexerSession(req.type, name)
                } else {
                    // Re-prompt rather than clearing the request: a typo must not
                    // strand the user on a plain shell with no way back to the picker.
                    Toast.makeText(
                        this, "Session name must be non-empty with no spaces",
                        Toast.LENGTH_SHORT
                    ).show()
                    multiplexerCreateDialog = null
                    showMultiplexerCreateDialog(tab, req)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> tab.dismissMultiplexerAsk() }
            .setOnCancelListener { tab.dismissMultiplexerAsk() }
            .show()
    }

    private fun updatePrefixKeyVisual(multiplexerType: String?) {
        // Four distinct visual states:
        //   0. Disabled (picker's per-connection "Disable PRE key" row) → forced heavy dim,
        //      regardless of armed/mux state — the key stays tappable/long-pressable
        //      (tap logs a no-op, long-press still opens the re-enable picker) but
        //      must *look* inert so a disabled key never appears to be live.
        //   1. Armed (prefixArmed = true)     → solid green fill — latch ready to fire
        //   2. Mux detected (type != null)    → green outline only — mux present, not armed
        //   3. No mux                         → default grey — nothing to send
        // The active=true solid fill is reserved for the armed state only so the user
        // can tell the difference between "mux running" and "about to send prefix".
        val MUX_GREEN = androidx.core.content.ContextCompat.getColor(this, R.color.status_success)
        val activeTab = tabManager.getActiveTab()
        when {
            activeTab != null && !activeTab.isPrefixKeyEnabled ->
                binding.multiRowKeyboard.setKeyState(
                    "PREFIX", active = false, enabled = true, dimmed = true
                )
            prefixArmed ->
                binding.multiRowKeyboard.setKeyState("PREFIX", active = true, enabled = true)
            multiplexerType != null ->
                binding.multiRowKeyboard.setKeyState(
                    "PREFIX", active = false, enabled = true, accentColor = MUX_GREEN
                )
            else ->
                binding.multiRowKeyboard.setKeyState("PREFIX", active = false, enabled = true)
        }
        // Label is always "PRE"; brackets indicate the latch is armed.
        val label = if (prefixArmed) "[PRE]" else "PRE"
        binding.multiRowKeyboard.setKeyLabel("PREFIX", label)
    }

    /**
     * Convert a prefix notation string to a compact key label.
     * "C-b" → "^B", "C-Space" → "^Sp", "M-b" → "M-b", literals pass through.
     */
    private fun prefixToShortLabel(notation: String): String {
        val t = notation.trim()
        return when {
            t.matches(Regex("^(C-|\\^|Ctrl[-+])([a-zA-Z])$", RegexOption.IGNORE_CASE)) ->
                "^${t.last().uppercaseChar()}"
            t.matches(Regex("^(C-|\\^|Ctrl[-+])Space$", RegexOption.IGNORE_CASE)) ->
                "^Sp"
            t.matches(Regex("^(M-|Alt[-+])([a-zA-Z])$", RegexOption.IGNORE_CASE)) ->
                "M-${t.last()}"
            t.length <= 4 -> t
            else -> t.take(4)
        }
    }

    /**
     * Show the PRE-key picker (long-press on the PREFIX key). A picked type
     * takes effect immediately AND is persisted as a per-connection override
     * (`ConnectionProfile.multiplexerOverride`) so it survives reconnects and
     * app restarts. Precedence: override > live detection > global default.
     * "Auto (detect)" clears the override and hands control back to the
     * detection loop. There is no global on/off toggle — PRE key
     * enablement is per-connection only: the last row disables the PRE key
     * for this connection and, once disabled, toggles in place back to
     * "Enable PRE key" so long-press remains the only way to re-enable it.
     */
    private fun showMultiplexerPickerDialog() {
        val prefs = app.preferencesManager
        val tab = tabManager.getActiveTab() ?: return
        val tmuxLabel   = prefixToShortLabel(prefs.getMultiplexerPrefix("tmux"))
        val zellijLabel = prefixToShortLabel(prefs.getMultiplexerPrefix("zellij"))
        val screenLabel = prefixToShortLabel(prefs.getMultiplexerPrefix("screen"))
        val isEnabled = tab.isPrefixKeyEnabled
        val types = arrayOf(
            getString(R.string.terminal_pre_key_picker_auto),
            getString(R.string.terminal_pre_key_picker_tmux, tmuxLabel),
            getString(R.string.terminal_pre_key_picker_zellij, zellijLabel),
            getString(R.string.terminal_pre_key_picker_screen, screenLabel),
            if (isEnabled) getString(R.string.terminal_pre_key_picker_disable)
            else getString(R.string.terminal_pre_key_picker_enable)
        )
        val keys = arrayOf("auto", "tmux", "zellij", "screen", "toggle_off")
        // setMessage and setItems both occupy the dialog body — using both silently
        // hides the item list. Move the hint into the title so the list renders.
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_pre_key_multiplexer_title)
            .setItems(types) { _, which ->
                // "auto" means no override — stored as NULL. The last row is
                // "off" when currently enabled, or back to "auto" (re-enable)
                // when currently disabled — it never writes a literal "off"
                // while already off.
                val override = when (keys[which]) {
                    "auto" -> null
                    "toggle_off" -> if (isEnabled) "off" else null
                    else -> keys[which]
                }
                tab.applyMultiplexerOverride(override)
                persistMultiplexerOverride(tab.profile.id, override)
                if (keys[which] == "toggle_off" && override == "off" && prefixArmed) {
                    prefixArmed = false
                    prefixArmedType = null
                    getActiveTerminalView()?.let { tv ->
                        tv.setPendingPrefix(null)
                        tv.onPrefixConsumed = null
                    }
                }
                updatePrefixKeyVisual(tab.activeMultiplexerType)
                Logger.i(
                    "TabTerminalActivity",
                    "Multiplexer override set to ${override ?: "auto"} for ${tab.profile.getDisplayName()}"
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Persist the per-connection PRE-key override picked in the long-press
     * dialog. Re-reads the row before writing so a concurrent edit in
     * ConnectionEditActivity isn't clobbered with the tab's stale in-memory
     * profile snapshot.
     */
    private fun persistMultiplexerOverride(connectionId: String, override: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dao = app.database.connectionDao()
                val fresh = dao.getConnectionById(connectionId) ?: return@launch
                dao.updateConnection(fresh.copy(multiplexerOverride = override))
            } catch (e: Exception) {
                Logger.w("TabTerminalActivity", "Failed to persist multiplexer override: ${e.message}")
            }
        }
    }

    private fun switchToTab(index: Int) {
        if (swipeEnabled) {
            // Use smooth=false so programmatic jumps (tab-bar tap, keyboard shortcut,
            // onActiveTabChanged) never trigger an animation that can re-fire
            // onPageSelected. User swipes already have their own smooth animation
            // handled by ViewPager2 internally — no need for us to add one.
            viewPager?.setCurrentItem(index, false)
        } else {
            // Classic mode: attach terminal to single view
            val tab = tabManager.getTab(index)
            if (tab != null) {
                // Connect terminal view to the tab's terminal emulator
                val terminal = tab.termuxBridge
                terminalView?.attachTerminalEmulator(terminal)

                // Deactivate previous tab
                tabManager.getActiveTab()?.deactivate()

                // Activate new tab
                tab.activate()

                // Update toolbar title
                supportActionBar?.title = tab.getDisplayTitle()

                Logger.d("TabTerminalActivity", "Switched to tab: ${tab.profile.getDisplayName()}")
            }
        }
        // Disarm any pending PREFIX latch — the latch belongs to the previous tab's
        // terminal session and must not bleed into the new tab.
        if (prefixArmed) {
            prefixArmed = false
            prefixArmedType = null
            getActiveTerminalView()?.let { tv ->
                tv.setPendingPrefix(null)
                tv.onPrefixConsumed = null
            }
        }
        // Mirror the new tab's multiplexer detection state to the PREFIX key.
        observeMultiplexerState(tabManager.getTab(index))
        // Recording is tracked per-tab — sync the keyboard's stop-recording
        // key to whatever the newly active tab's actual state is.
        updateRecordingKeyIndicator()
        // Give the newly active tab's input view focus so hardware key events
        // and the IME attach to it immediately, without requiring a tap first
        // (Issue: console keyboard input silently dropped after a tab switch).
        getActiveInputView()?.requestFocus()
    }
    
    private fun updateTabIcon(tab: SSHTab, state: ConnectionState) {
        val tabLayout = binding.tabLayout
        
        // Find the tab by ID
        for (i in 0 until tabLayout.tabCount) {
            val tabLayoutTab = tabLayout.getTabAt(i)
            if (tabLayoutTab?.tag == tab.tabId) {
                // Update tab appearance based on connection state
                when (state) {
                    ConnectionState.CONNECTED -> {
                        // Green indicator or checkmark
                        tabLayoutTab.setIcon(R.drawable.ic_connected)
                    }
                    ConnectionState.CONNECTING -> {
                        // Orange/yellow indicator
                        tabLayoutTab.setIcon(R.drawable.ic_connecting)
                    }
                    ConnectionState.ERROR -> {
                        // Red error indicator
                        tabLayoutTab.setIcon(R.drawable.ic_error)
                    }
                    ConnectionState.DISCONNECTED -> {
                        // Gray or no icon
                        tabLayoutTab.icon = null

                        // Wave 1.1 — instead of auto-closing the tab after 2s,
                        // show a Reconnect / Close dialog so the user can resume
                        // the session in one tap. Common case: SSH timeout, server
                        // restart, brief network blip — auto-close was destroying
                        // the user's tab + scrollback unnecessarily.
                        //
                        // Refinement: if the remote shell reported a clean exit
                        // (status 0 from `exit`/`logout`) just close the tab —
                        // the user explicitly ended the session, so prompting
                        // them to reconnect is friction. The reconnect dialog
                        // is for *unexpected* disconnects (status -1 = no
                        // exit-status message, or non-zero = abnormal exit).
                        // SSH connection exit status takes precedence.
                        // For mosh/telnet/standalone (connection == null) fall back
                        // to the PTY session exit code captured in TermuxBridge.
                        // Exit 0 = user explicitly exited; non-zero = unexpected.
                        val exitStatus = tab.connection?.getShellExitStatus()
                            ?: tab.termuxBridge.moshLastExitCode
                        Logger.i("TabTerminalActivity",
                            "Tab ${tab.tabId} disconnected (exit=$exitStatus)")
                        runOnUiThread {
                            if (exitStatus == 0) {
                                // closeTabById fires onTabClosed which calls finish()
                                // when tab count reaches 0 — don't call finish()
                                // here too or we get a double-finish race.
                                tabManager.closeTabById(tab.tabId)
                            } else {
                                showReconnectDialog(tab)
                            }
                        }
                    }
                    else -> {}
                }
                break
            }
        }
    }
    
    /**
     * Wave 1.1 — when a tab's SSH session ends (server-side exit, timeout,
     * network blip), keep the tab and show a Reconnect / Close dialog
     * instead of auto-destroying it. The user's scrollback stays visible
     * behind the dialog so they can read the last output before deciding.
     *
     * When mosh fails fast (UDP blocked — "nothing received from server on port"),
     * a neutral "Try SSH instead" button is offered so the user can fall back
     * without editing the profile.
     */
    private fun showReconnectDialog(tab: SSHTab) {
        if (isFinishing || isDestroyed) return

        val tabId = tab.tabId
        val profile = tab.profile
        // Mosh "nothing received from server" exits quickly with a non-zero code.
        // Offer SSH fallback only when mosh was actually attempted and failed fast.
        val isMoshFastFail = tab.termuxBridge.moshFailedFast && profile.moshMode != "off"

        // Silent mosh→SSH fallback: when mosh exits fast (UDP blocked by carrier,
        // host firewall, etc.) and we haven't already fallen back for this profile,
        // transparently reconnect over plain SSH. Per the fallback-chain policy,
        // intermediate failures are logged at INFO and never surfaced as errors —
        // the user only sees a dialog if SSH *also* fails.
        if (isMoshFastFail && !moshFallbackAttempted.contains(profile.id)) {
            moshFallbackAttempted.add(profile.id)
            Logger.i("TabTerminalActivity",
                "Mosh failed fast for tab $tabId — silently falling back to SSH (UDP likely blocked)")
            isReconnecting = true
            tabManager.closeTabById(tabId)
            lifecycleScope.launch {
                try {
                    connectToProfile(profile.copy(moshMode = "off"), forceNew = true)
                } finally {
                    isReconnecting = false
                    if (!isFinishing && !isDestroyed && tabManager.getTabCount() == 0) {
                        Logger.i("TabTerminalActivity",
                            "Silent SSH fallback produced no tabs — finishing activity")
                        finish()
                    }
                }
            }
            return
        }

        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_connection_closed_title)
            .setMessage(getString(R.string.terminal_disconnected_diagnosing, profile.getDisplayName()))
            .setCancelable(false)
            .setPositiveButton(R.string.terminal_reconnect) { _, _ ->
                Logger.i("TabTerminalActivity", "User chose RECONNECT for tab $tabId")
                // Close the dead tab object then start a fresh connect to the
                // same profile — keeps the slot in the tab strip. The
                // `isReconnecting` flag prevents `onTabClosed` from
                // auto-finishing this activity in the brief window between
                // closeTab and the new tab being created. Cleared in the
                // coroutine's finally{} so a failed reconnect still lets
                // the activity finish if no tabs remain.
                isReconnecting = true
                tabManager.closeTabById(tabId)
                lifecycleScope.launch {
                    try {
                        // forceNew=true: old tab was just closed; always open a fresh session.
                        connectToProfile(profile, forceNew = true)
                    } finally {
                        isReconnecting = false
                        // If the reconnect failed (no new tab landed) and
                        // we're still alive, walk out the same as a normal
                        // close-on-empty would.
                        if (!isFinishing && !isDestroyed && tabManager.getTabCount() == 0) {
                            Logger.i("TabTerminalActivity", "Reconnect produced no tabs — finishing activity")
                            finish()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.terminal_close_tab) { _, _ ->
                Logger.i("TabTerminalActivity", "User chose CLOSE for tab $tabId")
                if (tabManager.closeTabById(tabId) != null) {
                    if (tabManager.getTabCount() == 0) finish()
                }
            }

        if (isMoshFastFail) {
            // UDP is likely blocked by the carrier. Offer a one-shot SSH reconnect
            // without permanently changing the saved profile.
            builder.setNeutralButton(R.string.terminal_try_ssh_instead) { _, _ ->
                Logger.i("TabTerminalActivity", "User chose SSH FALLBACK for mosh tab $tabId")
                isReconnecting = true
                tabManager.closeTabById(tabId)
                lifecycleScope.launch {
                    try {
                        connectToProfile(profile.copy(moshMode = "off"), forceNew = true)
                    } finally {
                        isReconnecting = false
                        if (!isFinishing && !isDestroyed && tabManager.getTabCount() == 0) {
                            Logger.i("TabTerminalActivity", "SSH fallback produced no tabs — finishing activity")
                            finish()
                        }
                    }
                }
            }
        }

        val dialog = builder.show()

        // Run the reachability ladder off the main thread and rewrite the
        // dialog body with a specific reason (DNS / host down / SSH down /
        // mosh UDP blocked) instead of leaving the user with the generic
        // socket error JSch surfaced. The dialog stays interactive while
        // the probe runs — worst case it finishes ~4 s later.
        lifecycleScope.launch {
            val result = ConnectionDiagnostic.diagnose(
                host = profile.host,
                sshPort = profile.port,
                moshFailedFast = isMoshFastFail,
            )
            if (!isFinishing && !isDestroyed && dialog.isShowing) {
                dialog.setMessage(getString(R.string.terminal_disconnected_detail, profile.getDisplayName(), result.userMessage))
            }
        }
    }

    // Per-tab connectionState observers for Vnc/Console tabs, keyed by tabId.
    // The SSH equivalent lives in TabManager (SSH-typed listener); these are
    // activity-scoped so they die with the UI that would show the dialog.
    private val consoleGateObservers = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * VNC/SPICE close policy — mirrors the SSH exit-code gate in
     * [updateTabIcon]: a session that ended cleanly (orderly server EOF /
     * native SPICE disconnect) closes its tab like `exit` status 0; an
     * abrupt drop (reset, mid-stream EOF, protocol or native error) shows a
     * reconnect dialog. Each Vnc/Console tab's connectionState is observed
     * with the same has-been-connected gating TabManager applies to SSH
     * tabs, so a freshly created (still DISCONNECTED) tab is never treated
     * as a dead session.
     */
    private fun wireConsoleClosePolicy() {
        lifecycleScope.launch {
            tabManager.allTabsFlow.collect { tabs ->
                // Drop observers for tabs that no longer exist.
                val live = tabs.map { it.tabId }.toSet()
                (consoleGateObservers.keys - live).forEach { id ->
                    consoleGateObservers.remove(id)?.cancel()
                }
                tabs.forEach { tab ->
                    if (tab.tabId in consoleGateObservers) return@forEach
                    val stateFlow = when (tab) {
                        is Tab.Ssh -> return@forEach
                        is Tab.Vnc -> tab.vncTab.connectionState
                        is Tab.Console -> tab.consoleTab.connectionState
                    }
                    consoleGateObservers[tab.tabId] = lifecycleScope.launch {
                        var hasBeenConnected = false
                        stateFlow.collect { state ->
                            if (state == ConnectionState.CONNECTED) {
                                hasBeenConnected = true
                                // Live graphical session → make sure the session
                                // service is running so the per-tab "Connected"
                                // notification (with its Disconnect action) is
                                // posted — the SSH path starts it from
                                // SSHSessionManager, which VNC/SPICE never touch.
                                io.github.tabssh.services.SSHConnectionService.startService(applicationContext)
                            } else if (state == ConnectionState.DISCONNECTED && hasBeenConnected) {
                                hasBeenConnected = false
                                handleConsoleTabDisconnected(tab)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * DISCONNECTED-after-CONNECTED handler for a Vnc/Console tab. The reason
     * recorded by the client hooks (RfbClient/SpiceClient.onSessionEnded via
     * VncTab/ConsoleTab) discriminates: CLEAN → auto-close like exit 0;
     * ERROR → reconnect dialog. A null reason means the state flipped without
     * the hooks firing — either a user-initiated close (tab already removed;
     * the existence check below drops it) or a text-console/manager-driven
     * disconnect, which is unexpected from the user's view → dialog.
     */
    private fun handleConsoleTabDisconnected(tab: Tab) {
        val (reason, detail) = when (tab) {
            is Tab.Ssh -> return
            is Tab.Vnc -> Pair(tab.vncTab.lastDisconnectReason, tab.vncTab.lastDisconnectMessage)
            is Tab.Console -> Pair(tab.consoleTab.lastDisconnectReason, tab.consoleTab.lastDisconnectMessage)
        }
        Logger.i("TabTerminalActivity",
            "Console tab ${tab.tabId} disconnected (reason=$reason, detail=$detail)")
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            // User-initiated closes also pass DISCONNECTED on their way out —
            // by the time this runs, closeTab has already removed the tab, so
            // a missing tab means the user ended the session: do nothing.
            if (tabManager.getAllTabsSealed().none { it.tabId == tab.tabId }) return@runOnUiThread
            if (reason == ConsoleDisconnectReason.CLEAN) {
                // Clean server EOF — the session ended normally (e.g. the VM
                // shut down or the server closed it): close like exit 0.
                closeConsoleTab(tab.tabId)
            } else {
                showConsoleReconnectDialog(tab, detail)
            }
        }
    }

    /**
     * Close a Vnc/Console tab by id. UI work (pager rebuild, finish when
     * empty) happens in the [TabManagerListener.onGraphicalTabClosed]
     * callback that [TabManager.closeTab] fires — the same callback path a
     * notification-shade Disconnect takes, so both routes stay consistent.
     */
    private fun closeConsoleTab(tabId: String) {
        tabManager.closeTabByIdSealed(tabId)
    }

    /**
     * Reconnect / Close dialog for a Vnc/Console tab that dropped with an
     * error — the console counterpart of [showReconnectDialog]. Reconnect is
     * only offered for persisted-VncHost tabs: ephemeral hypervisor consoles
     * need live authenticated API clients/tickets held by their manager
     * activities (and libvirt SPICE needs its SSH port forward re-created),
     * so those tabs get Close / Keep instead — the frozen framebuffer stays
     * visible so the user can read the last screen before deciding.
     */
    private fun showConsoleReconnectDialog(tab: Tab, detail: String?) {
        val title = when (tab) {
            is Tab.Ssh -> return
            is Tab.Vnc -> tab.vncTab.getDisplayTitle()
            is Tab.Console -> tab.consoleTab.getDisplayTitle()
        }
        val reconnectableHost = (tab as? Tab.Vnc)?.vncTab?.vncHost
        val body = buildString {
            append(title).append(" disconnected.")
            if (!detail.isNullOrBlank()) append("\n\n").append(detail)
        }
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_connection_closed_title)
            .setMessage(body)
            .setCancelable(false)
            .setNegativeButton(R.string.terminal_close_tab) { _, _ ->
                Logger.i("TabTerminalActivity", "User chose CLOSE for console tab ${tab.tabId}")
                closeConsoleTab(tab.tabId)
            }
            .setNeutralButton(R.string.terminal_keep_open) { d, _ -> d.dismiss() }
        if (reconnectableHost != null) {
            builder.setPositiveButton(R.string.terminal_reconnect) { _, _ ->
                Logger.i("TabTerminalActivity", "User chose RECONNECT for VNC tab ${tab.tabId}")
                reconnectVncTab((tab as Tab.Vnc).vncTab)
            }
        }
        builder.show()
    }

    /**
     * In-place reconnect for a persisted-VncHost [io.github.tabssh.ui.tabs.VncTab]:
     * resolve credentials the same way VncHostsActivity.openVncConsole does,
     * dial a fresh RfbClient via VncDirectConnector, swap it onto the tab
     * (the rfbClient setter rewires the close-policy hook), and rebind the
     * page so VncViewHolder attaches + starts the new client.
     */
    private fun reconnectVncTab(vncTab: io.github.tabssh.ui.tabs.VncTab) {
        val host = vncTab.vncHost ?: return
        lifecycleScope.launch {
            try {
                val (password, username) = withContext(Dispatchers.IO) {
                    val identityId = host.identityId
                    // Per-host password override always takes priority.
                    val hostPw = try {
                        app.securePasswordManager.retrievePassword("vnc_host_${host.id}")
                    } catch (e: Exception) {
                        Logger.w("TabTerminalActivity", "Could not retrieve VNC host password: ${e.message}")
                        null
                    }
                    if (hostPw != null) {
                        val identityUsername = if (identityId != null) {
                            app.database.vncIdentityDao().getById(identityId)?.username
                        } else null
                        Pair(hostPw, identityUsername)
                    } else if (identityId != null) {
                        val identity = app.database.vncIdentityDao().getById(identityId)
                        val pw = try {
                            app.securePasswordManager.retrievePassword("vnc_identity_$identityId")
                        } catch (e: Exception) {
                            Logger.w("TabTerminalActivity", "Could not retrieve VNC identity password: ${e.message}")
                            null
                        }
                        Pair(pw, identity?.username)
                    } else {
                        Pair(null, null)
                    }
                }
                val (rfbClient, _) = withContext(Dispatchers.IO) {
                    io.github.tabssh.hypervisor.vnc.VncDirectConnector.connect(
                        host, password, username, this@TabTerminalActivity)
                }
                // Drop whatever dead session is still parked under this tab's
                // key so the rebind below can't reclaim the old client.
                io.github.tabssh.hypervisor.vnc.VncBackgroundSessionStore.discard(vncTab.storeKey)
                vncTab.rfbClient = rfbClient
                vncTab.setConnectionState(ConnectionState.CONNECTED)
                withContext(Dispatchers.IO) {
                    app.database.vncHostDao().updateLastConnected(host.id, System.currentTimeMillis())
                }
                val idx = tabManager.getAllTabsSealed().indexOfFirst { it.tabId == vncTab.tabId }
                if (idx >= 0) pagerAdapter?.notifyItemChanged(idx)
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "VNC reconnect failed for '${host.name}'", e)
                if (!isFinishing && !isDestroyed) {
                    showToast("Reconnect failed: ${e.message}")
                    // Put the decision back in front of the user.
                    showConsoleReconnectDialog(
                        Tab.Vnc(vncTab), e.message ?: "Connection failed")
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            R.id.action_new_tab -> {
                // Open connection selector for new tab
                showConnectionSelector()
                true
            }
            R.id.action_close_tab -> {
                closeCurrentTab()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_disconnect_all -> {
                disconnectAllTabs()
                true
            }
            R.id.action_toggle_recording -> {
                toggleRecording(item)
                true
            }
            R.id.action_port_forwarding -> {
                openPortForwarding()
                true
            }
            R.id.action_view_transcripts -> {
                startActivity(Intent(this, TranscriptViewerActivity::class.java))
                true
            }
            R.id.action_command_palette -> { showCommandPalette(); true }
            R.id.action_quick_switcher -> { showQuickSwitcher(); true }
            R.id.action_broadcast_input -> { showBroadcastTargetsDialog(); true }
            R.id.action_save_workspace -> { showSaveWorkspaceDialog(); true }
            R.id.action_open_workspace -> { showOpenWorkspaceDialog(); true }
            R.id.action_history_palette -> { showHistoryPalette(); true }
            R.id.action_split_bottom -> { showSplitConnectionPicker(); true }
            R.id.action_unsplit -> { closeSplitPane(); true }
            R.id.action_mosh_handoff -> { showMoshHandoff(); true }
            R.id.action_macro_record -> { toggleMacroRecording(); true }
            R.id.action_macro_replay -> { showMacroPicker(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /**
     * Wave 2.10 — Per-tab cache of remote shell history. Lazy-fetched on
     * first Ctrl+R for that tab so we don't pay the round-trip until the
     * user actually wants the palette.
     */
    private val historyCache = mutableMapOf<String, List<String>>()

    private fun showHistoryPalette() {
        val active = tabManager.getActiveTab()
        if (active == null) {
            Toast.makeText(this, "No active tab", Toast.LENGTH_SHORT).show()
            return
        }
        val ssh = active.connection
        if (ssh == null) {
            Toast.makeText(this, "Not an SSH tab — history needs a live SSH session", Toast.LENGTH_SHORT).show()
            return
        }
        val cached = historyCache[active.tabId]
        if (cached != null) {
            showHistoryDialog(cached)
            return
        }
        Toast.makeText(this, "Fetching history…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val hist = io.github.tabssh.ssh.HistoryFetcher(ssh).fetch()
            historyCache[active.tabId] = hist
            runOnUiThread {
                if (hist.isEmpty()) {
                    Toast.makeText(this@TabTerminalActivity, "No history found (or files unreadable)", Toast.LENGTH_LONG).show()
                } else {
                    showHistoryDialog(hist)
                }
            }
        }
    }

    private fun showHistoryDialog(history: List<String>) {
        val items = history.map { line ->
            io.github.tabssh.ui.views.PaletteDialog.Item(
                title = line,
                subtitle = null
            ) { getActiveTerminalView()?.sendText(line) }
        }
        io.github.tabssh.ui.views.PaletteDialog.show(this, "Remote history (${history.size})", items)
    }

    /**
     * Wave 2.7 — Broadcast input across tabs.
     *
     * The active tab keeps typing as normal; in addition, every keystroke is
     * fanned out to the SSH outputStream of each selected target tab.
     * Implementation: we mutate `termuxBridge.broadcastTargets` on the ACTIVE
     * tab to the list of (peer-tab outputStreams). When the user switches
     * tabs we don't currently re-thread the targets — they stay attached to
     * whichever tab was active when the dialog committed. That's the simple
     * intended semantic: "I'm typing here, mirror to those".
     */
    private val broadcastTargetIds = mutableSetOf<String>()

    private fun showBroadcastTargetsDialog() {
        val tabs = tabManager.getAllTabs()
        if (tabs.size < 2) {
            Toast.makeText(this, "Open at least 2 tabs first", Toast.LENGTH_SHORT).show()
            return
        }
        val active = tabManager.getActiveTab()
        val others = tabs.filter { it.tabId != active?.tabId }
        val labels = others.map { it.profile.getDisplayName() }.toTypedArray()
        val checked = BooleanArray(others.size) { i -> broadcastTargetIds.contains(others[i].tabId) }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_broadcast_input_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val tabId = others[which].tabId
                if (isChecked) broadcastTargetIds.add(tabId) else broadcastTargetIds.remove(tabId)
            }
            .setPositiveButton(R.string.terminal_apply) { _, _ -> applyBroadcastTargets() }
            .setNeutralButton(R.string.terminal_stop_broadcasting) { _, _ ->
                broadcastTargetIds.clear()
                applyBroadcastTargets()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyBroadcastTargets() {
        val active = tabManager.getActiveTab() ?: return
        val targetStreams = tabManager.getAllTabs()
            .filter { broadcastTargetIds.contains(it.tabId) && it.tabId != active.tabId }
            .mapNotNull { it.termuxBridge.peerOutputStream() }
        active.termuxBridge.broadcastTargets = targetStreams
        // Clear targets on every other tab so we don't accidentally double-broadcast.
        tabManager.getAllTabs().filter { it.tabId != active.tabId }
            .forEach { it.termuxBridge.broadcastTargets = emptyList() }
        if (targetStreams.isEmpty()) {
            Toast.makeText(this, "Broadcast off", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Broadcasting to ${targetStreams.size} tab(s)", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Wave 2.5 — Save the currently open tabs (their connection IDs, in tab
     * order) as a named workspace. Reopening the workspace later will fan
     * out connectToProfile to each one in sequence with a small inter-open
     * delay so we don't slam every host at once.
     */
    private fun showSaveWorkspaceDialog() {
        val tabs = tabManager.getAllTabs()
        if (tabs.isEmpty()) {
            Toast.makeText(this, "No open tabs", Toast.LENGTH_SHORT).show()
            return
        }
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val edit = io.github.tabssh.ui.dialogs.DialogFields.addText(
            form, hint = getString(R.string.save_workspace_name_hint)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(resources.getQuantityString(R.plurals.terminal_save_workspace_title, tabs.size, tabs.size))
            .setView(form.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = edit.text.toString().trim().ifBlank { "Workspace ${System.currentTimeMillis() / 1000}" }
                val ids = tabs.map { it.profile.id }
                val json = org.json.JSONArray(ids).toString()
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.workspaceDao().upsert(
                                io.github.tabssh.storage.database.entities.Workspace(
                                    name = name,
                                    connectionIdsJson = json
                                )
                            )
                        }
                        runOnUiThread {
                            Toast.makeText(this@TabTerminalActivity, "Saved workspace '$name'", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Logger.e("TabTerminalActivity", "Save workspace failed", e)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOpenWorkspaceDialog() {
        lifecycleScope.launch {
            val all = try {
                withContext(Dispatchers.IO) { app.database.workspaceDao().getAll() }
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Load workspaces failed", e)
                emptyList()
            }
            runOnUiThread {
                if (all.isEmpty()) {
                    Toast.makeText(this@TabTerminalActivity, "No workspaces saved", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = all.map { ws ->
                    val n = try { org.json.JSONArray(ws.connectionIdsJson).length() } catch (_: Exception) { 0 }
                    "${ws.name} ($n tab${if (n == 1) "" else "s"})"
                }.toTypedArray()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TabTerminalActivity)
                    .setTitle(R.string.terminal_open_workspace_title)
                    .setItems(labels) { _, which -> openWorkspace(all[which]) }
                    .setNeutralButton(R.string.terminal_delete_workspace_action) { _, _ -> showDeleteWorkspaceDialog(all) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun openWorkspace(ws: io.github.tabssh.storage.database.entities.Workspace) {
        val ids: List<String> = try {
            val arr = org.json.JSONArray(ws.connectionIdsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            Logger.e("TabTerminalActivity", "Workspace ${ws.id} has malformed connection list", e)
            emptyList()
        }
        if (ids.isEmpty()) {
            Toast.makeText(this, "Workspace is empty", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            var opened = 0
            var skipped = 0
            for (id in ids) {
                val profile = try { app.database.connectionDao().getConnectionById(id) } catch (_: Exception) { null }
                if (profile == null) { skipped++; continue }
                // forceNew=true: workspace restore always opens a fresh tab per entry.
                connectToProfile(profile, forceNew = true)
                opened++
                kotlinx.coroutines.delay(400) // gentle stagger
            }
            runOnUiThread {
                Toast.makeText(
                    this@TabTerminalActivity,
                    "Opened $opened${if (skipped > 0) " (skipped $skipped missing)" else ""}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showDeleteWorkspaceDialog(all: List<io.github.tabssh.storage.database.entities.Workspace>) {
        val labels = all.map { it.name }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_delete_workspace_title)
            .setItems(labels) { _, which ->
                val ws = all[which]
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.workspaceDao().delete(ws)
                            // H6 — record the deletion so it propagates and is not resurrected.
                            TombstoneRecorder.record(app, TombstoneRecorder.WORKSPACE, ws.id)
                        }
                        runOnUiThread {
                            Toast.makeText(this@TabTerminalActivity, "Deleted '${ws.name}'", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Logger.e("TabTerminalActivity", "Delete workspace failed", e)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Wave 2.8 — Minimal split view. One bottom pane per tab; the pane is its
     * own SSHTab (NOT in tabManager) anchored to the activity. Tap a pane to
     * focus; getActiveTerminalView() routes keystrokes accordingly. Closing
     * the pane disconnects its SSH session and hides the layout slot.
     *
     * What this is NOT: nested splits, horizontal split, multi-pane grids,
     * pane resize. The use case is "tail logs in the bottom while typing
     * commands in the top" on a phone — anything richer is a tablet
     * problem and not in scope yet.
     */
    private var splitTab: SSHTab? = null
    private var bottomTerminalView: TerminalView? = null
    private var bottomPaneFocused: Boolean = false

    /**
     * Tracks the coroutine that collects SSH non-fatal warnings for the most
     * recently connected tab. Cancelled and replaced on every new connect so
     * only one collector is alive at a time — prevents N parallel collectors
     * accumulating after N successful connects within a single Activity instance.
     */
    private var warningsJob: Job? = null

    private fun showSplitConnectionPicker() {
        if (splitTab != null) {
            Toast.makeText(this, "Already split — close the bottom pane first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val recent = try {
                withContext(Dispatchers.IO) { app.database.connectionDao().getFrequentlyUsedConnections(20) }
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Recent fetch failed for split picker", e)
                emptyList()
            }
            runOnUiThread {
                if (recent.isEmpty()) {
                    Toast.makeText(this@TabTerminalActivity, "No saved connections", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = recent.map { it.getDisplayName() }.toTypedArray()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TabTerminalActivity)
                    .setTitle(R.string.terminal_split_bottom_pane_title)
                    .setItems(labels) { _, which -> openSplitWithProfile(recent[which]) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun openSplitWithProfile(profile: ConnectionProfile) {
        val pane = findViewById<android.widget.FrameLayout>(R.id.split_bottom_pane)
        val term = findViewById<TerminalView>(R.id.split_bottom_terminal)
        bottomTerminalView = term
        pane.visibility = View.VISIBLE
        // Tap-to-focus indicator: simple border swap.
        pane.setOnClickListener { setBottomPaneFocused(true) }
        term.setOnClickListener { setBottomPaneFocused(true) }
        // Tap on top FrameLayout (parent of viewPager / classic terminalView) to refocus top.
        findViewById<View>(R.id.view_pager)?.setOnClickListener { setBottomPaneFocused(false) }
        terminalView?.setOnClickListener { setBottomPaneFocused(false) }

        lifecycleScope.launch {
            val ssh = if (profile.protocol.equals("telnet", ignoreCase = true)) null
                else app.sshSessionManager.connectToServer(profile)
            // Telnet branch (separate path)
            if (profile.protocol.equals("telnet", ignoreCase = true)) {
                val telnet = io.github.tabssh.ssh.connection.TelnetConnection(profile.host, profile.port.takeIf { it > 0 } ?: 23)
                val newTab = SSHTab(
                    profile,
                    io.github.tabssh.terminal.TermuxBridge(
                        transcriptRows = app.preferencesManager.getTranscriptRows(),
                        cursorStyle = app.preferencesManager.getCursorStyleInt()
                    )
                )
                term.attachTerminalEmulator(newTab.termuxBridge)
                try {
                    kotlinx.coroutines.delay(150)
                    if (newTab.connect(telnet)) {
                        splitTab = newTab
                        runOnUiThread { Toast.makeText(this@TabTerminalActivity, "Split (telnet) ready", Toast.LENGTH_SHORT).show() }
                    } else {
                        runOnUiThread {
                            pane.visibility = View.GONE
                            Toast.makeText(this@TabTerminalActivity, "Split failed: telnet ${profile.host}:${profile.port.takeIf { it > 0 } ?: 23}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Coroutine cancelled mid-attach (activity destroyed while waiting):
                    // disconnect the partial tab so the TelnetConnection socket is closed
                    // and the pane is not left visible with a stale bridge.
                    try { newTab.disconnect() } catch (_: Exception) {}
                    runOnUiThread { pane.visibility = View.GONE }
                    throw e
                }
                return@launch
            }
            if (ssh == null) {
                runOnUiThread {
                    pane.visibility = View.GONE
                    Toast.makeText(this@TabTerminalActivity, "Split failed: ssh ${profile.username}@${profile.host}:${profile.port}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val newTab = SSHTab(
                    profile,
                    io.github.tabssh.terminal.TermuxBridge(
                        transcriptRows = app.preferencesManager.getTranscriptRows(),
                        cursorStyle = app.preferencesManager.getCursorStyleInt()
                    )
                )
            term.attachTerminalEmulator(newTab.termuxBridge)
            try {
                kotlinx.coroutines.delay(150)
                if (newTab.connect(ssh)) {
                    splitTab = newTab
                    runOnUiThread {
                        Toast.makeText(this@TabTerminalActivity, "Split: ${profile.getDisplayName()}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        pane.visibility = View.GONE
                        Toast.makeText(this@TabTerminalActivity, "Split failed: ssh ${profile.username}@${profile.host}:${profile.port}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine cancelled mid-attach (activity destroyed while waiting):
                // disconnect the SSH channel and release the session from
                // sshSessionManager so the foreground-service session count stays
                // accurate and the pane is not left visible with a stale bridge.
                try { newTab.disconnect() } catch (_: Exception) {}
                try { app.sshSessionManager.closeConnection(profile.id) } catch (_: Exception) {}
                runOnUiThread { pane.visibility = View.GONE }
                throw e
            }
        }
    }

    private fun closeSplitPane() {
        val tab = splitTab
        if (tab == null) {
            Toast.makeText(this, "No split pane", Toast.LENGTH_SHORT).show()
            return
        }
        // Disconnect work is blocking I/O (JSch socket teardown + termux
        // bridge stream close). Run on IO so the UI thread is not blocked
        // and ANR'd while the SSH session tears down.
        val profileId = tab.profile.id
        app.applicationScope.launch(Dispatchers.IO) {
            try { tab.disconnect() } catch (e: Exception) {
                Logger.w("TabTerminalActivity", "Split tab disconnect: ${e.message}")
            }
            try { app.sshSessionManager.closeConnection(profileId) } catch (_: Exception) {}
        }
        splitTab = null
        bottomTerminalView = null
        bottomPaneFocused = false
        findViewById<View>(R.id.split_bottom_pane).visibility = View.GONE
        Toast.makeText(this, "Split pane closed", Toast.LENGTH_SHORT).show()
    }

    private fun setBottomPaneFocused(focus: Boolean) {
        if (focus && splitTab == null) return
        // Skip the toast (but still flip the focus flag) if we're moving
        // focus off a pane that was never focused — avoids a spurious
        // "Top pane focused" announcement on the first taps after split.
        val changed = bottomPaneFocused != focus
        bottomPaneFocused = focus
        // No theme-aware tinting yet — just announce so user notices.
        if (changed) {
            val msg = if (focus) "Bottom pane focused" else "Top pane focused"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Issue #173 — toggle macro recording on the active tab. First tap
     * starts capture; second tap stops, prompts for a name, and saves
     * the recorded byte sequence as a Macro.
     */
    private fun toggleMacroRecording() {
        val active = tabManager.getActiveTab() ?: run {
            Toast.makeText(this, "No active tab", Toast.LENGTH_SHORT).show()
            return
        }
        val bridge = active.termuxBridge
        if (!bridge.isRecordingMacro()) {
            bridge.startMacroRecording()
            Toast.makeText(this, "Recording macro — replay menu to stop", Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = bridge.stopMacroRecording()
        if (bytes.isEmpty()) {
            Toast.makeText(this, "No keystrokes recorded", Toast.LENGTH_SHORT).show()
            return
        }
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val input = io.github.tabssh.ui.dialogs.DialogFields.addText(
            form, hint = getString(R.string.macro_name_hint)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.terminal_save_macro_title, bytes.size))
            .setView(form.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Macro ${System.currentTimeMillis()}" }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.macroDao().insertMacro(
                                io.github.tabssh.storage.database.entities.Macro.fromBytes(name, bytes)
                            )
                        }
                        Toast.makeText(this@TabTerminalActivity, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Logger.e("TabTerminalActivity", "Failed to save macro", e)
                        showError("Failed to save macro: ${e.message}", "Macro error")
                    }
                }
            }
            .setNegativeButton(R.string.discard, null)
            .show()
    }

    /**
     * Issue #173 — pick a saved macro and replay its bytes verbatim into
     * the active tab. Bumps usage count on replay so the picker stays
     * sorted by frequency.
     */
    private fun showMacroPicker() {
        val active = tabManager.getActiveTab() ?: run {
            Toast.makeText(this, "No active tab", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val macros = try { withContext(Dispatchers.IO) { app.database.macroDao().getAllMacrosList() } }
                catch (e: Exception) {
                    Logger.e("TabTerminalActivity", "Failed to load macros", e)
                    emptyList()
                }
            if (macros.isEmpty()) {
                Toast.makeText(this@TabTerminalActivity, "No saved macros — record one first", Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = macros.map { "${it.name} (${it.decodedSequence().size}b)" }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TabTerminalActivity)
                .setTitle(R.string.terminal_replay_macro_title)
                .setItems(labels) { _, which ->
                    val m = macros[which]
                    val bytes = m.decodedSequence()
                    // Replay through the bridge's TerminalOutput hook so
                    // any sibling code (broadcast input, etc.) sees it.
                    active.termuxBridge.write(bytes)
                    lifecycleScope.launch {
                        try { withContext(Dispatchers.IO) { app.database.macroDao().incrementUsageCount(m.id) } }
                        catch (_: Exception) {}
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * Wave 2.X — Mosh handoff. Runs `mosh-server new` over the active SSH
     * exec channel, parses MOSH CONNECT, and shows the user a copy-able
     * `mosh -p PORT user@host` command. NOT real Mosh — see
     * [io.github.tabssh.protocols.mosh.MoshHandoff] for the rationale.
     */
    private fun showMoshHandoff() {
        val active = tabManager.getActiveTab()
        if (active == null) {
            Toast.makeText(this, "No active tab", Toast.LENGTH_SHORT).show()
            return
        }
        val ssh = active.connection
        if (ssh == null) {
            Toast.makeText(this, "Mosh handoff needs an active SSH session", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Bootstrapping mosh-server…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val res = io.github.tabssh.protocols.mosh.MoshHandoff.bootstrap(
                ssh,
                username = active.profile.username,
                host = active.profile.host
            )
            runOnUiThread {
                when (res) {
                    is io.github.tabssh.protocols.mosh.MoshHandoff.Result.Success -> {
                        val info = res.info
                        val cmd = info.toClientCommand()
                        val termuxLauncher = io.github.tabssh.protocols.mosh.TermuxMoshLauncher
                        val termuxStatus = termuxLauncher.status(this@TabTerminalActivity)
                        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TabTerminalActivity)
                            .setTitle(R.string.terminal_mosh_handoff_ready_title)
                        when (termuxStatus) {
                            is io.github.tabssh.protocols.mosh.TermuxMoshLauncher.Status.Ready,
                            is io.github.tabssh.protocols.mosh.TermuxMoshLauncher.Status.Unknown -> {
                                builder.setMessage(
                                    getString(
                                        R.string.terminal_mosh_termux_ready_message,
                                        info.port,
                                        io.github.tabssh.protocols.mosh.TermuxMoshLauncher.TERMUX_PROPS_HINT
                                    )
                                )
                                .setPositiveButton(R.string.terminal_open_in_termux) { _, _ ->
                                    val ok = termuxLauncher.launch(
                                        this@TabTerminalActivity,
                                        info.host, info.port, info.keyBase64, info.username
                                    )
                                    if (!ok) {
                                        Toast.makeText(this@TabTerminalActivity,
                                            "Termux refused — check RUN_COMMAND permission + allow-external-apps", Toast.LENGTH_LONG).show()
                                    }
                                }
                                .setNeutralButton(R.string.terminal_copy_command) { _, _ ->
                                    io.github.tabssh.utils.ClipboardHelper.copy(this@TabTerminalActivity, "mosh handoff", cmd, sensitive = false)
                                    Toast.makeText(this@TabTerminalActivity, "Copied", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton(R.string.close, null)
                            }
                            io.github.tabssh.protocols.mosh.TermuxMoshLauncher.Status.MoshNotInstalled -> {
                                builder.setMessage(
                                    getString(R.string.terminal_mosh_termux_no_mosh_message, info.port, cmd)
                                )
                                .setPositiveButton(R.string.terminal_copy_command) { _, _ ->
                                    io.github.tabssh.utils.ClipboardHelper.copy(this@TabTerminalActivity, "mosh handoff", cmd, sensitive = false)
                                    Toast.makeText(this@TabTerminalActivity, "Copied", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton(R.string.close, null)
                            }
                            io.github.tabssh.protocols.mosh.TermuxMoshLauncher.Status.TermuxMissing -> {
                                builder.setMessage(
                                    getString(R.string.terminal_mosh_no_termux_message, info.port, cmd)
                                )
                                .setPositiveButton(R.string.terminal_install_termux) { _, _ ->
                                    termuxLauncher.openTermuxListing(this@TabTerminalActivity)
                                }
                                .setNeutralButton(R.string.terminal_copy_command) { _, _ ->
                                    io.github.tabssh.utils.ClipboardHelper.copy(this@TabTerminalActivity, "mosh handoff", cmd, sensitive = false)
                                    Toast.makeText(this@TabTerminalActivity, "Copied", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton(R.string.close, null)
                            }
                        }
                        builder.show()
                    }
                    is io.github.tabssh.protocols.mosh.MoshHandoff.Result.Error -> {
                        showError(res.message, "Mosh handoff failed")
                    }
                }
            }
        }
    }

    private fun openPortForwarding() {
        val activeTab = tabManager.getActiveTab()
        if (activeTab == null) {
            Toast.makeText(this, "No active connection", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, PortForwardingActivity::class.java)
        intent.putExtra("connection_id", activeTab.profile.id)
        startActivity(intent)
    }
    
    private fun toggleRecording(menuItem: MenuItem? = null) {
        val activeTab = tabManager.getActiveTab() ?: return

        if (activeTab.sessionRecorder?.isRecording() == true) {
            // Stop recording
            activeTab.sessionRecorder?.stopRecording()
            menuItem?.title = "Start Recording"
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
        } else {
            // Start recording
            if (activeTab.sessionRecorder == null) {
                activeTab.sessionRecorder = io.github.tabssh.terminal.recording.SessionRecorder(
                    this,
                    activeTab.profile.getDisplayName()
                )
            }
            activeTab.sessionRecorder?.startRecording()
            menuItem?.title = "Stop Recording"
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        }
        updateRecordingKeyIndicator()
    }

    /**
     * Mirror the active tab's recording state onto the custom keyboard's
     * transient STOP_RECORDING key (far right of row 0) — visible only while
     * the tab currently on screen is recording. Called after every toggle and
     * whenever the active tab changes, since recording state is per-tab.
     */
    private fun updateRecordingKeyIndicator() {
        val recording = tabManager.getActiveTab()?.sessionRecorder?.isRecording() == true
        binding.multiRowKeyboard.setRecordingIndicatorVisible(recording)
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Volume key action: font_size (default) / scroll / off.
        // Migrate legacy boolean key on first run. The old key was a boolean
        // stored by SwitchPreferenceCompat; migrate only if the new key has
        // not been written yet (default returns "font_size" which is also the
        // correct migrated value for "was enabled", so false→"off" is the
        // only meaningful migration).
        val volumeAction = run {
            val prefs = app.preferencesManager
            val current = prefs.getString("volume_keys_action", "")
            if (current.isEmpty()) {
                // New key not set yet — check the old boolean pref.
                val legacyEnabled = prefs.getBoolean("volume_keys_font_size", true)
                val migrated = if (legacyEnabled) "font_size" else "off"
                prefs.setString("volume_keys_action", migrated)
                migrated
            } else {
                current
            }
        }
        when (volumeAction) {
            "font_size" -> when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP   -> { adjustFontSize(+2); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { adjustFontSize(-2); return true }
            }
            "scroll" -> when (keyCode) {
                // Volume Up = page toward older content; Down = page toward newest.
                KeyEvent.KEYCODE_VOLUME_UP   -> { getActiveTerminalView()?.scrollByPage(+1); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { getActiveTerminalView()?.scrollByPage(-1); return true }
            }
            // "off" → fall through; system handles the volume event.
        }

        // App-level shortcuts. Bare Ctrl+letter is reserved for the
        // remote shell (bash/vim/tmux all treat ^C / ^D / ^W / ^R / ^T
        // / ^K / ^J as critical control codes), so app commands MUST use
        // Ctrl+Shift+letter. Otherwise we hijack things like
        // ^W (unix-word-rubout) and ^R (history-search-backward) and
        // the user's typed input dies inside our app instead of going
        // to the shell — what they perceive as "the connection got
        // killed when I hit Ctrl+W".
        //
        // Tab navigation (Ctrl+TAB / Ctrl+Shift+TAB / Ctrl+1..9) is
        // kept on bare Ctrl: every browser, terminal multiplexer, and
        // IDE uses these, and remotes never bind them. Ctrl+TAB is also
        // not a printable control code so the shell wouldn't see
        // anything useful anyway.
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_TAB -> {
                    if (event.isShiftPressed) {
                        tabManager.switchToPreviousTab()
                    } else {
                        tabManager.switchToNextTab()
                    }
                    return true
                }
                in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                    val tabNumber = keyCode - KeyEvent.KEYCODE_0
                    tabManager.switchToTabNumber(tabNumber)
                    return true
                }
                // Font-size zoom on bare Ctrl (browser/IDE/terminal
                // convention: Ctrl+= / Ctrl+- / Ctrl+0). These are not
                // printable control codes, so the remote shell loses
                // nothing. Ctrl+1..9 above is tab switching, leaving 0
                // free for reset — exactly matching the convention.
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> {
                    adjustFontSize(+2)
                    return true
                }
                KeyEvent.KEYCODE_MINUS -> {
                    adjustFontSize(-2)
                    return true
                }
                KeyEvent.KEYCODE_0 -> {
                    resetFontSize()
                    return true
                }
            }
            // App command shortcuts — REQUIRE Shift so bare Ctrl+letter
            // passes through to the terminal as the corresponding
            // control code (^T / ^W / ^K / ^J / ^R).
            if (event.isShiftPressed) {
                when (keyCode) {
                    KeyEvent.KEYCODE_T -> { showConnectionSelector(); return true }
                    KeyEvent.KEYCODE_W -> { closeCurrentTab(); return true }
                    KeyEvent.KEYCODE_K -> { showCommandPalette(); return true }
                    KeyEvent.KEYCODE_J -> { showQuickSwitcher(); return true }
                    KeyEvent.KEYCODE_R -> { showHistoryPalette(); return true }
                    KeyEvent.KEYCODE_F -> { showSearchOverlay(); return true }
                }
            }
        }

        // Let terminal view handle other keys
        val activeTerminal = getActiveTerminalView()
        return activeTerminal?.onKeyDown(keyCode, event) ?: super.onKeyDown(keyCode, event)
    }

    /**
     * Get the currently active terminal view (works in both classic and swipe modes)
     */
    private fun getActiveTerminalView(): TerminalView? {
        // Wave 2.8 — split takes precedence: if user has tapped the bottom pane
        // we route input there, regardless of which top tab is selected.
        if (bottomPaneFocused && bottomTerminalView != null) return bottomTerminalView
        return if (swipeEnabled) {
            // Get the currently visible page in ViewPager2
            val currentItem = viewPager?.currentItem ?: return null
            val holder = (viewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                ?.findViewHolderForAdapterPosition(currentItem) as? TerminalPagerAdapter.TerminalViewHolder
            holder?.terminalView
        } else {
            terminalView
        }
    }

    /**
     * Get the active tab's actual input view — [TerminalView] for SSH tabs
     * and text-mode console tabs, [io.github.tabssh.ui.views.VncView] for
     * console tabs in RFB display mode, [io.github.tabssh.ui.views.SpiceView]
     * for console tabs in SPICE display mode. Classic (non-swipe) mode only
     * ever hosts SSH tabs (VNC/console tabs require swipe/ViewPager2 — see
     * onResume's classic-mode reconnect comment), so it always falls back to
     * the single [terminalView]. Used by [toggleKeyboard] so the IME attaches
     * to whichever view is actually receiving input.
     */
    private fun getActiveInputView(): View? {
        if (bottomPaneFocused && bottomTerminalView != null) return bottomTerminalView
        if (!swipeEnabled) return terminalView
        val currentItem = viewPager?.currentItem ?: return null
        val holder = (viewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentItem)
        return when (holder) {
            is TerminalPagerAdapter.TerminalViewHolder -> holder.terminalView
            is TerminalPagerAdapter.VncViewHolder -> holder.vncView
            is TerminalPagerAdapter.ConsoleViewHolder -> when {
                !holder.isGraphicalMode -> holder.terminalView
                holder.isRfbMode -> holder.vncView
                else -> holder.spiceView
            }
            else -> null
        }
    }

    /**
     * Get the active tab's [ConsoleTab], or null when the active tab is not
     * a console tab. Used by [handleCustomKeyPress] to decide whether a bar
     * key must be routed to the RFB/SPICE session instead of a TerminalView.
     */
    private fun getActiveConsoleTab(): ConsoleTab? =
        (tabManager.getActiveTabSealed() as? Tab.Console)?.consoleTab

    /**
     * Route a custom-keyboard-bar key press to a console tab's graphical
     * (RFB or SPICE) session. Named keys ([ConsoleKeyMapper.RFB_KEYSYM_BY_ID] /
     * [ConsoleKeyMapper.SPICE_SCANCODE_BY_ID]) are sent as a press+release
     * pair through the resolved [VncView] / [SpiceView]; single printable
     * characters go through the view's `sendChar`. Any modifier latched via
     * [consolePendingModifier] brackets the key as modifier-down, key
     * press/release, modifier-up, then is cleared (along with the bar's
     * highlight) — mirroring TerminalView's pending-modifier contract. Keys
     * with no console mapping are logged at debug and dropped; never crash.
     */
    private fun sendConsoleKeyPress(consoleTab: ConsoleTab, key: io.github.tabssh.ui.keyboard.KeyboardKey) {
        val inputView = getActiveInputView()
        val modifier = consolePendingModifier
        consolePendingModifier = null
        if (modifier != null) binding.multiRowKeyboard.clearModifier()

        when (consoleTab.displayMode.value) {
            ConsoleDisplayMode.RFB -> {
                val vncView = inputView as? VncView
                if (vncView == null) {
                    Logger.d("TabTerminalActivity", "Console key ${key.id}: no active VncView")
                    return
                }
                val modKeysym = modifier?.let { ConsoleKeyMapper.RFB_MODIFIER_KEYSYM[it] }
                val keysym = ConsoleKeyMapper.RFB_KEYSYM_BY_ID[key.id]
                if (keysym != null) {
                    if (modKeysym != null) vncView.sendKey(modKeysym, true)
                    vncView.sendKey(keysym, true)
                    vncView.sendKey(keysym, false)
                    if (modKeysym != null) vncView.sendKey(modKeysym, false)
                    return
                }
                val ch = key.keySequence.singleOrNull()
                if (ch == null) {
                    Logger.d("TabTerminalActivity", "Console key ${key.id}: no RFB mapping — dropped")
                    return
                }
                if (modKeysym != null) vncView.sendKey(modKeysym, true)
                vncView.sendChar(ch)
                if (modKeysym != null) vncView.sendKey(modKeysym, false)
            }
            ConsoleDisplayMode.SPICE -> {
                val spiceView = inputView as? SpiceView
                if (spiceView == null) {
                    Logger.d("TabTerminalActivity", "Console key ${key.id}: no active SpiceView")
                    return
                }
                val modScancode = modifier?.let { ConsoleKeyMapper.SPICE_MODIFIER_SCANCODE[it] }
                val scancode = ConsoleKeyMapper.SPICE_SCANCODE_BY_ID[key.id]
                if (scancode != null) {
                    if (modScancode != null) spiceView.sendScancode(modScancode, true)
                    spiceView.sendScancode(scancode, true)
                    spiceView.sendScancode(scancode, false)
                    if (modScancode != null) spiceView.sendScancode(modScancode, false)
                    return
                }
                val ch = key.keySequence.singleOrNull()
                if (ch == null) {
                    Logger.d("TabTerminalActivity", "Console key ${key.id}: no SPICE mapping — dropped")
                    return
                }
                if (modScancode != null) spiceView.sendScancode(modScancode, true)
                if (!spiceView.sendChar(ch)) {
                    Logger.d("TabTerminalActivity", "Console key ${key.id}: char '$ch' has no scancode — dropped")
                }
                if (modScancode != null) spiceView.sendScancode(modScancode, false)
            }
            ConsoleDisplayMode.TEXT -> {
                // Unreachable — callers only route here when isGraphicalMode is
                // true, and a console tab's displayMode never falls back to
                // TEXT once it flips graphical (see ConsoleTab).
            }
        }
    }

    /**
     * Adjust terminal font size by delta
     */
    /**
     * Wave 2.6 — Command palette (Ctrl+K). Lists every navigable destination
     * + tab/connection actions; fuzzy-filterable from the search box.
     */
    private fun showCommandPalette() {
        val items = mutableListOf<io.github.tabssh.ui.views.PaletteDialog.Item>()
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Settings", "Open settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Theme Editor", "Create or edit a custom terminal theme") {
            startActivity(ThemeEditorActivity.createIntent(this))
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("SSH Keys", "Manage SSH private keys & certificates") {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("start_tab", 2)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent)
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Snippets", "Reusable command snippets") {
            startActivity(Intent(this, SnippetManagerActivity::class.java))
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Port Forwarding", "Local / Remote / SOCKS tunnels") {
            startActivity(Intent(this, PortForwardingActivity::class.java))
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Find in scrollback", "Search current tab's history") {
            showSearchOverlay()
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Close current tab", null) { closeCurrentTab() }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Increase font size", "Ctrl+= (or Volume Up)") { adjustFontSize(+2) }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Decrease font size", "Ctrl+- (or Volume Down)") { adjustFontSize(-2) }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Reset font size", "Ctrl+0") { resetFontSize() }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Toggle keyboard", null) { toggleKeyboard() }
        val barLabel = if (customKeyboardVisible) "Hide key bar" else "Show key bar"
        items += io.github.tabssh.ui.views.PaletteDialog.Item(barLabel, "Show or hide the custom function-key bar") {
            if (customKeyboardVisible) hideCustomKeyboardBar() else showCustomKeyboardBar()
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Paste from clipboard", null) { pasteFromClipboard() }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Copy screen", "Copy visible terminal output to clipboard") { copyTerminalScreen() }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Cluster: send to all sessions…", "Broadcast a command to every open tab") { showClusterBroadcastDialog() }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Share connection info", "Share host / user details") { shareSession() }
        io.github.tabssh.ui.views.PaletteDialog.show(this, "Command Palette", items)
    }

    /**
     * Wave 2.6 — Quick switcher (Ctrl+J). Lists open tabs first, then recent
     * connections — pick one to switch / open.
     */
    private fun showQuickSwitcher() {
        val items = mutableListOf<io.github.tabssh.ui.views.PaletteDialog.Item>()
        // Open tabs. Displayed number is this SSH-only list's 1-based
        // position, but the switch itself is by tabId — switchToTabNumber()
        // indexes the unified list, which is not the same position once
        // VNC/console tabs exist.
        tabManager.getAllTabs().forEachIndexed { index, tab ->
            items += io.github.tabssh.ui.views.PaletteDialog.Item(
                "Tab ${index + 1}: ${tab.profile.getDisplayName()}",
                "Open · ${tab.connectionState.value}"
            ) { tabManager.switchToTabById(tab.tabId) }
        }
        // Recent connections (top 20 most-used)
        lifecycleScope.launch {
            try {
                val recent = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getFrequentlyUsedConnections(20)
                }
                runOnUiThread {
                    recent.forEach { profile ->
                        items += io.github.tabssh.ui.views.PaletteDialog.Item(
                            profile.getDisplayName(),
                            "Connect · ${profile.username}@${profile.host}:${profile.port}"
                        ) {
                            // forceNew=true: quick-switcher connections always open a new tab
                            // (the existing-tab section above handles switching to open tabs).
                            lifecycleScope.launch { connectToProfile(profile, forceNew = true) }
                        }
                    }
                    io.github.tabssh.ui.views.PaletteDialog.show(this@TabTerminalActivity, "Quick Switcher", items)
                }
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Failed to load recent connections for switcher", e)
                runOnUiThread {
                    io.github.tabssh.ui.views.PaletteDialog.show(this@TabTerminalActivity, "Quick Switcher", items)
                }
            }
        }
    }

    private fun adjustFontSize(delta: Int) {
        val view = getActiveTerminalView()
        view?.let {
            val currentSize = it.getFontSize()
            val newSize = (currentSize + delta).coerceIn(8, 32)
            it.setFontSize(newSize)

            // Session-only zoom (like pinch): the terminal_font_size pref
            // stays the user's Settings baseline so resetFontSize() has a
            // real value to return to. Persisting here would silently
            // redefine that baseline and make reset a no-op.
            Toast.makeText(this, "Font Size: ${newSize}sp", Toast.LENGTH_SHORT).show()

            Logger.d("TabTerminalActivity", "Font size adjusted: $currentSize → $newSize")
        }
    }

    private fun resetFontSize() {
        val view = getActiveTerminalView() ?: return
        // Reset to the user's configured baseline (Settings → Terminal →
        // Font Size), NOT a hardcoded default — 14 only applies when the
        // user never touched the slider.
        val baseline = app.preferencesManager.getInt("terminal_font_size", 14)
        view.setFontSize(baseline)
        Toast.makeText(this, "Font Size: ${baseline}sp (your default)", Toast.LENGTH_SHORT).show()
        Logger.d("TabTerminalActivity", "Font size reset to baseline: $baseline")
    }
    
    private fun toggleKeyboard() {
        // getActiveInputView() resolves TerminalView / VncView / SpiceView
        // depending on tab type and console display mode — VncView and
        // SpiceView both implement onCreateInputConnection so the IME
        // attaches to them exactly like it does to a TerminalView.
        val inputView = getActiveInputView() ?: return
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager

        // Always grab focus first so the IME has a target.
        inputView.requestFocus()

        // toggleSoftInput(SHOW_IMPLICIT) silently no-ops on the first tap when
        // the framework hasn't seen us as the active input client yet (Issue
        // #39). Use WindowInsetsCompat to read actual IME visibility and
        // drive show/hide explicitly.
        val rootInsets = androidx.core.view.ViewCompat
            .getRootWindowInsets(inputView)
        val imeVisible = rootInsets?.isVisible(
            androidx.core.view.WindowInsetsCompat.Type.ime()
        ) == true

        if (imeVisible) {
            imm.hideSoftInputFromWindow(inputView.windowToken, 0)
            Logger.d("TabTerminalActivity", "IME hidden")
        } else {
            if (hasHardwareKeyboard()) {
                Logger.d("TabTerminalActivity", "IME show suppressed — hardware keyboard active")
                return
            }
            imm.showSoftInput(
                inputView,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
            )
            Logger.d("TabTerminalActivity", "IME shown")
        }
    }

    /**
     * True when a physical keyboard is attached and currently exposed
     * (e.g. Bluetooth/USB keyboard connected, hardware keyboard slid out).
     * Used to auto-hide both the soft IME and the custom on-screen key bar
     * since neither is useful when the user is typing on real keys.
     */
    private fun hasHardwareKeyboard(): Boolean {
        val cfg = resources.configuration
        return cfg.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
            cfg.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    }

    /**
     * Reconcile the custom key bar's on-screen visibility with the current
     * hardware-keyboard state. When a HW keyboard is attached, hide the bar
     * regardless of the user's stored preference — the pref is preserved so
     * the bar reappears once the HW keyboard is unplugged.
     */
    private fun applyHardwareKeyboardPolicy() {
        val hw = hasHardwareKeyboard()
        if (hw) {
            binding.multiRowKeyboard.visibility = android.view.View.GONE
        } else {
            binding.multiRowKeyboard.visibility =
                if (customKeyboardVisible) android.view.View.VISIBLE else android.view.View.GONE
        }
        Logger.d("TabTerminalActivity", "HW keyboard policy: hw=$hw pref=$customKeyboardVisible")
    }

    private fun openFileManager() {
        val activeTab = tabManager.getActiveTab()
        if (activeTab != null && activeTab.isConnected()) {
            // Open SFTP file manager
            val intent = Intent(this, SFTPActivity::class.java).apply {
                putExtra(SFTPActivity.EXTRA_CONNECTION_ID, activeTab.profile.id)
            }
            startActivity(intent)
        } else {
            showToast("Connect to a server first")
        }
    }

    /**
     * Text-operations popup attached to the 📋 keyboard key. Offers paste,
     * drag-select, and full-screen copy without cluttering the terminal bottom
     * sheet with text-only actions.
     */
    private fun showClipboardMenu() {
        // A graphical console tab (RFB/SPICE) has no text buffer to select
        // from or terminal screen to copy — those entries only make sense
        // against a TerminalView, so they're dropped from the menu entirely
        // rather than shown disabled. Paste still works everywhere: it goes
        // through the console text-input path on a graphical console tab.
        val consoleGraphical = getActiveConsoleTab()?.isGraphicalMode?.value == true
        val options = if (consoleGraphical) {
            arrayOf("Paste")
        } else {
            arrayOf("Paste", "Select Text…", "Copy Screen")
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pasteFromClipboard()
                    1 -> {
                        getActiveTerminalView()?.armSelectionForNextDrag()
                        Toast.makeText(this, "Drag to select, then tap Copy.", Toast.LENGTH_LONG).show()
                    }
                    2 -> copyTerminalScreen()
                }
            }
            .show()
    }

    private fun pasteFromClipboard() {
        // Guard against the activity being torn down between the menu tap and
        // the system-service call. Accessing ClipboardManager / Toast on a
        // destroyed activity throws BadTokenException.
        if (isFinishing || isDestroyed) {
            Logger.w("TabTerminalActivity", "pasteFromClipboard: activity finishing/destroyed; ignoring")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        // coerceToText() handles plain-text, HTML, and URI clipboard items; plain
        // .text only returns non-null for ClipData.newPlainText() clips, so it
        // silently drops everything else.
        val text = clipboard.primaryClip?.getItemAt(0)
            ?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val consoleTab = getActiveConsoleTab()
        if (consoleTab != null && consoleTab.isGraphicalMode.value) {
            when (val inputView = getActiveInputView()) {
                is VncView -> inputView.onTextInput?.invoke(text)
                is SpiceView -> inputView.onTextInput?.invoke(text)
                else -> {
                    Logger.w("TabTerminalActivity", "pasteFromClipboard: no active console view")
                    Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        val tv = getActiveTerminalView()
        if (tv == null) {
            Logger.w("TabTerminalActivity", "pasteFromClipboard: no active terminal view")
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show()
            return
        }
        tv.pasteText(text)
    }
    
    private fun showConnectionSelector() {
        lifecycleScope.launch {
            val connections = try {
                withContext(Dispatchers.IO) { app.database.connectionDao().getRecentConnections(50) }
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Failed to load connections for picker", e)
                emptyList()
            }
            runOnUiThread {
                val labels = connections.map { it.getDisplayName() }.toTypedArray()
                val items = arrayOf(getString(R.string.terminal_add_new_connection)) + labels

                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TabTerminalActivity)
                    .setTitle(R.string.terminal_open_new_tab_title)
                    .setItems(items) { _, which ->
                        if (which == 0) {
                            startActivity(Intent(this@TabTerminalActivity, ConnectionEditActivity::class.java))
                        } else {
                            val profile = connections[which - 1]
                            // forceNew=true: the user explicitly chose "Open new tab".
                            // Never reattach to an existing session from this path.
                            lifecycleScope.launch { connectToProfile(profile, forceNew = true) }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /**
     * Show snippets dialog for quick command access
     */
    private fun showSnippetsDialog() {
        lifecycleScope.launch {
            try {
                val snippets = withContext(Dispatchers.IO) { app.database.snippetDao().getFrequentlyUsedSnippets(20) }

                if (snippets.isEmpty()) {
                    // Show create snippet dialog if no snippets exist
                    showToast("No snippets yet. Create one first!")
                    showCreateSnippetDialog()
                    return@launch
                }

                // Build snippet list
                val snippetNames = snippets.map { snippet ->
                    if (snippet.hasVariables()) {
                        "${snippet.name} ${snippet.category}"
                    } else {
                        "${snippet.name} - ${snippet.category}"
                    }
                }.toTypedArray()

                runOnUiThread {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TabTerminalActivity)
                        .setTitle(R.string.terminal_insert_snippet_title)
                        .setItems(snippetNames) { _, which ->
                            val snippet = snippets[which]
                            insertSnippet(snippet)
                        }
                        .setNeutralButton(R.string.terminal_manage_snippets) { _, _ ->
                            showManageSnippetsMenu()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Failed to load snippets", e)
                showError("Failed to load snippets", "Error")
            }
        }
    }

    /**
     * Insert a snippet into the active terminal
     */
    private fun insertSnippet(snippet: io.github.tabssh.storage.database.entities.Snippet) {
        lifecycleScope.launch {
            try {
                // Check if snippet has variables
                if (snippet.hasVariables()) {
                    // Show dialog to fill in variables
                    showVariablesDialog(snippet)
                } else {
                    // Insert directly
                    val terminal = getActiveTerminalView()
                    terminal?.sendText(snippet.command)

                    // Increment usage count
                    withContext(Dispatchers.IO) { app.database.snippetDao().incrementUsageCount(snippet.id) }

                    Logger.d("TabTerminalActivity", "Inserted snippet: ${snippet.name}")
                }
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Failed to insert snippet", e)
                showError("Failed to insert snippet", "Error")
            }
        }
    }

    /**
     * Wave 2.1 — Show dialog to fill snippet variables. Honours `{?name:default|hint}`
     * declared defaults and hints, and recalls the last value used for each
     * variable name (per snippet) from SharedPreferences so users don't retype
     * the same hostnames / paths over and over.
     */
    private fun showVariablesDialog(snippet: io.github.tabssh.storage.database.entities.Snippet) {
        val specs = snippet.getVariableSpecs()
        if (specs.isEmpty()) {
            getActiveTerminalView()?.sendText(snippet.command)
            return
        }
        val recallPrefs = getSharedPreferences("snippet_var_recall", android.content.Context.MODE_PRIVATE)
        val inputs = mutableListOf<com.google.android.material.textfield.TextInputEditText>()

        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)

        specs.forEach { spec ->
            // Pre-fill: last-used > declared default > blank.
            // For password-typed variables we never recall the prior
            // value — only the declared default is honoured (and even
            // that only because if the user wrote the default in
            // plaintext into the snippet, they've already opted in).
            val recall = if (spec.isPassword) null
                else recallPrefs.getString("${snippet.id}/${spec.name}", null)
            val initial = recall ?: spec.default
            // Floating label carries the variable name; the masking toggle on
            // secret fields replaces the old "(masked)" suffix in the label text.
            val helper = spec.hint ?: getString(R.string.snippet_variable_default_hint, spec.name)
            val input = if (spec.isPassword) {
                io.github.tabssh.ui.dialogs.DialogFields.addSecret(
                    form, hint = spec.name, initial = initial, helper = helper
                )
            } else {
                io.github.tabssh.ui.dialogs.DialogFields.addText(
                    form, hint = spec.name, initial = initial, helper = helper
                )
            }
            if (!initial.isNullOrEmpty()) {
                input.setSelection(input.text?.length ?: 0)
            }
            inputs.add(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_fill_variables_title)
            .setView(form.root)
            .setPositiveButton(R.string.terminal_insert) { _, _ ->
                val values = mutableMapOf<String, String>()
                val recallEdits = recallPrefs.edit()
                specs.forEachIndexed { i, spec ->
                    val v = inputs[i].text.toString()
                    values[spec.name] = v
                    // Never persist password-typed variable values.
                    if (v.isNotBlank() && !spec.isPassword) {
                        recallEdits.putString("${snippet.id}/${spec.name}", v)
                    }
                }
                recallEdits.apply()

                getActiveTerminalView()?.sendText(snippet.applyVariables(values))
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.database.snippetDao().incrementUsageCount(snippet.id) }
                }
                Logger.d("TabTerminalActivity", "Inserted snippet with variables: ${snippet.name}")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Show manage snippets menu
     */
    private fun showManageSnippetsMenu() {
        val options = arrayOf(
            getString(R.string.terminal_create_new_snippet),
            getString(R.string.terminal_view_all_snippets),
            getString(R.string.terminal_search_snippets_title)
        )

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_manage_snippets)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateSnippetDialog()
                    1 -> showAllSnippetsDialog()
                    2 -> showSearchSnippetsDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Show dialog to create a new snippet
     */
    private fun showCreateSnippetDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_snippet, null)
        val inputName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_snippet_name)
        val inputCommand = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_snippet_command)
        val inputDescription = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_snippet_description)
        val inputCategory = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.edit_snippet_category)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_create_snippet_title)
            .setView(dialogView)
            .setPositiveButton(R.string.docker_create) { _, _ ->
                val name = inputName.text.toString().trim()
                val command = inputCommand.text.toString().trim()
                val category = inputCategory.text.toString().trim().ifBlank { "General" }

                if (name.isNotBlank() && command.isNotBlank()) {
                    lifecycleScope.launch {
                        val snippet = io.github.tabssh.storage.database.entities.Snippet(
                            name = name,
                            command = command,
                            description = inputDescription.text.toString().trim(),
                            category = category
                        )
                        withContext(Dispatchers.IO) { app.database.snippetDao().insertSnippet(snippet) }
                        showToast("Snippet created: $name")
                        Logger.d("TabTerminalActivity", "Created snippet: $name")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Show all snippets dialog
     */
    private fun showAllSnippetsDialog() {
        lifecycleScope.launch {
            val allSnippets = withContext(Dispatchers.IO) { app.database.snippetDao().getFrequentlyUsedSnippets(100) }

            if (allSnippets.isEmpty()) {
                showToast("No snippets yet")
                return@launch
            }

            val snippetNames = allSnippets.map { "${it.name} - ${it.category}" }.toTypedArray()

            runOnUiThread {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TabTerminalActivity)
                    .setTitle(R.string.terminal_all_snippets_title)
                    .setItems(snippetNames) { _, which ->
                        insertSnippet(allSnippets[which])
                    }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
        }
    }

    /**
     * Show search snippets dialog
     */
    private fun showSearchSnippetsDialog() {
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val input = io.github.tabssh.ui.dialogs.DialogFields.addText(
            form, hint = getString(R.string.search_snippets_hint)
        )

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.terminal_search_snippets_title)
            .setView(form.root)
            .setPositiveButton(R.string.terminal_search) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) {
                    searchAndShowSnippets(query)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Search and show matching snippets
     */
    private fun searchAndShowSnippets(query: String) {
        // searchSnippets returns a Room Flow that re-emits on every DB change.
        // Take the first emission (`.first()`) and bail — we want a single
        // snapshot for the dialog, not a live subscription that re-opens the
        // dialog every time the user touches another snippet table.
        lifecycleScope.launch {
            val results = try {
                app.database.snippetDao()
                    .searchSnippets(query)
                    .flowOn(Dispatchers.IO)
                    .firstOrNull() ?: emptyList()
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Snippet search failed", e)
                emptyList()
            }
            if (results.isEmpty()) {
                showToast("No matching snippets")
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            val snippetNames = results.map { "${it.name} - ${it.category}" }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@TabTerminalActivity)
                .setTitle(R.string.terminal_search_results_title)
                .setItems(snippetNames) { _, which ->
                    insertSnippet(results[which])
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    /**
     * Close the active tab, whichever kind it is. This is the shared exit
     * point for every generic close action — the toolbar's close button,
     * the overflow menu's "Close tab", Ctrl+W, and the command palette.
     *
     * [TabManager.closeTab] only fires [TabManagerListener.onTabClosed] for
     * SSH tabs (the listener is SSH-typed), so calling it directly for a
     * Vnc/Console tab would remove the tab's data but leave the ViewPager2 /
     * TabLayout UI stale and skip the "close last tab → finish()" handling.
     * Route Vnc/Console tabs through [closeConsoleTab], which already does
     * that UI work for the console reconnect-dialog close path.
     */
    private fun closeCurrentTab() {
        // lifecycleScope cancels with the activity so teardown work is never orphaned.
        lifecycleScope.launch {
            val activeIndex = tabManager.getActiveTabIndex()
            if (activeIndex < 0) return@launch
            when (val entry = tabManager.getActiveTabSealed()) {
                is Tab.Ssh -> tabManager.closeTab(activeIndex)
                is Tab.Vnc, is Tab.Console -> closeConsoleTab(entry.tabId)
                null -> Unit
            }
        }
    }

    private fun disconnectAllTabs() {
        lifecycleScope.launch {
            tabManager.closeAllTabs()
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Show a password prompt dialog and suspend until the user responds.
     * Returns the entered password, or null if the user cancelled.
     */
    private suspend fun promptForPassword(message: String): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
            // setMessage and setView both own the dialog's content area — using both
            // silently drops the message on most ROMs. Show the message as a TextView
            // inside the same form column so both the prompt and the field are visible.
            val promptLabel = android.widget.TextView(this).apply {
                text = message
                setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            }
            form.column.addView(promptLabel, 0)
            val editText = io.github.tabssh.ui.dialogs.DialogFields.addSecret(
                form, hint = getString(R.string.password_hint)
            )
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.terminal_auth_required_title)
                .setView(form.root)
                .setPositiveButton(R.string.connect_button) { _, _ ->
                    if (cont.isActive) cont.resume(editText.text.toString()) {}
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    if (cont.isActive) cont.resume(null) {}
                }
                .setCancelable(false)
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.multiRowKeyboard.notifyConfigurationChanged(newConfig)
        // A HW keyboard being connected/disconnected fires
        // onConfigurationChanged with a new `keyboard`/`hardKeyboardHidden`
        // — reconcile bar visibility so the user does not see a redundant
        // key bar while typing on real keys.
        applyHardwareKeyboardPolicy()
        // Also drop the soft IME if a HW keyboard just became active.
        if (hasHardwareKeyboard()) {
            val terminalView = getActiveTerminalView()
            if (terminalView != null) {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Re-apply theme so toggling accessibility_high_contrast (or any
        // theme change) in Settings takes effect when the user comes back
        // here, instead of requiring a full activity recreate.
        applyCurrentTheme()

        // Re-load keyboard layout — when the user opens
        // KeyboardCustomizationActivity from Settings and saves a new
        // layout, the terminal needs to pick it up on return. Without
        // this we'd keep showing the layout that was current when the
        // terminal activity was created.
        setupCustomKeyboard()

        // Re-apply terminal-screen prefs (Fullscreen Terminal, Show
        // Function Key Row, Line Spacing, Word Wrap). These are toggled
        // in Settings and need to take effect on return without a full
        // recreate. Was previously only in onCreate, so toggling
        // Fullscreen had no visible effect until app restart.
        applyTerminalUiPrefs()

        // Re-sync the PRE key with this connection's per-connection PRE-key
        // override — settable from the PRE long-press picker. If the user
        // disabled it while this activity was backgrounded, drop any stuck
        // [PRE] armed-latch — taps are now ignored while disabled, so a
        // stale [PRE] label could no longer be cleared by the user.
        val resumedTab = tabManager.getActiveTab()
        if (resumedTab != null && !resumedTab.isPrefixKeyEnabled && prefixArmed) {
            prefixArmed = false
            prefixArmedType = null
            getActiveTerminalView()?.let { tv ->
                tv.setPendingPrefix(null)
                tv.onPrefixConsumed = null
            }
        }
        updatePrefixKeyVisual(resumedTab?.activeMultiplexerType)

        // VNC-tab-swipe integration step 6e: reclaim any Tab.Vnc/Tab.Console
        // session parked in VncBackgroundSessionStore by onStop() below, then
        // stop VncKeepAliveService if nothing is left parked (mirrors
        // VMConsoleActivity.reattachVncHost()'s pattern, see AI.md §11.7.2).
        tabManager.reclaimBackgroundSessions()
        if (VncBackgroundSessionStore.isEmpty) {
            VncKeepAliveService.stopService(applicationContext)
        }

        // Restore active tab if needed
        val activeTab = tabManager.getActiveTab()
        if (activeTab != null) {
            if (!swipeEnabled) {
                // In classic mode, reconnect terminal view to active tab
                val terminal = activeTab.termuxBridge
                terminalView?.initialize(terminal.getRows(), terminal.getCols())
            }
            activeTab.activate()
            supportActionBar?.title = activeTab.getDisplayTitle()
        }
    }
    
    override fun onPause() {
        super.onPause()

        // saveTabState() is a disk write — dispatch to IO, bound to the activity lifecycle.
        lifecycleScope.launch(Dispatchers.IO) {
            tabManager.saveTabState()
        }
    }

    override fun onStop() {
        super.onStop()

        // VNC-tab-swipe integration step 6e background-parking parity: pause
        // and park every live, connected Tab.Vnc/graphical Tab.Console
        // session into VncBackgroundSessionStore, then start
        // VncKeepAliveService so the OS doesn't reclaim the process while
        // backgrounded — the same protection SSH tabs already get
        // incidentally from SSHConnectionService (see AI.md §11.7.2).
        if (tabManager.parkBackgroundSessions()) {
            VncKeepAliveService.startService(applicationContext)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cancel performance overlay updates
        performanceUpdateJob?.cancel()

        // Drop the listener BEFORE cleanup so we can't get a callback into
        // a half-destroyed activity, and so the anonymous-listener implicit
        // back-ref to `this` is broken (memory leak fix).
        tabManagerListener?.let { tabManager.removeListener(it) }
        tabManagerListener = null

        // Cancel the warnings collector so it doesn't outlive the Activity.
        warningsJob?.cancel()
        warningsJob = null

        // The ASK-mode multiplexer dialogs hold this window's token.
        dismissMultiplexerDialogs()

        // Disconnect the split-pane tab if the user never tapped "Close split".
        // The split tab is Activity-scoped (not in the Application TabManager)
        // so it would leak an open SSH channel if we don't clean it up here.
        val stab = splitTab
        if (stab != null) {
            // Dispatch the blocking SSH/socket teardown to IO so onDestroy
            // (Main thread) does not block waiting for JSch session.disconnect()
            // and any inflight read loops. applicationScope outlives the
            // Activity so the cleanup completes even after onDestroy returns.
            val sshSessionManager = app.sshSessionManager
            val profileId = stab.profile.id
            app.applicationScope.launch(Dispatchers.IO) {
                try { stab.disconnect() } catch (e: Exception) {
                    Logger.w("TabTerminalActivity", "Split tab cleanup in onDestroy: ${e.message}")
                }
                try { sshSessionManager.closeConnection(profileId) } catch (_: Exception) {}
            }
        }
        splitTab = null
        bottomTerminalView = null

        Logger.d("TabTerminalActivity", "Terminal activity destroyed")

        // Intentionally do NOT call tabManager.cleanup() here — the
        // TabManager is Application-scoped (see setupTabManager), so its
        // tabs + TermuxBridges + read loops survive this activity's
        // destruction. cleanup() would disconnect every tab across every
        // profile, defeating the back-button reattach guarantee.
        //
        // Intentionally do NOT close SSH sessions here either. The
        // foreground service owns connection lifecycle. Earlier code
        // closed every open profile's session in onDestroy so the FG
        // notification wouldn't show stale counts — but that meant
        // pressing the Android back button (or the system killing this
        // activity to reclaim memory) would tear down live connections.
        // Now each host has its own notification (see
        // SSHConnectionService), so staleness isn't an issue, and the
        // connections survive a back-out exactly as the user expects.
    }
    
    private fun setupCustomKeyboard() {
        // Restore the user's bar-visibility preference (default: visible).
        val barVisible = app.preferencesManager.getBoolean(PREF_KEY_BAR_VISIBLE, true)
        customKeyboardVisible = barVisible
        binding.multiRowKeyboard.visibility =
            if (barVisible) android.view.View.VISIBLE else android.view.View.GONE
        // If a hardware keyboard is already attached at startup, override the
        // stored pref visually — the pref is preserved so the bar reappears
        // when the HW keyboard is disconnected.
        applyHardwareKeyboardPolicy()

        // Wire listeners synchronously — these are cheap.
        binding.multiRowKeyboard.setOnKeyClickListener { key ->
            handleCustomKeyPress(key)
        }

        // Long-press escape hatch — PRE long-press opens the multiplexer
        // picker so the user can manually override an auto-detected (and
        // possibly wrong) type, even when detection already reported one.
        binding.multiRowKeyboard.setOnKeyLongClickListener { key ->
            if (key.id == "PREFIX") {
                // Long-press always works, even when the PRE key is disabled —
                // it is the only way to reach the Enable/Disable toggle inside
                // showMultiplexerPickerDialog() and re-enable the key.
                Logger.d("TabTerminalActivity", "PREFIX key long-press — opening multiplexer picker override")
                // Cancel any armed latch first so a stale prefix doesn't
                // fire after the user picks a (possibly different) type —
                // mirrors the second-tap disarm path above.
                if (prefixArmed) {
                    val tab = tabManager.getActiveTab()
                    val type = tab?.activeMultiplexerType
                    prefixArmed = false
                    prefixArmedType = null
                    getActiveTerminalView()?.let { tv ->
                        tv.setPendingPrefix(null)
                        tv.onPrefixConsumed = null
                    }
                    updatePrefixKeyVisual(type)
                }
                showMultiplexerPickerDialog()
            }
        }

        binding.multiRowKeyboard.setOnToggleClickListener {
            toggleCustomKeyboard()
        }

        // Bridge bar modifier state into the terminal so IME letters honour
        // sticky CTL/ALT (Issue #37). Also wire the inverse — when the
        // terminal consumes a one-shot modifier, the bar UI must reset.
        binding.multiRowKeyboard.setOnModifierChangedListener { modifier ->
            val tv = getActiveTerminalView()
            if (tv != null) {
                tv.setPendingModifier(modifier)
                tv.onModifierConsumed = {
                    binding.multiRowKeyboard.clearModifier()
                }
                return@setOnModifierChangedListener
            }
            // No TerminalView active — a graphical console tab (RFB/SPICE) is
            // showing. Latch the modifier here; sendConsoleKeyPress consumes
            // and clears it (and the bar visual) on the next non-modifier key.
            consolePendingModifier = modifier
        }

        // Defer the expensive layout population (button creation) to after
        // onCreate returns so it does not block the main thread.
        binding.multiRowKeyboard.post {
            try {
                val rowCount = app.preferencesManager.getKeyboardRowCount()
                binding.multiRowKeyboard.setRowCount(rowCount)

                // Auto-migrate default layout when the built-in layout has been
                // updated and the user has not explicitly customised their layout.
                val storedVersion = app.preferencesManager.getKeyboardLayoutVersion()
                val isCustomized  = app.preferencesManager.isKeyboardLayoutCustomized()
                if (storedVersion < io.github.tabssh.ui.keyboard.MultiRowKeyboardView.CURRENT_DEFAULT_LAYOUT_VERSION && !isCustomized) {
                    Logger.i("TabTerminalActivity", "Default layout updated (v$storedVersion → v${io.github.tabssh.ui.keyboard.MultiRowKeyboardView.CURRENT_DEFAULT_LAYOUT_VERSION}); clearing saved layout")
                    app.preferencesManager.setKeyboardLayoutJson(null)
                    app.preferencesManager.setKeyboardLayoutVersion(io.github.tabssh.ui.keyboard.MultiRowKeyboardView.CURRENT_DEFAULT_LAYOUT_VERSION)
                }

                val layoutJson = app.preferencesManager.getKeyboardLayoutJson()
                Logger.d("TabTerminalActivity", "Custom keyboard layout JSON length=${layoutJson?.length ?: 0}, rowCount=$rowCount")
                if (layoutJson != null) {
                    try {
                        val savedLayout = io.github.tabssh.ui.keyboard.KeyboardLayoutManager.parseLayoutJson(layoutJson)
                        binding.multiRowKeyboard.setLayout(savedLayout)
                        Logger.i("TabTerminalActivity", "Loaded custom keyboard layout: ${savedLayout.size} rows, ${savedLayout.sumOf { it.size }} keys")
                    } catch (e: Exception) {
                        Logger.e("TabTerminalActivity", "Failed to load keyboard layout, using defaults", e)
                        binding.multiRowKeyboard.resetToDefault()
                    }
                } else {
                    Logger.d("TabTerminalActivity", "No saved layout, using default keyboard")
                    binding.multiRowKeyboard.resetToDefault()
                }
                Logger.d("TabTerminalActivity", "Multi-row keyboard initialized with $rowCount rows")
            } catch (e: Exception) {
                Logger.w("TabTerminalActivity", "Keyboard layout load failed; using defaults: ${e.message}")
                binding.multiRowKeyboard.resetToDefault()
            }
            // Re-sync the PREFIX key visual after the keyboard is rebuilt.
            // resetToDefault() / setLayout() create fresh KeyButton instances with
            // the label "PRE"; the StateFlow won't re-emit (value unchanged since
            // the last collect), so we force an update here to restore the correct
            // label and colour state.
            val currentMuxType = tabManager.getActiveTab()?.activeMultiplexerType
            updatePrefixKeyVisual(currentMuxType)
        }
    }

    private fun handleCustomKeyPress(key: io.github.tabssh.ui.keyboard.KeyboardKey) {
        Logger.d("TabTerminalActivity", "Custom key pressed: ${key.label} id=${key.id} sequence=${key.keySequence.map { it.code }}")

        // Flush any composing IME text before this key's escape sequence
        // writes to the terminal — otherwise a bar-key press can race ahead
        // of an in-flight composition and silently drop it.
        getActiveTerminalView()?.flushPendingComposing()

        // Console tabs in RFB/SPICE mode have no TerminalView — everything
        // except the bar's own activity-level actions (clipboard menu,
        // terminal menu, keyboard toggle, recording, multiplexer prefix —
        // none of which are terminal-text-input keys) must be sent to the
        // graphical session instead of falling through to the TerminalView
        // path below. Text-mode console tabs (getActiveConsoleTab() with
        // isGraphicalMode == false) fall through unchanged, same as SSH tabs.
        val consoleTab = getActiveConsoleTab()
        if (consoleTab != null && consoleTab.isGraphicalMode.value &&
            key.id !in CONSOLE_BAR_ACTION_KEY_IDS
        ) {
            sendConsoleKeyPress(consoleTab, key)
            return
        }

        val terminal = getActiveTerminalView()

        when (key.id) {
            "PASTE" -> {
                // Compat fallback for saved layouts that still contain the
                // legacy PASTE key — the default palette now uses CLIPBOARD.
                Logger.d("TabTerminalActivity", "Paste action (legacy key)")
                pasteFromClipboard()
            }
            "CLIPBOARD" -> {
                Logger.d("TabTerminalActivity", "Clipboard key — opening text operations menu")
                showClipboardMenu()
            }
            "MENU" -> {
                Logger.d("TabTerminalActivity", "Menu key — opening terminal bottom sheet")
                showTerminalMenu()
            }
            "TOGGLE" -> {
                Logger.d("TabTerminalActivity", "Toggle keyboard action")
                toggleCustomKeyboard()
            }
            "STOP_RECORDING" -> {
                Logger.d("TabTerminalActivity", "Stop recording key pressed")
                toggleRecording()
            }
            "PREFIX" -> {
                val tab = tabManager.getActiveTab()
                val type = tab?.activeMultiplexerType
                if (prefixArmed) {
                    // Second tap on PRE while already armed — cancel the latch
                    // without sending anything, mirroring CTL/ALT toggle-off.
                    prefixArmed = false
                    prefixArmedType = null
                    getActiveTerminalView()?.let { tv ->
                        tv.setPendingPrefix(null)
                        tv.onPrefixConsumed = null
                    }
                    updatePrefixKeyVisual(type)
                    Logger.d("TabTerminalActivity", "PREFIX key: disarmed (second tap)")
                } else if (type == null) {
                    // No multiplexer detected yet — the 30 s periodic probe
                    // may not have caught a multiplexer the user launched
                    // after connect. Fire a one-shot fast probe (3 s cap)
                    // before falling back to the picker dialog. If the probe
                    // succeeds, activeMultiplexerTypeFlow updates and the
                    // key visual flips to the detected type automatically —
                    // the user re-taps PRE to arm the latch.
                    val activeTab = tab
                    if (activeTab != null) {
                        lifecycleScope.launch {
                            val detected = activeTab.probeMultiplexerNow(3000L)
                            if (detected == null) {
                                showMultiplexerPickerDialog()
                            } else {
                                Logger.i("TabTerminalActivity",
                                    "PREFIX key: on-demand probe detected $detected")
                            }
                        }
                    } else {
                        showMultiplexerPickerDialog()
                    }
                } else if (tab?.isPrefixKeyEnabled == false) {
                    // User disabled the PREFIX shortcut for this connection
                    // (long-press picker's "Disable PRE key" row) — tap is a
                    // no-op until re-enabled; long-press still works
                    // regardless.
                    Logger.d("TabTerminalActivity", "PREFIX key: tap ignored (disabled)")
                } else {
                    // PRE is a shortcut for physically pressing the multiplexer
                    // bind (e.g. Ctrl-B) — so tapping it must act exactly like
                    // that keypress: send the bytes to the terminal right now,
                    // not deferred until the user's next keystroke.
                    val prefixStr = when (type) {
                        "tmux"   -> app.preferencesManager.getString(
                            "multiplexer_custom_prefix_tmux",   "C-b")
                        "screen" -> app.preferencesManager.getString(
                            "multiplexer_custom_prefix_screen", "C-a")
                        "zellij" -> app.preferencesManager.getString(
                            "multiplexer_custom_prefix_zellij", "C-g")
                        else     -> app.preferencesManager.getString(
                            "multiplexer_custom_prefix_tmux",   "C-b")
                    }
                    val bytes = io.github.tabssh.terminal.gestures.PrefixParser.parse(prefixStr)
                    if (bytes == null) {
                        Logger.w("TabTerminalActivity", "PREFIX key: failed to parse prefix '$prefixStr'")
                        return
                    }
                    prefixArmed = true
                    prefixArmedType = type
                    getActiveTerminalView()?.let { tv ->
                        tv.sendText(String(bytes, Charsets.ISO_8859_1))
                        // Arm only the visual-disarm latch — tmux/screen is now
                        // server-side waiting for the next keystroke regardless
                        // of anything the app does; this just clears the [PRE]
                        // button visual when that keystroke arrives.
                        tv.setPendingPrefix(bytes)
                        tv.onPrefixConsumed = {
                            prefixArmed = false
                            prefixArmedType = null
                            updatePrefixKeyVisual(tabManager.getActiveTab()?.activeMultiplexerType)
                        }
                    }
                    updatePrefixKeyVisual(type)
                    Logger.d("TabTerminalActivity", "PREFIX key: sent $type ($prefixStr)")
                }
            }
            else -> {
                // If the PREFIX latch is armed, the bytes were already sent to
                // the terminal at PRE-tap time — this only clears the armed
                // visual. TerminalView's consumePendingPrefix() fires
                // automatically from onKeyDown() and commitText(); we only
                // need to clear the Activity-side state here so the visual
                // deactivation happens correctly when the latch fires from a
                // custom-bar key (which bypasses those TerminalView paths).
                if (prefixArmed) {
                    val consumedType = prefixArmedType
                    prefixArmed = false
                    prefixArmedType = null
                    getActiveTerminalView()?.let { tv ->
                        // The prefix bytes were already sent at PRE-tap time —
                        // consumePendingPrefix() here only fires onPrefixConsumed
                        // to clear the visual, handling the custom-bar key path.
                        // For hardware/IME keys it was already called — this is
                        // then a no-op since the latch is already disarmed.
                        tv.consumePendingPrefix()
                        tv.onPrefixConsumed = null
                    }
                    updatePrefixKeyVisual(tabManager.getActiveTab()?.activeMultiplexerType)
                    if (consumedType != null) {
                        Logger.d(
                            "TabTerminalActivity",
                            "PREFIX latch: consumed for $consumedType before ${key.label}"
                        )
                    }
                }
                if (key.keySequence.isNotEmpty()) {
                    // When SFT is latched, produce the standard xterm Shift+key
                    // escape sequences for navigation and function keys. Single-char
                    // keys fall through to sendCharWithPendingModifier below which
                    // uppercases them. Shift+Arrow uses the CSI modifier-param form
                    // even in DECCKM mode — xterm does the same.
                    val shiftActive = terminal?.isPendingShift() == true
                    val seq: String = if (shiftActive) {
                        when (key.id) {
                            "UP"    -> "[1;2A"
                            "DOWN"  -> "[1;2B"
                            "RIGHT" -> "[1;2C"
                            "LEFT"  -> "[1;2D"
                            "TAB"   -> "[Z"
                            "HOME"  -> "[1;2H"
                            "END"   -> "[1;2F"
                            "PGUP"  -> "[5;2~"
                            "PGDN"  -> "[6;2~"
                            "F1"    -> "[1;2P"
                            "F2"    -> "[1;2Q"
                            "F3"    -> "[1;2R"
                            "F4"    -> "[1;2S"
                            "F5"    -> "[15;2~"
                            "F6"    -> "[17;2~"
                            "F7"    -> "[18;2~"
                            "F8"    -> "[19;2~"
                            "F9"    -> "[20;2~"
                            "F10"   -> "[21;2~"
                            "F11"   -> "[23;2~"
                            "F12"   -> "[24;2~"
                            else    -> key.keySequence
                        }
                    } else if (terminal?.isApplicationCursorKeysMode() == true &&
                        (key.id == "HOME" || key.id == "END")) {
                        // HOME/END must respect DECCKM just like the arrows: the
                        // xterm-256color terminfo defines khome=\EOH / kend=\EOF
                        // (SS3), so vim/less only recognise the SS3 form when they
                        // have enabled application cursor keys (\033[?1h).
                        when (key.id) {
                            "HOME" -> "OH"
                            "END"  -> "OF"
                            else   -> key.keySequence
                        }
                    } else if (key.category == io.github.tabssh.ui.keyboard.KeyboardKey.KeyCategory.ARROW &&
                        // ARROW keys must respect DECCKM: when application cursor key mode
                        // is active (\033[?1h), arrows use SS3 (\033OA) not CSI (\033[A).
                        terminal?.isApplicationCursorKeysMode() == true) {
                        when (key.id) {
                            "UP"    -> "OA"
                            "DOWN"  -> "OB"
                            "RIGHT" -> "OC"
                            "LEFT"  -> "OD"
                            else    -> key.keySequence
                        }
                    } else {
                        key.keySequence
                    }
                    // If the bar has CTL/ALT/SFT latched and the key is a single
                    // ASCII character (typical for symbol/letter keys), apply
                    // the modifier here so chords like CTL+/ also work from
                    // the bar even without the IME path.
                    val applied = if (seq.length == 1 &&
                        terminal != null &&
                        terminal.sendCharWithPendingModifier(seq[0])
                    ) {
                        true
                    } else {
                        false
                    }
                    if (!applied) {
                        terminal?.sendText(seq)
                    }
                } else {
                    Logger.w("TabTerminalActivity", "Key ${key.label} has empty sequence")
                }
            }
        }
    }
    
    private fun toggleCustomKeyboard() {
        // Toggle the system soft keyboard when user taps keyboard icon
        // This lets users show/hide the main keyboard while keeping custom bar visible
        toggleKeyboard()
    }

    private fun hideCustomKeyboardBar() {
        customKeyboardVisible = false
        binding.multiRowKeyboard.visibility = android.view.View.GONE
        app.preferencesManager.setBoolean(PREF_KEY_BAR_VISIBLE, false)
    }

    private fun showCustomKeyboardBar() {
        customKeyboardVisible = true
        binding.multiRowKeyboard.visibility = android.view.View.VISIBLE
        app.preferencesManager.setBoolean(PREF_KEY_BAR_VISIBLE, true)
    }

}
