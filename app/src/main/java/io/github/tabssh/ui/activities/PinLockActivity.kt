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
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger
import java.security.MessageDigest

/**
 * Wave 3.2 — PIN code app lock.
 *
 * Two modes selected via [EXTRA_MODE]:
 *  - "set"    — first-time setup. Asks for PIN twice; on match, stores
 *               SHA-256 hex in `app_lock_pin_hash` preference and enables
 *               `app_lock_enabled = true`. Returns RESULT_OK.
 *  - "verify" — gate. Asks for PIN; on match, returns RESULT_OK. On 5
 *               failed attempts, finishes with RESULT_CANCELED (caller
 *               typically `finishAffinity()`s the app).
 *
 * Coexists with biometric — this is a *supplement*, not a replacement.
 * Biometric flows in `SecurePasswordManager` are unaffected.
 *
 * No PIN history / strength meter / lockout backoff in this MVP. SHA-256
 * is plenty for a 4-8 digit PIN that gates a local app; a real attacker
 * with the device cleared past biometric and decrypted Keystore is not
 * the threat model here.
 */
class PinLockActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PinLockActivity"
        const val EXTRA_MODE = "mode"
        const val MODE_SET = "set"
        const val MODE_VERIFY = "verify"
        const val PREF_PIN_HASH = "app_lock_pin_hash"
        const val PREF_PIN_ENABLED = "app_lock_enabled"
        private const val MAX_ATTEMPTS = 5

        fun verifyIntent(context: Context): Intent =
            Intent(context, PinLockActivity::class.java).putExtra(EXTRA_MODE, MODE_VERIFY)

        fun setupIntent(context: Context): Intent =
            Intent(context, PinLockActivity::class.java).putExtra(EXTRA_MODE, MODE_SET)

        /** SHA-256 hex of [pin] for storage / comparison. */
        fun hashPin(pin: String): String =
            MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private lateinit var app: TabSSHApplication
    private lateinit var pinInput: EditText
    private lateinit var status: TextView
    private var firstPin: String? = null
    private var attempts = 0
    private var mode: String = MODE_VERIFY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots of the lock screen.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        app = application as TabSSHApplication
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY

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
        // Match — persist.
        app.preferencesManager.setString(PREF_PIN_HASH, hashPin(pin))
        app.preferencesManager.setBoolean(PREF_PIN_ENABLED, true)
        Toast.makeText(this, "App lock PIN set", Toast.LENGTH_SHORT).show()
        Logger.i(TAG, "PIN configured")
        setResult(Activity.RESULT_OK)
        finish()
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
        if (hashPin(pin) == expected) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }
        attempts++
        pinInput.setText("")
        if (attempts >= MAX_ATTEMPTS) {
            status.text = "Too many wrong attempts."
            Toast.makeText(this, "App locked — quit and try later", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        status.text = "Incorrect — try again (${MAX_ATTEMPTS - attempts} left)"
    }

    @Deprecated("Block back press while locked")
    override fun onBackPressed() {
        if (mode == MODE_VERIFY) {
            // Don't let user dismiss the gate.
            return
        }
        super.onBackPressed()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
