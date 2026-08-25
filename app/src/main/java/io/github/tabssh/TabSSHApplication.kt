package io.github.tabssh

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import io.github.tabssh.storage.database.TabSSHDatabase
import java.lang.ref.WeakReference
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.crypto.keys.KeyStorage
import io.github.tabssh.ssh.connection.SSHSessionManager
import io.github.tabssh.terminal.emulator.TerminalManager
import io.github.tabssh.themes.definitions.ThemeManager
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.performance.PerformanceManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Main application class for TabSSH
 * Handles application-level initialization and dependency injection
 */
class TabSSHApplication : Application() {

    companion object {
        const val STARTUP_PREFS = "tabssh_startup"
        const val KEY_STARTUP_ERROR = "startup_error"
        const val KEY_LAST_CRASH   = "last_crash"
        const val KEY_CRASH_THREAD = "crash_thread"
        const val KEY_CRASH_TIME   = "crash_time"
        const val KEY_LAST_LOGGED_COMMIT = "last_logged_commit"

        // One-time migration guard + legacy pref key for the removed global
        // "Enable PRE Key" toggle — see migrateLegacyPrefixKeyPref().
        private const val KEY_PRE_KEY_GLOBAL_MIGRATED = "pre_key_global_migrated"
        private const val KEY_INLINE_PROXY_ROUTE_MIGRATED = "inline_proxy_route_migrated"
        private const val KEY_CONNECTION_PASSWORDS_MIGRATED = "connection_passwords_keystore_migrated"
        private const val KEY_OCI_ACCOUNT_SECRETS_MIGRATED = "oci_account_secrets_migrated"
        private const val KEY_CONTAINER_HOST_ALIASES_MIGRATED = "container_host_aliases_migrated"
        private const val KEY_CONTAINER_NAMING_MIGRATED = "container_naming_migrated"
        private const val KEY_LEGACY_CONTAINER_WORK_CANCELLED = "legacy_docker_update_work_cancelled"
        private const val LEGACY_KEY_PREFIX_KEY_ENABLED = "terminal_prefix_key_enabled"

        // Docker-only identifiers replaced by the engine-agnostic container
        // naming — see migrateDockerNamingToContainer().
        private const val LEGACY_KEY_DOCKER_UPDATE_CHECK = "docker_update_check_enabled"
        private const val KEY_CONTAINER_UPDATE_CHECK = "container_update_check_enabled"
        private const val LEGACY_KEY_SYNC_DOCKER = "sync_docker"
        private const val KEY_SYNC_CONTAINERS = "sync_containers"
        private const val LEGACY_TOMBSTONE_DOCKER_HOST = "docker_host"

        // Intent extras used to pass crash data directly to CrashReportActivity.
        // Preferred over SharedPreferences because they are in-process and not
        // subject to disk-write races; SharedPreferences is kept as a fallback.
        const val EXTRA_CRASH_TRACE  = "extra_crash_trace"
        const val EXTRA_CRASH_THREAD = "extra_crash_thread"
        const val EXTRA_CRASH_TIME   = "extra_crash_time"

        // Lightweight Application singleton — set in onCreate, used by
        // helpers that don't have a Context (notably SSHTab when it
        // builds the multiplexer auto-launch command from
        // gesture_multiplexer_type). Intentionally not exposed via
        // public API; treat as a last-resort accessor.
        @Volatile private var INSTANCE: TabSSHApplication? = null
        fun get(): TabSSHApplication = INSTANCE
            ?: error("TabSSHApplication.get() called before onCreate()")
    }

    // Core components - initialized lazily
    val database by lazy { TabSSHDatabase.getDatabase(this) }
    val preferencesManager by lazy { PreferenceManager(this) }
    // Held as an explicit Lazy so teardown can ask whether it was ever built:
    // touching it from onTerminate() would otherwise open the Android Keystore
    // just to clear data that was never created
    private val securePasswordManagerLazy = lazy { SecurePasswordManager(this) }
    val securePasswordManager: SecurePasswordManager by securePasswordManagerLazy
    val keyStorage by lazy { KeyStorage(this) }
    private val sshSessionManagerLazy = lazy { SSHSessionManager(this) }
    val sshSessionManager: SSHSessionManager by sshSessionManagerLazy
    val terminalManager by lazy { TerminalManager(this) }
    val themeManager by lazy { ThemeManager(this) }
    val performanceManager by lazy { PerformanceManager(this) }
    /** Owns the lifecycle of saved, persistent port forwards (start/stop,
     *  session reuse, auto-start on boot). Standalone from connections. */
    val portForwardCoordinator by lazy {
        io.github.tabssh.ssh.forwarding.PortForwardCoordinator(this)
    }
    val auditLogManager by lazy { io.github.tabssh.audit.AuditLogManager(this, database, preferencesManager) }
    val tabManager by lazy { io.github.tabssh.ui.tabs.TabManager(database) }
    private val sessionPersistenceManagerLazy = lazy {
        io.github.tabssh.background.SessionPersistenceManager(this, tabManager)
    }
    val sessionPersistenceManager: io.github.tabssh.background.SessionPersistenceManager
        by sessionPersistenceManagerLazy
    /** App-wide network state observer. Single instance so every connection
     *  type (SSH, VNC, Telnet) shares one [ConnectivityManager] callback. */
    val networkDetector by lazy { io.github.tabssh.network.detection.NetworkDetector(this) }

    // ANR watchdog — single instance, only running when debug logging is
    // active. Public start/stop so the Settings → Logging toggle can flip
    // it in lockstep with `Logger.forceEnableDebugMode` /
    // `Logger.disableDebugMode`.
    private val anrWatchdog by lazy { io.github.tabssh.utils.diagnostics.AnrWatchdog() }
    fun startAnrWatchdog() = anrWatchdog.start()
    fun stopAnrWatchdog() = anrWatchdog.stop()

    /**
     * Issue #36 — application-wide background coroutine scope, used by
     * `onCreate` to push slow init off the main thread. SupervisorJob
     * because one component failing shouldn't cancel the others; the
     * `tryInit` wrapper inside `initializeCoreComponents` already isolates
     * exceptions per component.
     *
     * No need to ever cancel this scope — it dies with the process.
     */
    val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
    )

    // Track the current foreground activity for showing dialogs from background threads
    private var currentActivityRef: WeakReference<Activity>? = null
    private var foregroundActivityCount = 0

    // False until the PIN gate has been satisfied in this process, so a cold
    // launch with app lock on is stopped at PinLockActivity.
    private var appUnlockedThisProcess = false

    /**
     * Get the current foreground Activity.
     * Used for showing dialogs from background threads (like host key verification).
     */
    fun getCurrentActivity(): Activity? = currentActivityRef?.get()

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this

        // Android ships a stripped "BC" provider that is missing KeyFactory/RSA
        // and other algorithms. Replace it with the full external BouncyCastle
        // before any crypto code runs so every subsequent BC lookup is correct.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        // Logger init policy:
        //   - debug builds always have BuildConfig.DEBUG_LOG = true and
        //     auto-enable debug-file logging.
        //   - release / fdroidRelease builds compute DEBUG_LOG from the
        //     versionName at configure time: a clean "x.x.x" semver (e.g.
        //     "1.0.0", "0.9.2") is treated as a production release and gets
        //     DEBUG_LOG = false; anything else ("0.9.1-beta", "1.0.0-rc1",
        //     "1.2.3-devel") is a pre-production build and gets DEBUG_LOG
        //     = true so testers and beta users have logs ready when filing
        //     issues. See app/build.gradle :: isProductionRelease.
        //   - Production users can still opt in manually via Settings →
        //     Logging → "Enable Debug Logging" (pref key
        //     `debug_logging_enabled`).
        // The app log (sanitized for public sharing) is always on regardless
        // — it's safe and cheap and powers the Copy App Log menu item.
        val savedDebug = preferencesManager.isDebugLoggingEnabled()
        val debugLoggingActive = BuildConfig.DEBUG_LOG || savedDebug
        // Sync the toggle pref to match the actual active state. On debug
        // builds DEBUG_LOG forces logging on regardless of what the user
        // last set; without this sync the Settings → Logging toggle shows
        // "Off" even though the logger is running — misleading.
        if (BuildConfig.DEBUG_LOG && !savedDebug) {
            preferencesManager.setDebugLoggingEnabled(true)
        }
        Logger.initialize(this, debugLoggingActive)
        setupExceptionHandler()

        // ANR watchdog tracks debug mode — when debug logging is on we
        // catch main-thread freezes and write the captured stack trace
        // to the debug log so it shows up in Copy Debug Logs.
        if (debugLoggingActive) startAnrWatchdog()

        // Restore the user's keystroke-byte-logging opt-in (default off).
        // Static flag on TermuxBridge — gated separately from debug logging
        // because users who enable debug logs for protocol triage don't
        // necessarily want their typed sudo/ssh passwords in those logs.
        io.github.tabssh.terminal.TermuxBridge.logKeystrokeBytes =
            preferencesManager.isLogKeystrokeBytesEnabled()

        // Stamp the source-of-truth commit into the log exactly once per
        // commit-id change (install / update). Lets users grepping their
        // own log — or pasting it into a bug report — figure out which
        // build the symptoms came from without having to ask the version
        // string. Falls through silently when commit_id is the same as
        // last launch so we don't spam the log on every startup.
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        val lastLoggedCommit = prefs.getString(KEY_LAST_LOGGED_COMMIT, null)
        if (lastLoggedCommit != BuildConfig.GIT_COMMIT_ID) {
            val marker = "## apk built from: ${BuildConfig.GIT_COMMIT_ID} ##"
            Logger.i("TabSSHApplication", marker)
            Logger.d("TabSSHApplication", marker)
            prefs.edit().putString(KEY_LAST_LOGGED_COMMIT, BuildConfig.GIT_COMMIT_ID).apply()
        }

        Logger.d("TabSSHApplication", "Application starting...")

        // Apply the user's saved app theme BEFORE any Activity is created.
        // SettingsActivity's preference change handler only updates the
        // mode for the current process; without this, every cold start
        // ignores the saved value and the user perceives "only dark mode".
        applySavedAppTheme()
        applySavedAppLanguage()

        // Create notification channels
        io.github.tabssh.utils.NotificationHelper.createNotificationChannels(this)
        
        // Register activity lifecycle callbacks. Beyond foreground tracking,
        // this is also where we apply screen-level security flags (FLAG_SECURE
        // for screenshot blocking, FLAG_KEEP_SCREEN_ON for screen wake) so
        // the user-facing prefs work uniformly across every Activity without
        // each activity having to remember to call into a helper.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyWindowSecurityFlags(activity)
            }
            override fun onActivityStarted(activity: Activity) {
                foregroundActivityCount++
                maybeRequireUnlock(activity)
            }
            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                // Re-apply on resume — the prefs may have changed in
                // SettingsActivity since the activity was created.
                applyWindowSecurityFlags(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivityRef?.get() == activity) {
                    currentActivityRef = null
                }
            }
            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount--
                // Only stamp the background time when the ENTIRE app goes to
                // background (no activities left in the Started state). Stamping
                // on every individual activity stop would reset the clock on
                // normal in-app transitions (A starts B → A.onStop fires while
                // B is already started) and make the timeout never accumulate.
                if (foregroundActivityCount == 0) {
                    val prefs = androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(this@TabSSHApplication)
                    prefs.edit().putLong("ui_last_backgrounded_at", System.currentTimeMillis()).apply()
                    // Clear SESSION_ONLY in-memory passwords when the whole app
                    // goes to background. ENCRYPTED/BIOMETRIC-level passwords
                    // are not touched — only the volatile sessionPasswords map.
                    securePasswordManager.clearSensitiveData()
                    // Also zero per-connection credential caches on active SSH
                    // sessions. JSch keeps its own reference to the password for
                    // the live session; these cached copies only exist to service
                    // UserInfo callbacks. Clearing here prevents them surviving a
                    // biometric-lock event or a process cache into the background.
                    sshSessionManager.clearCachedCredentials()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Issue #36 — Move slow init off the main thread.
        //
        // The lazy delegates below (`securePasswordManager`, `themeManager`,
        // `keyStorage`, `sshSessionManager`, ...) do non-trivial work in
        // their constructors — `SecurePasswordManager` for example does an
        // eager `getSharedPreferences("tabssh_secure_storage", ...)` plus
        // `KeyStore.getInstance(...).load(null)` at field-init time. Doing
        // this on the main thread caused ANRs on update for users with
        // large saved-credentials sets. Touching the lazies from a
        // background coroutine first means Kotlin's `lazy { }` runs the
        // constructor on the background thread; later main-thread accesses
        // block on the lock but the actual I/O is already done.
        //
        // No regression if the main thread races and beats the scope: the
        // lazy fires on whichever thread hits it first, same as before.
        applicationScope.launch {
            initializeCoreComponents()
            // Single source of truth for host-key verification dialogs. Set
            // on SSHSessionManager so EVERY future connection inherits it
            // (SSHSessionManager.createConnection copies these onto each new
            // SSHConnection at construction time). Looks up the current
            // foreground Activity via currentActivityRef so the dialog
            // renders wherever the user happens to be — terminal,
            // port-forward, multi-host dashboard, SFTP, performance.
            wireGlobalHostKeyCallbacks()
            wireGlobalNotifications()
            // Drain the v13->v14 hypervisor password carry-over table into the
            // Keystore now instead of waiting for each row's next retrieve(),
            // and drop the table once it is empty.
            io.github.tabssh.crypto.storage.HypervisorPasswordStore
                .sweepLegacyPlaintext(this@TabSSHApplication)
            migrateLegacyPrefixKeyPref()
            migrateInlineProxiesToRoutes()
            migratePlaintextConnectionPasswords()
            migrateProfileKeyedOciSecrets()
            migrateContainerHostAliases()
            migrateDockerNamingToContainer()
            // Re-register periodic sync work on every cold start. WorkManager's
            // DB survives process death but can be wiped by reinstall or system
            // maintenance. Re-registering is idempotent when
            // ExistingPeriodicWorkPolicy.KEEP is used.
            val syncEnabled = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this@TabSSHApplication)
                .getBoolean("sync_enabled", false)
            if (syncEnabled) {
                io.github.tabssh.sync.worker.SyncWorkScheduler(this@TabSSHApplication)
                    .schedulePeriodicSync()
            }
            Logger.i("TabSSHApplication", "Application initialized successfully (background)")
        }
    }

    /**
     * One-time migration for the removed global "Enable PRE Key" toggle
     * (`terminal_prefix_key_enabled`, PreferenceManager). PRE key
     * enablement is now per-connection only (SSHTab.isPrefixKeyEnabled /
     * ConnectionProfile.multiplexerOverride == "off"). A user who had
     * turned the global toggle off expects every connection that never had
     * its own override set to keep behaving as "off"; connections with an
     * explicit override (including a pinned multiplexer type) are left
     * untouched. Guarded by a flag so this runs at most once, and is a
     * no-op on every later launch — including for users who never had the
     * legacy pref set (defaulted true, nothing to migrate).
     */
    private suspend fun migrateLegacyPrefixKeyPref() {
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PRE_KEY_GLOBAL_MIGRATED, false)) return
        val legacyPrefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
        val legacyEnabled = legacyPrefs.getBoolean(LEGACY_KEY_PREFIX_KEY_ENABLED, true)
        if (!legacyEnabled) {
            val migratedCount = database.connectionDao().disablePrefixKeyForProfilesWithNullOverride()
            Logger.i(
                "TabSSHApplication",
                "Migrated legacy global PRE key off-toggle to $migratedCount profile(s)"
            )
        }
        prefs.edit().putBoolean(KEY_PRE_KEY_GLOBAL_MIGRATED, true).apply()
    }

    /**
     * One-time Routing & Forwarding migration: copy each connection's legacy
     * inline proxy/jump config into a reusable NetworkRoute and link it via
     * route_id (see InlineProxyRouteMigration). Guarded by a flag so it runs at
     * most once; the underlying query is itself idempotent (already-routed
     * rows are skipped), so this is a belt-and-suspenders no-op on later
     * launches and for users who never configured a proxy.
     */
    private suspend fun migrateInlineProxiesToRoutes() {
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INLINE_PROXY_ROUTE_MIGRATED, false)) return
        try {
            io.github.tabssh.storage.database.InlineProxyRouteMigration.run(database)
        } catch (e: Exception) {
            Logger.e("TabSSHApplication", "Inline proxy → route migration failed", e)
            return
        }
        prefs.edit().putBoolean(KEY_INLINE_PROXY_ROUTE_MIGRATED, true).apply()
    }

    /**
     * One-time secret migration: move every plaintext
     * `password_{connectionId}` value the old backup/sync layer wrote into
     * the default SharedPreferences file over to the Keystore, then delete
     * the plaintext key. The runtime SSH path has always read the Keystore
     * under the bare connection id, so this only reunites the two copies —
     * it never changes which password authenticates.
     *
     * The done-flag is only set once every legacy key is gone, so a Keystore
     * failure defers the affected entries to the next launch rather than
     * dropping them.
     */
    private suspend fun migratePlaintextConnectionPasswords() {
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CONNECTION_PASSWORDS_MIGRATED, false)) return
        val complete = try {
            io.github.tabssh.crypto.storage.LegacySecretMigrations
                .migratePlaintextConnectionPasswords(
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(this),
                    securePasswordManager
                )
        } catch (e: Exception) {
            Logger.e("TabSSHApplication", "Plaintext connection password migration failed", e)
            false
        }
        if (complete) prefs.edit().putBoolean(KEY_CONNECTION_PASSWORDS_MIGRATED, true).apply()
    }

    /**
     * One-time secret migration: move profile-keyed OCI API-key secrets onto
     * the account-scoped aliases, creating and linking the HypervisorAccount
     * when the profile has none. Nothing writes the profile-keyed aliases any
     * more and no read path is left for them, so this is the only way a
     * dev-build install keeps its OCI key.
     */
    private suspend fun migrateProfileKeyedOciSecrets() {
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OCI_ACCOUNT_SECRETS_MIGRATED, false)) return
        val complete = try {
            io.github.tabssh.crypto.storage.LegacySecretMigrations
                .migrateProfileKeyedOciSecrets(database, securePasswordManager)
        } catch (e: Exception) {
            Logger.e("TabSSHApplication", "Profile-keyed OCI secret migration failed", e)
            false
        }
        if (complete) prefs.edit().putBoolean(KEY_OCI_ACCOUNT_SECRETS_MIGRATED, true).apply()
    }

    /**
     * One-time secret migration for the Docker → container rename: move every
     * custom-endpoint host password from the `docker_host_{id}` Keystore alias
     * onto `container_host_{id}`, the only alias the auth path reads now.
     *
     * The done-flag is set only once no legacy alias remains, so a Keystore
     * failure retries on the next launch instead of dropping the password.
     */
    private suspend fun migrateContainerHostAliases() {
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CONTAINER_HOST_ALIASES_MIGRATED, false)) return
        val complete = try {
            io.github.tabssh.crypto.storage.LegacySecretMigrations
                .migrateContainerHostAliases(securePasswordManager)
        } catch (e: Exception) {
            Logger.e("TabSSHApplication", "Container host alias migration failed", e)
            false
        }
        if (complete) prefs.edit().putBoolean(KEY_CONTAINER_HOST_ALIASES_MIGRATED, true).apply()
    }

    /**
     * One-time rename migration for the non-Keystore Docker-only identifiers
     * the engine-agnostic container feature replaced:
     *
     *  - the `docker_update_check_enabled` preference, whose value is copied to
     *    `container_update_check_enabled` before the old key is removed, so a
     *    user who turned the update checker off keeps it off;
     *  - the `sync_docker` preference, copied to `sync_containers` the same way
     *    so a user who excluded container data from sync keeps it excluded;
     *  - persisted `sync_tombstones` rows still typed `docker_host`, re-recorded
     *    under `container_host` with their original deletedAt/deviceId so the
     *    deletion keeps winning last-write-wins against a stale peer copy.
     *
     * Every part is idempotent; the done-flag is set only when all of them complete.
     */
    private suspend fun migrateDockerNamingToContainer() {
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CONTAINER_NAMING_MIGRATED, false)) return
        val complete = try {
            migrateDockerUpdateCheckPref()
            migrateSyncDockerPref()
            migrateDockerHostTombstones()
            true
        } catch (e: Exception) {
            Logger.e("TabSSHApplication", "Docker → container naming migration failed", e)
            false
        }
        if (complete) prefs.edit().putBoolean(KEY_CONTAINER_NAMING_MIGRATED, true).apply()
    }

    private fun migrateDockerUpdateCheckPref() {
        val defaultPrefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
        if (!defaultPrefs.contains(LEGACY_KEY_DOCKER_UPDATE_CHECK)) return
        val enabled = defaultPrefs.getBoolean(LEGACY_KEY_DOCKER_UPDATE_CHECK, true)
        defaultPrefs.edit()
            .putBoolean(KEY_CONTAINER_UPDATE_CHECK, enabled)
            .remove(LEGACY_KEY_DOCKER_UPDATE_CHECK)
            .apply()
        Logger.i("TabSSHApplication", "Migrated update-check preference to container_update_check_enabled")
    }

    private fun migrateSyncDockerPref() {
        val defaultPrefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
        if (!defaultPrefs.contains(LEGACY_KEY_SYNC_DOCKER)) return
        val enabled = defaultPrefs.getBoolean(LEGACY_KEY_SYNC_DOCKER, true)
        defaultPrefs.edit()
            .putBoolean(KEY_SYNC_CONTAINERS, enabled)
            .remove(LEGACY_KEY_SYNC_DOCKER)
            .apply()
        Logger.i("TabSSHApplication", "Migrated sync toggle preference to sync_containers")
    }

    /**
     * Cancel the periodic update-check work still registered under the development build's
     * unique name. Renaming [io.github.tabssh.background.ContainerUpdateCheckWorker.WORK_NAME]
     * alone would leave that registration enqueued forever, running a second checker cycle
     * beside the new one. Runs before the new work is enqueued and is guarded by its own
     * one-time flag so a normal cold start does not touch the WorkManager DB.
     */
    private fun cancelLegacyDockerUpdateWork() {
        val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LEGACY_CONTAINER_WORK_CANCELLED, false)) return
        androidx.work.WorkManager.getInstance(this)
            .cancelUniqueWork(io.github.tabssh.background.ContainerUpdateCheckWorker.LEGACY_WORK_NAME)
        prefs.edit().putBoolean(KEY_LEGACY_CONTAINER_WORK_CANCELLED, true).apply()
        Logger.i("TabSSHApplication", "Cancelled legacy docker_update_check periodic work")
    }

    private suspend fun migrateDockerHostTombstones() {
        val dao = database.syncTombstoneDao()
        val legacy = dao.getByType(LEGACY_TOMBSTONE_DOCKER_HOST)
        if (legacy.isEmpty()) return
        dao.recordAll(
            legacy.map {
                it.copy(entityType = io.github.tabssh.sync.tombstone.TombstoneRecorder.CONTAINER_HOST)
            }
        )
        legacy.forEach { dao.clear(LEGACY_TOMBSTONE_DOCKER_HOST, it.entityKey) }
        Logger.i("TabSSHApplication", "Retyped ${legacy.size} docker_host tombstones to container_host")
    }

    private fun wireGlobalNotifications() {
        // Per-host connect/disconnect notifications are handled entirely by
        // SSHConnectionService via renderHostNotification(). Posting a
        // second showDisconnected() here produced a duplicate notification
        // for the same host — the legacy aggregate ID 2001 shadowing the
        // per-host silent channel notification. Nothing to do here now.
    }

    private fun wireGlobalHostKeyCallbacks() {
        sshSessionManager.newHostKeyCallback = { info ->
            promptHostKey(
                title = "New Host Key",
                message = info.getDisplayMessage(),
                changedHost = false,
            )
        }
        sshSessionManager.hostKeyChangedCallback = { info ->
            promptHostKey(
                title = "⚠️ Host Key CHANGED",
                message = info.getDisplayMessage(),
                changedHost = true,
            )
        }
    }

    /**
     * Block the calling (background) thread on a UI dialog and return the
     * user's decision. SSH connect is on Dispatchers.IO so the latch wait
     * is safe; the dialog itself is shown on the foreground activity via
     * currentActivityRef.
     *
     * Falls back to REJECT_CONNECTION if no foreground activity is
     * available (app backgrounded mid-connect, or first launch racing
     * with an auto-restored multi-host pump).
     */
    private fun promptHostKey(
        title: String,
        message: String,
        changedHost: Boolean,
    ): io.github.tabssh.ssh.connection.HostKeyAction {
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Logger.w("TabSSHApplication",
                "No foreground activity to show host-key dialog — rejecting (${if (changedHost) "changed" else "new"})")
            return io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
        }
        var userAction = io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
        val latch = java.util.concurrent.CountDownLatch(1)
        activity.runOnUiThread {
            try {
                val builder = MaterialAlertDialogBuilder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setOnDismissListener { latch.countDown() }
                if (changedHost) {
                    // MITM warning — make Reject the prominent choice.
                    builder
                        .setNegativeButton("Reject (recommended)") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
                            latch.countDown()
                        }
                        .setPositiveButton("Accept new key & save") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_NEW_KEY
                            latch.countDown()
                        }
                        .setNeutralButton("Accept once") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_ONCE
                            latch.countDown()
                        }
                } else {
                    // First-time host — Accept-and-save is the common path.
                    builder
                        .setPositiveButton("Accept & save") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_NEW_KEY
                            latch.countDown()
                        }
                        .setNeutralButton("Accept once") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_ONCE
                            latch.countDown()
                        }
                        .setNegativeButton("Reject") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
                            latch.countDown()
                        }
                }
                builder.show()
            } catch (e: Exception) {
                Logger.e("TabSSHApplication", "Failed to show host-key dialog", e)
                latch.countDown()
            }
        }
        try { latch.await() } catch (_: InterruptedException) {}
        return userAction
    }
    
    private fun initializeCoreComponents() {
        // Clear any previous startup errors
        getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE).edit().remove(KEY_STARTUP_ERROR).apply()

        fun tryInit(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                val msg = "$name: ${e::class.simpleName}: ${e.message}"
                Logger.e("TabSSHApplication", "Failed to initialize $name", e)
                // Persist error so MainActivity can surface it on-screen without ADB
                val prefs = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE)
                val existing = prefs.getString(KEY_STARTUP_ERROR, "")
                prefs.edit().putString(
                    KEY_STARTUP_ERROR,
                    if (existing.isNullOrEmpty()) msg else "$existing\n$msg"
                ).apply()
            }
        }

        tryInit("Preferences")  { preferencesManager.initialize() }
        tryInit("Themes")       { themeManager.initialize() }
        tryInit("Passwords")    { securePasswordManager.initialize() }
        tryInit("KeyStorage")   { keyStorage.initialize() }
        tryInit("SSHSession")   { sshSessionManager.initialize() }
        tryInit("Terminal")     { terminalManager.initialize() }
        tryInit("Performance")  { performanceManager.initialize() }
        // Issue #158 — pre-open the Room DB on the background scope so the
        // first Flow subscription from ConnectionsFragment doesn't pay for
        // open + migrations on the main thread. Touching writableDatabase
        // forces SupportSQLiteOpenHelper to run the migration chain now.
        tryInit("Database")     { database.openHelper.writableDatabase }

        // Schedule background host availability checks via WorkManager.
        // Uses ExistingPeriodicWorkPolicy.KEEP — idempotent, safe to call on
        // every cold start. The master "monitoring_enabled" pref is checked
        // inside the worker itself so we don't need to conditionalize here.
        tryInit("HostMonitor") {
            io.github.tabssh.background.HostAvailabilityWorker.schedule(this)
        }

        // Must run BEFORE the scheduling below: the periodic work registered
        // under the old unique name would otherwise keep firing alongside the
        // new one for the lifetime of the install.
        tryInit("LegacyContainerWorkCancel") {
            cancelLegacyDockerUpdateWork()
        }

        // Schedule periodic container image update checks (12 h cycle). Same
        // KEEP-policy idempotence as HostMonitor; the master
        // "container_update_check_enabled" pref is checked inside the worker.
        tryInit("ContainerUpdateCheck") {
            io.github.tabssh.background.ContainerUpdateCheckWorker.schedule(this)
        }

        // Bring up enabled auto-start port forwards on cold start too (not just
        // on boot). Unique work + network constraint make this idempotent and
        // safe on every launch; the enabled/auto-start filter lives in the DAO.
        tryInit("PortForwardAutoStart") {
            io.github.tabssh.background.PortForwardStartupWorker.schedule(this)
        }

        tryInit("RenewalReminder") {
            io.github.tabssh.background.RenewalReminderWorker.schedule(this)
        }

        tryInit("SessionPersistence") {
            registerActivityLifecycleCallbacks(sessionPersistenceManager)
        }

        Logger.d("TabSSHApplication", "Core components initialized")
    }
    
    /**
     * App-lock guard — invoked from `onActivityStarted` for every Activity.
     *
     * Two triggers, both requiring a configured PIN (`app_lock_enabled`):
     *  - cold launch: the first Activity of a new process is gated, which is
     *    what the "Prompt for PIN on app launch" setting promises. Before
     *    this existed, a PIN only ever did anything when the separate
     *    "Lock When Backgrounded" switch was also on, so setting a PIN and
     *    relaunching walked straight into the app.
     *  - return from background: `security_auto_lock_background` on AND the
     *    app was away longer than `security_auto_lock_timeout` seconds.
     *
     * Skipped for the PinLockActivity itself (would loop).
     */
    private fun maybeRequireUnlock(activity: Activity) {
        try {
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
            if (!prefs.getBoolean("app_lock_enabled", false)) return
            if (activity::class.java.simpleName == "PinLockActivity") return
            // App lock with no stored hash cannot be satisfied — PinLockActivity
            // clears the flag in that state; don't gate on it here.
            if (prefs.getString("app_lock_pin_hash", "").isNullOrBlank()) return

            val coldLaunch = !appUnlockedThisProcess
            if (!coldLaunch) {
                if (!prefs.getBoolean("security_auto_lock_background", false)) return
                val backgroundedAt = prefs.getLong("ui_last_backgrounded_at", 0L)
                if (backgroundedAt == 0L) return
                val timeoutSec = (prefs.getString("security_auto_lock_timeout", "300") ?: "300")
                    .toIntOrNull() ?: 300
                val elapsed = (System.currentTimeMillis() - backgroundedAt) / 1000
                if (elapsed < timeoutSec) return
                Logger.i("TabSSHApplication",
                    "Auto-lock triggered after ${elapsed}s background (limit ${timeoutSec}s)")
            } else {
                Logger.i("TabSSHApplication", "App lock: PIN required on launch")
            }
            // Reset the timestamp so we don't re-trigger immediately if the
            // PIN screen itself moves through the lifecycle hooks.
            prefs.edit().putLong("ui_last_backgrounded_at", 0L).apply()

            val intent = io.github.tabssh.ui.activities.PinLockActivity
                .verifyIntent(activity)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Logger.w("TabSSHApplication", "maybeRequireUnlock failed: ${e.message}")
        }
    }

    /**
     * Called by [io.github.tabssh.ui.activities.PinLockActivity] once the PIN
     * has been accepted (or set), so the cold-launch gate in
     * [maybeRequireUnlock] stops firing for the rest of this process.
     */
    fun markAppUnlocked() {
        appUnlockedThisProcess = true
    }

    private fun applyWindowSecurityFlags(activity: Activity) {
        try {
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
            val window = activity.window ?: return
            // Block screenshots / screen recording when the user opts in.
            if (prefs.getBoolean("security_prevent_screenshots", false)) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }

            // Keep the screen on globally when the pref says so. Per-activity
            // overrides (e.g. terminal-only) still apply on top of this.
            if (prefs.getBoolean("keep_screen_on", false)) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (e: Exception) {
            Logger.w("TabSSHApplication", "applyWindowSecurityFlags failed: ${e.message}")
        }
    }

    private fun applySavedAppLanguage() {
        // Apply the locale stored under "app_language" at startup so the setting
        // survives process death. Empty string / "system" clears the override
        // and follows the device locale.
        try {
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
            val lang = prefs.getString("app_language", "en") ?: "en"
            val localeList = if (lang.isBlank() || lang == "system") {
                androidx.core.os.LocaleListCompat.getEmptyLocaleList()
            } else {
                androidx.core.os.LocaleListCompat.forLanguageTags(lang)
            }
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeList)
            Logger.d("TabSSHApplication", "Applied saved language: $lang")
        } catch (e: Exception) {
            Logger.w("TabSSHApplication", "Failed to apply saved language: ${e.message}")
        }
    }

    private fun applySavedAppTheme() {
        // Read directly via PreferenceManager — same key as SettingsActivity
        // (preferences_general.xml: `android:key="app_theme"`). Mode values:
        //   "light"  → MODE_NIGHT_NO
        //   "dark"   → MODE_NIGHT_YES
        //   "system" → MODE_NIGHT_FOLLOW_SYSTEM (Android 10+) or AUTO_BATTERY
        // Fallback default is "dark" per AI.md PART 7 ("dark mode default"),
        // matching preferences_general.xml's android:defaultValue.
        try {
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(this)
            val theme = prefs.getString("app_theme", "dark") ?: "dark"
            val mode = when (theme) {
                "light"  -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark"   -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else     -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            Logger.d("TabSSHApplication", "Applied saved theme: $theme (mode=$mode)")
        } catch (e: Exception) {
            Logger.w("TabSSHApplication", "Failed to apply saved theme: ${e.message}")
        }
    }

    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Write synchronously before anything else
            Logger.writeCrashSync(thread, throwable)
            Logger.e("TabSSHApplication", "Uncaught exception in thread ${thread.name}", throwable)

            if (BuildConfig.DEBUG_LOG) {
                try {
                    val trace     = android.util.Log.getStackTraceString(throwable)
                    val crashTime = System.currentTimeMillis()

                    // Primary path: pass data directly via Intent extras (in-process,
                    // no disk I/O, no race between write and read).
                    // Secondary path: SharedPreferences on disk as a fallback for the
                    // case where Android restarts CrashReportActivity in a new process.
                    // Sanitize before persisting to disk — the Intent extra
                    // path below stays raw for in-process developer
                    // readability, but the SharedPreferences fallback
                    // survives a process restart and must not carry
                    // hostnames/IPs/credentials in plaintext.
                    getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_LAST_CRASH,   Logger.sanitize(trace))
                        .putString(KEY_CRASH_THREAD, thread.name)
                        .putLong(KEY_CRASH_TIME,     crashTime)
                        // synchronous; ignore return value — Intent extras are primary
                        .commit()

                    startActivity(
                        android.content.Intent(
                            this,
                            io.github.tabssh.ui.activities.CrashReportActivity::class.java
                        ).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            putExtra(EXTRA_CRASH_TRACE,  trace)
                            putExtra(EXTRA_CRASH_THREAD, thread.name)
                            putExtra(EXTRA_CRASH_TIME,   crashTime)
                        }
                    )
                } catch (e: Exception) {
                    // Crash reporter itself failed — fall through to default handler below
                    defaultHandler?.uncaughtException(thread, throwable)
                }
                // Do NOT call defaultHandler in debug mode.
                // Keeping the process alive lets the crash screen stay on screen
                // so the developer can read the trace and tap Copy/Share.
                return@setDefaultUncaughtExceptionHandler
            }

            // Release build: clean up and let Android handle it normally
            try {
                securePasswordManager.clearSensitiveDataOnCrash()
                // The default handler below is about to terminate the process, so
                // there is no later point at which these connections could be
                // closed asynchronously — block briefly here to flush them first.
                runBlocking { sshSessionManager.closeAllConnections() }
            } catch (e: Exception) {
                Logger.w("TabSSHApplication", "Cleanup on crash failed: ${e.message}", e)
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
    
    override fun onTerminate() {
        Logger.d("TabSSHApplication", "Application terminating...")

        // Only tear down what this process actually built — instantiating a
        // component here would do real work (open the Keystore, start a
        // persistence worker) on the way out and can fail outright
        if (sessionPersistenceManagerLazy.isInitialized()) {
            sessionPersistenceManager.cleanup()
        }
        if (sshSessionManagerLazy.isInitialized()) {
            // onTerminate() has no coroutine continuation past this call — the
            // process is being torn down, so connections must be closed
            // synchronously here rather than fired off into a scope that may
            // never get to run.
            runBlocking { sshSessionManager.closeAllConnections() }
        }
        if (securePasswordManagerLazy.isInitialized()) {
            securePasswordManager.clearSensitiveData()
        }

        super.onTerminate()
    }
    
    // TRIM_MEMORY_MODERATE/TRIM_MEMORY_COMPLETE are deprecated (API 34) with no
    // replacement constant carrying the same meaning — apps targeting SDK 34+
    // simply stop receiving them reliably, so this branch is kept as a
    // best-effort trim for devices/configurations that still deliver them.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        when (level) {
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE -> {
                // App is in background, trim memory usage
                terminalManager.trimInactiveTerminals()
                themeManager.clearCache()
                Logger.d("TabSSHApplication", "Memory trimmed due to level $level")
            }
        }
    }
}
