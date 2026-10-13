package io.github.tabssh.ui.dialogs

import android.app.Activity
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** One-shot Proxmox second-factor input. The returned value is never persisted. */
suspend fun Activity.promptProxmoxSecondFactor(): String? = suspendCancellableCoroutine { continuation ->
    val input = EditText(this).apply {
        hint = "Authenticator code or recovery key"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setSingleLine(true)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, 0, padding, 0)
        addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle("Proxmox verification")
        .setMessage("Enter your TOTP code, or use a single-use recovery key.")
        .setView(container)
        .setPositiveButton("Use TOTP", null)
        .setNeutralButton("Use recovery key", null)
        .setNegativeButton(android.R.string.cancel) { _, _ ->
            if (continuation.isActive) continuation.resume(null)
        }
        .setOnCancelListener {
            if (continuation.isActive) continuation.resume(null)
        }
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val code = input.text?.toString().orEmpty().trim()
            if (code.isNotBlank() && continuation.isActive) {
                continuation.resume("totp:$code")
                dialog.dismiss()
            } else input.error = "Enter a code"
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val key = input.text?.toString().orEmpty().trim()
            if (key.isNotBlank() && continuation.isActive) {
                continuation.resume("recovery:$key")
                dialog.dismiss()
            } else input.error = "Enter a recovery key"
        }
    }
    continuation.invokeOnCancellation { dialog.dismiss() }
    dialog.show()
}

/** Blocking JSch callback bridge; called on the SSH worker while the UI collects responses. */
fun Activity.promptSshKeyboardInteractive(
    prompts: Array<String>,
    echo: BooleanArray,
    cachedPassword: String
): Array<String>? {
    if (prompts.isEmpty() || prompts.size > 8 || isFinishing || isDestroyed) return null
    val result = AtomicReference<Array<String>?>(null)
    val activeDialog = AtomicReference<AlertDialog?>(null)
    val latch = CountDownLatch(1)
    runOnUiThread {
        if (isFinishing || isDestroyed) {
            latch.countDown()
            return@runOnUiThread
        }
        val fields = prompts.mapIndexed { index, rawPrompt ->
            val prompt = rawPrompt.filterNot { it.isISOControl() }.take(160).ifBlank { "Response ${index + 1}" }
            val label = TextView(this).apply { text = prompt }
            val input = EditText(this).apply {
                inputType = if (echo.getOrNull(index) == true) {
                    InputType.TYPE_CLASS_TEXT
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                setSingleLine(true)
                if (cachedPassword.isNotBlank() && prompt.contains("password", ignoreCase = true) &&
                    !prompt.contains("one-time", ignoreCase = true) && !prompt.contains("otp", ignoreCase = true)) {
                    setText(cachedPassword)
                }
            }
            label to input
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            fields.forEach { (label, input) ->
                addView(label)
                addView(input, LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("SSH verification")
            .setMessage("The SSH server requested additional authentication information.")
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> latch.countDown() }
            .setOnCancelListener { latch.countDown() }
            .create()
        activeDialog.set(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val answers = fields.map { (_, input) -> input.text?.toString().orEmpty() }
                if (answers.any { it.isBlank() }) {
                    fields.firstOrNull { it.second.text.isNullOrBlank() }?.second?.error = "Required"
                } else {
                    result.set(answers.toTypedArray())
                    latch.countDown()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
    if (!latch.await(2, TimeUnit.MINUTES)) {
        runOnUiThread { activeDialog.getAndSet(null)?.dismiss() }
        return null
    }
    return result.get()
}
