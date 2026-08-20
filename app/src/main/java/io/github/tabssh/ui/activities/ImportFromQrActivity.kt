package io.github.tabssh.ui.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import io.github.tabssh.R
import io.github.tabssh.pairing.FailureReason
import io.github.tabssh.pairing.PairingDecryptor
import io.github.tabssh.pairing.PairingImporter
import io.github.tabssh.pairing.PairingPayload
import io.github.tabssh.pairing.PairingResult
import io.github.tabssh.ui.dialogs.DialogFields
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Pair from desktop" flow — see AI.md §18 for the full spec.
 *
 * State machine:
 *
 *   onCreate
 *      ↓ (request CAMERA if not granted)
 *   launchScanner ── ZXing CaptureActivity ──┐
 *      ↓                                      │
 *   handleScanResult(scannedText)             │ user cancels → finish()
 *      ↓
 *   promptForCode
 *      ↓ (user types 6 digits)               retry up to 3× on WRONG_CODE
 *   decryptAndDecode (Argon2id, on IO)
 *      ↓
 *   showImportPreview(payload)
 *      ↓ (user confirms)
 *   importToDatabase
 *      ↓
 *   Toast.success → finish()
 *
 * The activity itself is a thin shell — most of the user-facing work
 * happens in modal dialogs.
 */
class ImportFromQrActivity : TabSSHActivity() {

    companion object {
        private const val TAG = "ImportFromQrActivity"
        private const val MAX_CODE_ATTEMPTS = 3
    }

    private lateinit var statusText: TextView
    private var scannedText: String? = null
    private var codeAttemptsRemaining = MAX_CODE_ATTEMPTS

    private val scanLauncher: ActivityResultLauncher<ScanOptions> =
        registerForActivityResult(ScanContract()) { result -> onScanFinished(result) }

    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScanner() else abortWith(getString(R.string.import_qr_camera_permission_required))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_from_qr)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setTitle(R.string.import_from_qr_title)
        statusText = findViewById(R.id.text_status)

        if (savedInstanceState == null) {
            ensureCameraPermissionThenScan()
        }
    }

    private fun ensureCameraPermissionThenScan() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) launchScanner() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchScanner() {
        statusText.setText(R.string.import_qr_status_scan)
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.import_qr_scanner_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setBarcodeImageEnabled(false)
        scanLauncher.launch(options)
    }

    private fun onScanFinished(result: ScanIntentResult) {
        val text = result.contents
        if (text.isNullOrBlank()) {
            // User pressed back / cancelled the scanner.
            finish()
            return
        }
        scannedText = text
        Logger.d(TAG, "QR scanned, ${text.length} chars; prompting for code")
        promptForCode()
    }

    private fun promptForCode() {
        statusText.setText(R.string.import_qr_status_enter_code)

        val form = DialogFields.form(this)
        val edit = DialogFields.addText(
            form, getString(R.string.import_qr_code_hint), inputType = InputType.TYPE_CLASS_NUMBER
        ).apply {
            setSelectAllOnFocus(true)
            // Soft keyboard hint; doesn't enforce length (we validate on confirm).
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }

        val attemptsHint = if (codeAttemptsRemaining < MAX_CODE_ATTEMPTS) {
            resources.getQuantityString(
                R.plurals.import_qr_attempts_left, codeAttemptsRemaining, codeAttemptsRemaining
            )
        } else ""

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_qr_dialog_title)
            .setMessage(getString(R.string.import_qr_dialog_message) + attemptsHint)
            .setView(form.root)
            .setPositiveButton(R.string.import_qr_decrypt) { _, _ ->
                val code = edit.text.toString().trim()
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    Toast.makeText(this, R.string.import_qr_code_invalid, Toast.LENGTH_SHORT).show()
                    promptForCode()
                    return@setPositiveButton
                }
                runDecrypt(code)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun runDecrypt(code: String) {
        val text = scannedText ?: return abortWith(getString(R.string.import_qr_no_scan_data))
        statusText.setText(R.string.import_qr_status_decrypting)

        // Argon2id is intentionally slow (~1s on a phone). Do not run on main.
        lifecycleScope.launch(Dispatchers.Default) {
            val outcome = PairingDecryptor.decryptAndDecode(text, code)
            withContext(Dispatchers.Main) { handleDecryptResult(outcome) }
        }
    }

    private fun handleDecryptResult(outcome: PairingResult) {
        when (outcome) {
            is PairingResult.Success -> showImportPreview(outcome.payload)
            is PairingResult.Failure -> handleFailure(outcome)
        }
    }

    private fun handleFailure(failure: PairingResult.Failure) {
        Logger.w(TAG, "Pairing failed: ${failure.reason} — ${failure.message}")
        when (failure.reason) {
            FailureReason.WRONG_CODE -> {
                codeAttemptsRemaining--
                if (codeAttemptsRemaining > 0) {
                    promptForCode()
                } else {
                    showFatalError(getString(R.string.import_qr_error_too_many_attempts))
                }
            }
            FailureReason.EXPIRED ->
                showFatalError(getString(R.string.import_qr_error_expired))
            FailureReason.UNSUPPORTED_VERSION, FailureReason.UNSUPPORTED_PAYLOAD_VERSION ->
                showFatalError(getString(R.string.import_qr_error_unsupported_version))
            FailureReason.BAD_ENVELOPE, FailureReason.BAD_PAYLOAD ->
                showFatalError(getString(R.string.import_qr_error_bad_payload))
            FailureReason.INTERNAL_ERROR ->
                showFatalError(getString(R.string.import_qr_error_internal_fmt, failure.message))
        }
    }

    private fun showImportPreview(payload: PairingPayload) {
        statusText.setText(R.string.import_qr_status_confirm)

        val separator = getString(R.string.import_qr_list_separator)
        val deviceLine = payload.deviceLabel
            ?.let { getString(R.string.import_qr_source_device_fmt, it) }
            ?: getString(R.string.import_qr_source_desktop)
        val title = resources.getQuantityString(
            R.plurals.import_qr_preview_title,
            payload.connections.size,
            payload.connections.size,
            deviceLine
        )

        val body = StringBuilder()
        if (payload.connections.isNotEmpty()) {
            body.append(getString(R.string.import_qr_preview_connections_header))
            payload.connections.forEach { c ->
                body.append(
                    getString(
                        R.string.import_qr_preview_connection_line_fmt,
                        c.name, c.username, c.host, c.port
                    )
                )
            }
        }
        if (payload.groups.isNotEmpty()) {
            body.append(
                getString(
                    R.string.import_qr_preview_groups_fmt,
                    payload.groups.joinToString(separator) { it.name }
                )
            )
        }
        if (payload.identities.isNotEmpty()) {
            body.append(
                getString(
                    R.string.import_qr_preview_identities_fmt,
                    payload.identities.joinToString(separator) { it.name }
                )
            )
        }
        body.append(getString(R.string.import_qr_preview_footer))

        // Wrap the body in a scrollable TextView so very large imports
        // don't push the dialog buttons off-screen.
        val message = TextView(this).apply {
            text = body.toString()
            setPadding((24 * resources.displayMetrics.density).toInt())
            textSize = 14f
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(message)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.import_qr_confirm) { _, _ -> runImport(payload) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun runImport(payload: PairingPayload) {
        statusText.setText(R.string.import_qr_status_importing)
        // Block any other interaction while DB inserts run.
        lifecycleScope.launch(Dispatchers.IO) {
            val summary = try {
                PairingImporter.import(this@ImportFromQrActivity, payload)
            } catch (e: Exception) {
                Logger.e(TAG, "Import failed", e)
                withContext(Dispatchers.Main) {
                    showFatalError(
                        getString(
                            R.string.import_qr_import_failed_fmt,
                            e.message ?: e::class.simpleName.orEmpty()
                        )
                    )
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                showImportSuccess(summary)
            }
        }
    }

    private fun showImportSuccess(summary: PairingImporter.ImportSummary) {
        val separator = getString(R.string.import_qr_list_separator)
        val parts = buildList {
            add(
                resources.getQuantityString(
                    R.plurals.import_qr_summary_connections, summary.connections, summary.connections
                )
            )
            if (summary.groups > 0) {
                add(
                    resources.getQuantityString(
                        R.plurals.import_qr_summary_groups, summary.groups, summary.groups
                    )
                )
            }
            if (summary.identities > 0) {
                add(
                    resources.getQuantityString(
                        R.plurals.import_qr_summary_identities, summary.identities, summary.identities
                    )
                )
            }
        }
        val msg = getString(R.string.import_qr_summary_fmt, parts.joinToString(separator))
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        if (summary.skipped.isNotEmpty()) {
            Logger.w(TAG, "Skipped: ${summary.skipped.joinToString(", ")}")
        }
        // Return to caller (MainActivity) so the new connections show up live.
        setResult(RESULT_OK)
        finish()
    }

    private fun showFatalError(message: String) {
        // Issue #167 — route through DialogUtils for the Copy button. Add a
        // dismiss callback so back/outside-tap still finish()s the activity.
        io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
            this, getString(R.string.import_qr_failed_title), message,
            onDismiss = { finish() }
        )
    }

    private fun abortWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

}
