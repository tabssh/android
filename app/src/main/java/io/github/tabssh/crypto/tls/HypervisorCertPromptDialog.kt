package io.github.tabssh.crypto.tls

import io.github.tabssh.utils.logging.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase-2 user prompt for the hypervisor TLS pinning flow. Two cases:
 *
 *  1. **First connect (TOFU confirmation).** `verifySsl=true` and no
 *     prior pin. Phase 1 silently auto-accepted. Phase 2 stops the
 *     connection and asks the user to verify the leaf SHA-256 — the
 *     same gate SSH uses for first-time host keys.
 *
 *  2. **Cert changed (mismatch warning).** `verifySsl=true` and the
 *     stored pin doesn't match what the server presents. Phase 1
 *     aborted the handshake silently. Phase 2 explains what changed
 *     so the user can knowingly accept (server reissue) or reject
 *     (suspected MITM).
 *
 * Both prompts are blocking from the calling thread's perspective —
 * the TLS handshake stalls while we await the user. We use a
 * `CountDownLatch` posted to the current Activity's UI thread, with a
 * hard 30-second timeout that defaults to REJECT. Same shape as
 * `HostKeyVerifier.showBlockingHostDialog`; deliberately not
 * unified yet because the SSH version still carries an older context-
 * walking path we don't need here.
 *
 * Activity resolution goes through `TabSSHApplication.getCurrentActivity()`
 * — populated by the application's activity lifecycle callbacks. If
 * no Activity is current (the app was just backgrounded, or the
 * connect happened from a service before any UI), the prompt
 * defaults to REJECT and the connection aborts. That's the
 * fail-safe.
 */
object HypervisorCertPromptDialog {

    private const val TAG = "HypervisorCertPrompt"
    private const val DIALOG_TIMEOUT_SECONDS: Long = 30

    /** Three-way user choice. Mirrors `HostKeyAction` semantics. */
    enum class Action {
        /** Accept this cert AND store the SHA-256 as the new pin. */
        ACCEPT_AND_PIN,

        /** Accept this cert for THIS connection only. Do NOT update
         *  the stored pin. (User unsure if reissue is legit.) */
        ACCEPT_ONCE,

        /** Reject. Trust manager throws → handshake aborts. */
        REJECT
    }

    /**
     * Prompt for a brand-new host (no prior pin). Display the
     * presented fingerprint and ask the user to verify it matches
     * what the server admin published.
     */
    fun promptNewHost(host: String, port: Int, presentedSha: String): Action {
        val message = buildString {
            appendLine("First TLS connection to this hypervisor.")
            appendLine()
            appendLine("Server: $host:$port")
            appendLine()
            appendLine("Leaf certificate SHA-256:")
            appendLine(presentedSha)
            appendLine()
            append(
                "Please verify this fingerprint matches what your server " +
                "admin shows. Future connections will be rejected if a " +
                "different certificate is presented (MITM / reissue / " +
                "wrong host)."
            )
        }
        return showBlockingDialog(
            title = "New hypervisor certificate",
            message = message,
            positiveLabel = "Accept & pin",
            neutralLabel = "Accept once",
            negativeLabel = "Reject",
            iconResId = null,
            logTag = "new-host:$host:$port"
        )
    }

    /**
     * Prompt for a cert that doesn't match the stored pin. Show both
     * fingerprints side-by-side so the user can compare.
     */
    fun promptChangedCert(
        host: String,
        port: Int,
        oldSha: String,
        newSha: String
    ): Action {
        val message = buildString {
            appendLine("⚠ This server presented a DIFFERENT certificate")
            appendLine("than the one TabSSH pinned. Could be:")
            appendLine("  • Server reissue / cert renewal — accept new pin.")
            appendLine("  • Wrong host (typo, network redirect) — reject.")
            appendLine("  • Active MITM attack — reject.")
            appendLine()
            appendLine("Server: $host:$port")
            appendLine()
            appendLine("Pinned   (old): $oldSha")
            appendLine("Presented (new): $newSha")
        }
        return showBlockingDialog(
            title = "⚠ Certificate changed",
            message = message,
            positiveLabel = "Accept new pin",
            neutralLabel = "Accept once",
            negativeLabel = "Reject (recommended)",
            iconResId = android.R.drawable.ic_dialog_alert,
            logTag = "changed:$host:$port"
        )
    }

    /**
     * Common dialog driver. Resolves an Activity, posts to its UI
     * thread, awaits with timeout. Default REJECT on every failure
     * path.
     */
    private fun showBlockingDialog(
        title: String,
        message: String,
        positiveLabel: String,
        neutralLabel: String,
        negativeLabel: String,
        iconResId: Int?,
        logTag: String
    ): Action {
        val app = try {
            io.github.tabssh.TabSSHApplication.get()
        } catch (e: Exception) {
            Logger.e(TAG, "TabSSHApplication.get() threw — defaulting to REJECT", e)
            return Action.REJECT
        }
        val activity = app.getCurrentActivity()
        if (activity == null) {
            Logger.e(TAG, "No current Activity for prompt ($logTag) — defaulting to REJECT")
            return Action.REJECT
        }
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.e(TAG, "Activity finishing/destroyed for prompt ($logTag) — defaulting to REJECT")
            return Action.REJECT
        }

        var result: Action = Action.REJECT
        val latch = CountDownLatch(1)

        activity.runOnUiThread {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    latch.countDown()
                    return@runOnUiThread
                }
                val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(positiveLabel) { _, _ ->
                        result = Action.ACCEPT_AND_PIN
                        latch.countDown()
                    }
                    .setNeutralButton(neutralLabel) { _, _ ->
                        result = Action.ACCEPT_ONCE
                        latch.countDown()
                    }
                    .setNegativeButton(negativeLabel) { _, _ ->
                        result = Action.REJECT
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .setOnDismissListener { latch.countDown() }
                if (iconResId != null) builder.setIcon(iconResId)
                builder.show()
            } catch (e: Exception) {
                Logger.e(TAG, "Error showing cert prompt ($logTag)", e)
                latch.countDown()
            }
        }

        try {
            val signaled = latch.await(DIALOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!signaled) {
                Logger.w(TAG, "Dialog timeout (${DIALOG_TIMEOUT_SECONDS}s) — defaulting to REJECT for $logTag")
                result = Action.REJECT
            }
        } catch (e: InterruptedException) {
            Logger.e(TAG, "Interrupted waiting for cert-prompt response ($logTag)", e)
            result = Action.REJECT
            Thread.currentThread().interrupt()
        }
        Logger.i(TAG, "Cert prompt ($logTag) → $result")
        return result
    }
}
