package io.github.tabssh.protocols.mosh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.tabssh.utils.logging.Logger
import java.io.File

/**
 * Wave 9.1 — Real Mosh by **handing off to Termux's RUN_COMMAND service**.
 *
 * Why this path: Termux ships an actively-maintained `mosh-client` ARM/x86
 * binary as the `mosh` apt package (depends on abseil-cpp, libprotobuf,
 * ncurses, openssl, libandroid-support — none of which we want to bundle
 * ourselves until cross-compile infrastructure is in place). Termux exposes
 * a `RUN_COMMAND` intent that any app can use to ask Termux to run any
 * binary in its `/data/data/com.termux/files/usr/bin/` PATH.
 *
 * The terminal session lives **in Termux**, not in TabSSH. That's an
 * honest UX trade-off: the user gets real Mosh (UDP transport, roaming,
 * predictive echo) in exchange for switching to Termux's terminal.
 * In-app Mosh requires bundling the binary + its .so deps and is tracked
 * as the next-session item (`mosh/build-android.sh`).
 *
 * Requirements on the user side:
 *   1. Install Termux (F-Droid is the recommended source).
 *   2. `pkg install mosh` inside Termux at least once.
 *   3. Set `allow-external-apps=true` in `~/.termux/termux.properties`
 *      (Termux refuses RUN_COMMAND intents otherwise).
 *
 * Returns concrete state via [Status] so the UI can render the right
 * message — install Termux, install mosh, enable external-apps, etc.
 */
object TermuxMoshLauncher {

    private const val TAG = "TermuxMoshLauncher"

    const val TERMUX_PKG = "com.termux"
    const val TERMUX_RUN_COMMAND_PERM = "com.termux.permission.RUN_COMMAND"
    const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    const val MOSH_CLIENT_PATH = "/data/data/com.termux/files/usr/bin/mosh-client"
    const val TERMUX_PROPS_HINT = "~/.termux/termux.properties"

    sealed class Status {
        /** Termux not installed at all. Show "Install Termux" CTA. */
        object TermuxMissing : Status()
        /** Termux installed but mosh-client binary not present. Show "pkg install mosh". */
        object MoshNotInstalled : Status()
        /** Cannot tell — can happen on devices that hide other-app data dirs. Try anyway. */
        object Unknown : Status()
        /** Ready to launch. */
        object Ready : Status()
    }

    /** Probe what's installed without actually trying to launch. */
    fun status(context: Context): Status {
        val pm = context.packageManager
        val installed = try {
            pm.getPackageInfo(TERMUX_PKG, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return Status.TermuxMissing

        // We can't read /data/data/com.termux/... directly from a different
        // app's UID. canRead() will fail under SELinux even when the file
        // exists. Treat the absence of read access as "Unknown" rather than
        // "missing".
        val moshFile = File(MOSH_CLIENT_PATH)
        return when {
            moshFile.canExecute() -> Status.Ready
            moshFile.exists() -> Status.Ready // exists but not executable from our UID — Termux can still run it
            else -> Status.Unknown // most likely; SELinux blocks the existence check
        }
    }

    /**
     * Launch `mosh-client host port` inside Termux with `MOSH_KEY` set.
     * Returns true if the intent was dispatched (NOT if mosh actually
     * connected — that happens asynchronously in Termux).
     *
     * The user must have granted `com.termux.permission.RUN_COMMAND`. If
     * not granted, Android silently drops the intent — caller should
     * surface a "grant Termux RUN_COMMAND" message via [Status].
     */
    fun launch(
        context: Context,
        host: String,
        port: Int,
        keyBase64: String,
        username: String? = null
    ): Boolean {
        val intent = Intent(TERMUX_RUN_COMMAND_ACTION).apply {
            component = ComponentName(TERMUX_PKG, TERMUX_RUN_COMMAND_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", MOSH_CLIENT_PATH)
            // mosh-client takes "host port" positionally and reads MOSH_KEY env.
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf(host, port.toString())
            )
            putExtra(
                "com.termux.RUN_COMMAND_BACKGROUND",
                false // foreground — show in Termux's terminal
            )
            // SESSION_ACTION 0 = open new session and switch to it.
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            // Termux env var passthrough — mosh-client looks at MOSH_KEY.
            putExtra(
                "com.termux.RUN_COMMAND_ENVIRONMENT",
                arrayOf("MOSH_KEY=$keyBase64")
            )
            if (username != null) {
                // Surface in label so the user can tell sessions apart.
                putExtra("com.termux.RUN_COMMAND_LABEL", "mosh: $username@$host")
            }
        }
        return try {
            context.startService(intent)
            Logger.i(TAG, "Dispatched mosh-client intent: $username@$host:$port (Termux)")
            true
        } catch (e: SecurityException) {
            Logger.w(TAG, "Termux refused RUN_COMMAND — likely missing permission or allow-external-apps not enabled: ${e.message}")
            false
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to dispatch mosh launch", e)
            false
        }
    }

    /** Helper to launch the Play / F-Droid listing for Termux when the user
     *  doesn't have it. Tries Play first, falls back to web. */
    fun openTermuxListing(context: Context) {
        val playUri = android.net.Uri.parse("market://details?id=$TERMUX_PKG")
        val playIntent = Intent(Intent.ACTION_VIEW, playUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (playIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(playIntent)
            return
        }
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("https://f-droid.org/en/packages/$TERMUX_PKG/")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { context.startActivity(webIntent) } catch (_: Exception) {}
    }
}
