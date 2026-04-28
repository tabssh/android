package io.github.tabssh.ui.activities

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import io.github.tabssh.R
import io.github.tabssh.pairing.FailureReason
import io.github.tabssh.pairing.PairingDecryptor
import io.github.tabssh.pairing.PairingImporter
import io.github.tabssh.pairing.PairingPayload
import io.github.tabssh.pairing.PairingResult
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Pair from desktop" flow — see QR_PAIRING.md.
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
class ImportFromQrActivity : AppCompatActivity() {

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
            if (granted) launchScanner() else abortWith("Camera permission required to scan QR codes.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_from_qr)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Pair from Desktop"
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
        statusText.text = "Point your camera at the QR code on your desktop."
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the QR shown by TabSSH on your desktop")
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
        statusText.text = "Enter the 6-digit code shown on your desktop."

        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6-digit code"
            setSelectAllOnFocus(true)
            // Soft keyboard hint; doesn't enforce length (we validate on confirm).
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }

        val attemptsHint = if (codeAttemptsRemaining < MAX_CODE_ATTEMPTS) {
            "\n\n($codeAttemptsRemaining attempt${if (codeAttemptsRemaining == 1) "" else "s"} left)"
        } else ""

        AlertDialog.Builder(this)
            .setTitle("Enter pairing code")
            .setMessage("Type the 6-digit code shown by TabSSH on your desktop.$attemptsHint")
            .setView(edit)
            .setPositiveButton("Decrypt") { _, _ ->
                val code = edit.text.toString().trim()
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    Toast.makeText(this, "Code must be 6 digits.", Toast.LENGTH_SHORT).show()
                    promptForCode()
                    return@setPositiveButton
                }
                runDecrypt(code)
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun runDecrypt(code: String) {
        val text = scannedText ?: return abortWith("No scanned data — try again.")
        statusText.text = "Decrypting… (this takes about a second)"

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
                    showFatalError("Too many wrong codes. Generate a new QR on the desktop and try again.")
                }
            }
            FailureReason.EXPIRED ->
                showFatalError("This QR has expired. Generate a new one on the desktop.")
            FailureReason.UNSUPPORTED_VERSION, FailureReason.UNSUPPORTED_PAYLOAD_VERSION ->
                showFatalError("This QR was created by a newer TabSSH. Update your phone app and try again.")
            FailureReason.BAD_ENVELOPE, FailureReason.BAD_PAYLOAD ->
                showFatalError("Couldn't read the QR. Make sure both apps are up to date.")
            FailureReason.INTERNAL_ERROR ->
                showFatalError("Something went wrong: ${failure.message}")
        }
    }

    private fun showImportPreview(payload: PairingPayload) {
        statusText.text = "Confirm import."

        val deviceLine = payload.deviceLabel?.let { "from $it" } ?: "from desktop"
        val title = "Import ${payload.connections.size} connections $deviceLine?"

        val body = StringBuilder()
        if (payload.connections.isNotEmpty()) {
            body.append("Connections:\n")
            payload.connections.forEach { c ->
                body.append("  • ${c.name} — ${c.username}@${c.host}:${c.port}\n")
            }
        }
        if (payload.groups.isNotEmpty()) {
            body.append("\nGroups: ${payload.groups.joinToString(", ") { it.name }}\n")
        }
        if (payload.identities.isNotEmpty()) {
            body.append("\nIdentities: ${payload.identities.joinToString(", ") { it.name }}\n")
        }
        body.append("\nNo passwords or private keys are imported — you'll be prompted on first connect.")

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

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Import") { _, _ -> runImport(payload) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun runImport(payload: PairingPayload) {
        statusText.text = "Importing…"
        // Block any other interaction while DB inserts run.
        lifecycleScope.launch(Dispatchers.IO) {
            val summary = try {
                PairingImporter.import(this@ImportFromQrActivity, payload)
            } catch (e: Exception) {
                Logger.e(TAG, "Import failed", e)
                withContext(Dispatchers.Main) {
                    showFatalError("Import failed: ${e.message ?: e::class.simpleName}")
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                showImportSuccess(summary)
            }
        }
    }

    private fun showImportSuccess(summary: PairingImporter.ImportSummary) {
        val parts = buildList {
            add("${summary.connections} connections")
            if (summary.groups > 0) add("${summary.groups} groups")
            if (summary.identities > 0) add("${summary.identities} identities")
        }
        val msg = "Imported ${parts.joinToString(", ")}."
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        if (summary.skipped.isNotEmpty()) {
            Logger.w(TAG, "Skipped: ${summary.skipped.joinToString(", ")}")
        }
        // Return to caller (MainActivity) so the new connections show up live.
        setResult(RESULT_OK)
        finish()
    }

    private fun showFatalError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Pairing failed")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun abortWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
