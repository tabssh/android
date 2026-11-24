package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import io.github.tabssh.R
import io.github.tabssh.ui.utils.AlwaysSecureScreen
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.tabSSHApp

/**
 * Inactivity lock behind the "Security Lock" setting: once the app has been in the background
 * longer than the chosen lock timeout, this screen asks the user to prove it is them with the
 * device's own unlock (biometric where the "Biometric Authentication" setting allows it, otherwise
 * the screen-lock PIN, pattern or password). It is separate from the in-app PIN lock, which has its
 * own prompt and setting. Dismissing or failing the prompt closes the app instead of showing it.
 */
class DeviceUnlockActivity : AppCompatActivity(), AlwaysSecureScreen {

    companion object {
        private const val TAG = "DeviceUnlockActivity"

        fun intent(context: Context): Intent = Intent(context, DeviceUnlockActivity::class.java)

        /**
         * Authenticators the prompt may use. With biometrics off the device credential alone is used,
         * where the platform allows it (API 30+); older releases cannot show a credential-only prompt
         * through BiometricPrompt, so there the combined set is the closest honest equivalent.
         */
        fun authenticators(biometricAllowed: Boolean): Int =
            if (biometricAllowed || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            } else {
                DEVICE_CREDENTIAL
            }

        /** True when the device has something to authenticate with, so the lock can be satisfied. */
        fun canAuthenticate(context: Context, biometricAllowed: Boolean): Boolean =
            BiometricManager.from(context).canAuthenticate(authenticators(biometricAllowed)) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    private var prompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the locked content out of screenshots and the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(FrameLayout(this))
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        if (!prompted) {
            prompted = true
            showPrompt()
        }
    }

    private fun showPrompt() {
        val biometricAllowed = tabSSHApp.preferencesManager.getBoolean("biometric_auth", false)
        val allowed = authenticators(biometricAllowed)
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Logger.i(TAG, "Security lock released")
                    setResult(RESULT_OK)
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Logger.w(TAG, "Security lock not released ($errorCode): $errString")
                    setResult(RESULT_CANCELED)
                    finishAffinity()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.security_lock_prompt_title))
            .setSubtitle(getString(R.string.security_lock_prompt_subtitle))
            .setAllowedAuthenticators(allowed)
            .build()
        prompt.authenticate(info)
    }
}
