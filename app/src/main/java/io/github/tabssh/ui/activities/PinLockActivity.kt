package io.github.tabssh.ui.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.pairing.PairingDecryptor
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Wave 3.2 — PIN code app lock.
 *
 * Two modes selected via [EXTRA_MODE]:
 *  - "set"    — first-time setup. Asks for PIN twice; on match, stores a
 *               salted Argon2id hash (`v2:<salt>:<hash>`) in the
 *               `app_lock_pin_hash` preference and enables
 *               `app_lock_enabled = true`. Returns RESULT_OK.
 *  - "verify" — gate. Asks for PIN; on match, returns RESULT_OK. On 5
 *               failed attempts, finishes with RESULT_CANCELED (caller
 *               typically `finishAffinity()`s the app).
 *
 * Coexists with biometric — this is a *supplement*, not a replacement.
 * Biometric flows in `SecurePasswordManager` are unaffected.
 *
 * The PIN hash is salted Argon2id (per-device random salt, 64 MiB / t=3
 * via the pairing subsystem's tuned KDF) so a leaked hash — from a backup
 * or a rooted device — is not trivially rainbow-tableable the way a bare
 * unsalted SHA-256 of a 4–8 digit PIN would be. The ~1s derivation also
 * rate-limits on-device brute force, complementing the 5-attempt lockout.
 * PINs set under the old unsalted-SHA-256 scheme are upgraded transparently
 * on the next successful unlock. No PIN history / strength meter / backoff.
 */
class PinLockActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PinLockActivity"
        const val EXTRA_MODE = "mode"
        const val MODE_SET = "set"
        const val MODE_VERIFY = "verify"
        const val PREF_PIN_HASH = "app_lock_pin_hash"
        const val PREF_PIN_ENABLED = "app_lock_enabled"
        // Failed-attempt counter is persisted so a brute-force attacker can't
        // simply rotate the activity (rotate device / kill task) to reset it.
        private const val PREF_PIN_FAIL_COUNT = "app_lock_pin_fail_count"
        private const val MAX_ATTEMPTS = 5

        /**
         * Constant-time comparison of two SHA-256 hex strings — short-circuit
         * equality on the PIN hash would leak whether the first byte matched
         * via timing. Strings are already fixed length (64 hex chars) so a
         * length mismatch fails fast and is not a timing concern.
         */
        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }

        fun verifyIntent(context: Context): Intent =
            Intent(context, PinLockActivity::class.java).putExtra(EXTRA_MODE, MODE_VERIFY)

        fun setupIntent(context: Context): Intent =
            Intent(context, PinLockActivity::class.java).putExtra(EXTRA_MODE, MODE_SET)

        // Storage format for the app-lock PIN hash. "v2:" is salted Argon2id
        // ("v2:<saltB64>:<hashB64>"); a bare 64-char SHA-256 hex string is the
        // legacy scheme, kept only for transparent upgrade on next unlock.
        private const val PIN_SCHEME_V2_PREFIX = "v2:"
        private const val PIN_SALT_BYTES = 16
        private const val B64_FLAGS = android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP

        private fun newSalt(): ByteArray =
            ByteArray(PIN_SALT_BYTES).also { SecureRandom().nextBytes(it) }

        // Salted, memory-hard Argon2id hash of [pin]. Reuses the pairing
        // subsystem's tuned Argon2id (64 MiB, t=3) — intentionally ~1s, so it
        // must run off the UI thread. Returns "v2:<saltB64>:<hashB64>".
        fun hashPinV2(pin: String, salt: ByteArray = newSalt()): String {
            val digest = PairingDecryptor.deriveKey(pin, salt)
            val saltB64 = android.util.Base64.encodeToString(salt, B64_FLAGS)
            val hashB64 = android.util.Base64.encodeToString(digest, B64_FLAGS)
            return "$PIN_SCHEME_V2_PREFIX$saltB64:$hashB64"
        }

        // Legacy unsalted single-round SHA-256 hex — verify-only, for migrating
        // PINs set before the Argon2id scheme landed.
        private fun legacyHashPin(pin: String): String =
            MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private lateinit var app: TabSSHApplication
    private lateinit var pinInput: EditText
    private lateinit var status: TextView
    private var firstPin: String? = null
    private var attempts = 0
    private var mode: String = MODE_VERIFY
    // Guards against re-entrant submits while an Argon2id derivation (~1s) is
    // running on a background thread.
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots of the lock screen.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        app = application as TabSSHApplication
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY
        // Restore the persisted failed-attempt count so a relaunch can't reset
        // the brute-force budget. MODE_SET starts fresh.
        attempts = if (mode == MODE_VERIFY)
            app.preferencesManager.getInt(PREF_PIN_FAIL_COUNT, 0)
        else 0

        // Modern back-press: in MODE_VERIFY we eat the back press
        // (don't let the user dismiss the gate); otherwise let the
        // dispatcher fall through to the default Activity finish.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == MODE_VERIFY) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        val title = TextView(this).apply {
            text = if (mode == MODE_SET) "Set app lock PIN" else "Enter app lock PIN"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        root.addView(title)

        status = TextView(this).apply {
            text = if (mode == MODE_SET) "Enter a 4–8 digit PIN" else ""
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)

        pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 28f
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        }
        root.addView(pinInput)

        val submit = Button(this).apply { text = if (mode == MODE_SET) "Continue" else "Unlock" }
        submit.setOnClickListener { onSubmit() }
        root.addView(submit)

        if (mode == MODE_SET) {
            val cancel = Button(this).apply { text = "Cancel" }
            cancel.setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            root.addView(cancel)
        }

        setContentView(root)
        pinInput.requestFocus()
    }

    private fun onSubmit() {
        if (busy) return
        val pin = pinInput.text.toString().trim()
        if (pin.length !in 4..8) {
            status.text = "PIN must be 4–8 digits"
            return
        }
        when (mode) {
            MODE_SET -> handleSetSubmit(pin)
            MODE_VERIFY -> handleVerifySubmit(pin)
        }
    }

    // Toggle the input's interactivity while a background derivation runs.
    private fun setBusy(value: Boolean) {
        busy = value
        pinInput.isEnabled = !value
    }

    private fun handleSetSubmit(pin: String) {
        if (firstPin == null) {
            firstPin = pin
            pinInput.setText("")
            status.text = "Enter PIN again to confirm"
            return
        }
        if (firstPin != pin) {
            firstPin = null
            pinInput.setText("")
            status.text = "PINs didn't match. Start over."
            return
        }
        // Match — derive the salted Argon2id hash off the UI thread (~1s),
        // then persist. Also reset the failed-attempt counter; setting a new
        // PIN should give the user a full retry budget on next unlock.
        setBusy(true)
        status.text = "Saving…"
        lifecycleScope.launch {
            val stored = withContext(Dispatchers.Default) { hashPinV2(pin) }
            app.preferencesManager.setString(PREF_PIN_HASH, stored)
            app.preferencesManager.setBoolean(PREF_PIN_ENABLED, true)
            app.preferencesManager.setInt(PREF_PIN_FAIL_COUNT, 0)
            setBusy(false)
            Toast.makeText(this@PinLockActivity, "App lock PIN set", Toast.LENGTH_SHORT).show()
            Logger.i(TAG, "PIN configured")
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun handleVerifySubmit(pin: String) {
        val expected = app.preferencesManager.getString(PREF_PIN_HASH, "")
        if (expected.isBlank()) {
            // Misconfigured — let user through rather than soft-bricking.
            Logger.w(TAG, "Verify mode without a stored hash — bypassing")
            setResult(Activity.RESULT_OK)
            finish()
            return
        }
        // Derive/compare off the UI thread (Argon2id is intentionally ~1s).
        setBusy(true)
        status.text = "Checking…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) { verifyPin(pin, expected) }
            setBusy(false)
            if (ok) {
                // Successful unlock clears the persisted fail counter so a future
                // session starts fresh.
                app.preferencesManager.setInt(PREF_PIN_FAIL_COUNT, 0)
                attempts = 0
                setResult(Activity.RESULT_OK)
                finish()
                return@launch
            }
            // Persist the attempt counter so killing/relaunching the activity
            // does not reset the brute-force budget — load → increment → store
            // on every failed entry.
            val persisted = app.preferencesManager.getInt(PREF_PIN_FAIL_COUNT, 0) + 1
            app.preferencesManager.setInt(PREF_PIN_FAIL_COUNT, persisted)
            attempts = persisted
            pinInput.setText("")
            if (attempts >= MAX_ATTEMPTS) {
                status.text = "Too many wrong attempts."
                Toast.makeText(this@PinLockActivity, "Too many wrong attempts — closing app", Toast.LENGTH_LONG).show()
                finishAffinity()
                return@launch
            }
            status.text = "Incorrect — try again (${MAX_ATTEMPTS - attempts} left)"
        }
    }

    // Verify [pin] against the [stored] hash. Handles both the salted Argon2id
    // "v2:" scheme and the legacy unsalted SHA-256 hex, transparently upgrading
    // a matched legacy hash to Argon2id in place. Runs on a background thread.
    private fun verifyPin(pin: String, stored: String): Boolean {
        if (stored.startsWith(PIN_SCHEME_V2_PREFIX)) {
            val parts = stored.removePrefix(PIN_SCHEME_V2_PREFIX).split(":")
            if (parts.size != 2) return false
            val salt = try {
                android.util.Base64.decode(parts[0], B64_FLAGS)
            } catch (_: IllegalArgumentException) {
                return false
            }
            val candidate = hashPinV2(pin, salt)
            return constantTimeEquals(candidate, stored)
        }
        // Legacy unsalted SHA-256 — verify, then upgrade the stored hash to the
        // salted Argon2id scheme so the weak form is never persisted again.
        if (constantTimeEquals(legacyHashPin(pin), stored)) {
            app.preferencesManager.setString(PREF_PIN_HASH, hashPinV2(pin))
            return true
        }
        return false
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
