package io.github.tabssh.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.hypervisor.oci.OciApiClient
import io.github.tabssh.hypervisor.oci.OciConfigParser
import io.github.tabssh.hypervisor.oci.OciConfigProfile
import io.github.tabssh.hypervisor.oci.OciKeyMaterial
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Path A onboarding for OCI hypervisors — import an existing
 * `~/.oci/config` plus its `.pem` private key. No in-app keypair
 * generation; users are expected to have run `oci setup config` (or
 * created the API key in the OCI console) and to know which key file
 * to import.
 *
 * Flow on this single screen:
 *   1. "Pick OCI config file…" → SAF picker → parse → if multiple
 *      `[sections]`, prompt to choose; auto-fills name/tenancy/user/
 *      region/fingerprint/compartment fields.
 *   2. "Pick PEM private key…" → SAF picker → parse via
 *      [OciKeyMaterial.fromPem]; encrypted keys prompt for a
 *      passphrase, retried on failure. Fingerprint round-trip:
 *      computed fingerprint must equal the config's fingerprint.
 *   3. Optional "Test" → live `validateCredentials()` against the
 *      chosen region's identity endpoint. Save persists the row +
 *      stores PEM + passphrase under
 *      `oci_private_key_${id}` / `oci_passphrase_${id}` in
 *      `SecurePasswordManager`.
 *
 * Reject path: any imported config profile carrying
 * `security_token_file=` is refused — those are 1-hour CLI-only
 * session tokens, not API keys.
 */
class OciOnboardingActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication

    private lateinit var btnPickConfig: MaterialButton
    private lateinit var btnPickPem: MaterialButton
    private lateinit var btnTest: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private lateinit var editName: TextInputEditText
    private lateinit var editTenancy: TextInputEditText
    private lateinit var editUser: TextInputEditText
    private lateinit var editRegion: MaterialAutoCompleteTextView
    private lateinit var editCompartment: TextInputEditText
    private lateinit var editFingerprint: TextInputEditText
    private lateinit var txtConfigStatus: android.widget.TextView
    private lateinit var txtPemStatus: android.widget.TextView
    private lateinit var txtTestStatus: android.widget.TextView

    /** Currently loaded key material — null until the user picks a PEM. */
    private var keyMaterial: OciKeyMaterial? = null
    /** Verbatim PEM text — persisted to the Keystore alongside the row. */
    private var pemText: String? = null
    /** Passphrase the user supplied for an encrypted PEM, if any. */
    private var keyPassphrase: String? = null

    private val pickConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onConfigPicked(it) } }

    private val pickPemLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onPemPicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oci_onboarding)

        app = application as TabSSHApplication

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        btnPickConfig = findViewById(R.id.btn_pick_config)
        btnPickPem = findViewById(R.id.btn_pick_pem)
        btnTest = findViewById(R.id.btn_test)
        btnSave = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)

        editName = findViewById(R.id.edit_name)
        editTenancy = findViewById(R.id.edit_tenancy)
        editUser = findViewById(R.id.edit_user)
        editRegion = findViewById(R.id.edit_region)
        editCompartment = findViewById(R.id.edit_compartment)
        editFingerprint = findViewById(R.id.edit_fingerprint)
        txtConfigStatus = findViewById(R.id.txt_config_status)
        txtPemStatus = findViewById(R.id.txt_pem_status)
        txtTestStatus = findViewById(R.id.txt_test_status)

        editRegion.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, REGION_SEED)
        )
        editRegion.threshold = 1

        btnPickConfig.setOnClickListener {
            pickConfigLauncher.launch(arrayOf("*/*"))
        }
        btnPickPem.setOnClickListener {
            pickPemLauncher.launch(arrayOf("*/*"))
        }
        btnTest.setOnClickListener { runValidate() }
        btnSave.setOnClickListener { runSave() }
        btnCancel.setOnClickListener { finish() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish(); return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------- Step 1: config import -----------------------------------

    private fun onConfigPicked(uri: Uri) {
        lifecycleScope.launch {
            val text = readTextFromUri(uri) ?: run {
                Toast.makeText(this@OciOnboardingActivity, "Could not read file", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val profiles = OciConfigParser.parse(text)
            if (profiles.isEmpty()) {
                txtConfigStatus.text = "Parsed file is empty — check the path."
                return@launch
            }
            // Reject session-token profiles up front. Build a list of usable ones.
            val usable = profiles.filter { !it.usesSessionToken }
            val rejected = profiles - usable.toSet()
            if (usable.isEmpty()) {
                AlertDialog.Builder(this@OciOnboardingActivity)
                    .setTitle("Unsupported config")
                    .setMessage("Every profile in this config uses security_token_file= " +
                        "(1-hour session tokens). TabSSH only supports persistent " +
                        "API keys.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            val chosen = if (usable.size == 1) usable.first() else pickProfileDialog(usable)
            chosen ?: return@launch

            applyProfile(chosen)
            val skipped = if (rejected.isEmpty()) "" else
                " (skipped ${rejected.size} session-token profile${if (rejected.size > 1) "s" else ""})"
            txtConfigStatus.text = "Loaded profile [${chosen.name}]$skipped"
        }
    }

    private suspend fun pickProfileDialog(profiles: List<OciConfigProfile>): OciConfigProfile? {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val labels = profiles.map { p ->
                val region = p.region ?: "?"
                "[${p.name}]   ${p.userOcid?.takeLast(8) ?: "?"}…  $region"
            }.toTypedArray()
            AlertDialog.Builder(this@OciOnboardingActivity)
                .setTitle("Pick a profile")
                .setItems(labels) { _, which -> cont.resume(profiles[which]) }
                .setOnCancelListener { cont.resume(null) }
                .show()
        }
    }

    private fun applyProfile(p: OciConfigProfile) {
        if (editName.text.isNullOrBlank()) {
            editName.setText("OCI - ${p.name}")
        }
        p.tenancyOcid?.let { editTenancy.setText(it) }
        p.userOcid?.let { editUser.setText(it) }
        p.region?.let { editRegion.setText(it, false) }
        p.fingerprint?.let { editFingerprint.setText(it) }
        // Compartment defaults to tenancy OCID (root compartment) — only
        // populate when the field is empty so the user can override.
        if (editCompartment.text.isNullOrBlank() && !p.tenancyOcid.isNullOrBlank()) {
            editCompartment.setText(p.tenancyOcid)
        }
    }

    // ---------- Step 2: PEM import + fingerprint round-trip -------------

    private fun onPemPicked(uri: Uri) {
        lifecycleScope.launch {
            val pem = readTextFromUri(uri) ?: run {
                Toast.makeText(this@OciOnboardingActivity, "Could not read file", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val km = tryParsePem(pem) ?: return@launch
            keyMaterial = km
            pemText = pem
            val expected = editFingerprint.text?.toString()?.trim().orEmpty()
            val computed = km.fingerprint
            if (expected.isNotBlank() && !expected.equals(computed, ignoreCase = true)) {
                AlertDialog.Builder(this@OciOnboardingActivity)
                    .setTitle("Fingerprint mismatch")
                    .setMessage("Config fingerprint:\n$expected\n\nKey fingerprint:\n$computed\n\n" +
                        "These must match. Pick the right .pem or fix the config.")
                    .setPositiveButton("OK", null)
                    .show()
                txtPemStatus.text = "Fingerprint mismatch — see dialog."
                return@launch
            }
            // Auto-fill if the field was empty.
            if (expected.isBlank()) editFingerprint.setText(computed)
            txtPemStatus.text = "Loaded RSA key. Fingerprint: $computed"
        }
    }

    /** Try parsing the PEM; on encrypted-key failures, prompt for a
     *  passphrase and retry (loops until success or user cancels). */
    private suspend fun tryParsePem(pem: String): OciKeyMaterial? {
        var passphrase: CharArray? = null
        while (true) {
            try {
                val km = withContext(Dispatchers.Default) {
                    OciKeyMaterial.fromPem(pem, passphrase)
                }
                keyPassphrase = passphrase?.let { String(it) }
                return km
            } catch (e: IllegalArgumentException) {
                if (e.message?.contains("passphrase", ignoreCase = true) == true) {
                    val pp = promptPassphrase() ?: return null
                    passphrase = pp.toCharArray()
                    continue
                }
                AlertDialog.Builder(this@OciOnboardingActivity)
                    .setTitle("Could not load key")
                    .setMessage(e.message ?: "Unknown PEM parse error")
                    .setPositiveButton("OK", null)
                    .show()
                return null
            } catch (e: Exception) {
                // Wrong passphrase typically surfaces as a BC exception, not
                // an IllegalArgumentException — treat as a re-prompt.
                Logger.w("OciOnboarding", "PEM parse threw ${e.javaClass.simpleName}: ${e.message}")
                val pp = promptPassphrase("Wrong passphrase. Try again:") ?: return null
                passphrase = pp.toCharArray()
            }
        }
    }

    private suspend fun promptPassphrase(msg: String = "Passphrase for the encrypted PEM:"): String? {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val edit = TextInputEditText(this@OciOnboardingActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Passphrase"
            }
            AlertDialog.Builder(this@OciOnboardingActivity)
                .setTitle("Encrypted private key")
                .setMessage(msg)
                .setView(edit)
                .setPositiveButton("OK") { _, _ ->
                    cont.resume(edit.text?.toString().orEmpty())
                }
                .setNegativeButton("Cancel") { _, _ -> cont.resume(null) }
                .setOnCancelListener { cont.resume(null) }
                .show()
        }
    }

    // ---------- Step 3: validate + save ---------------------------------

    private fun runValidate() {
        val km = keyMaterial ?: run {
            Toast.makeText(this, "Pick a PEM key first", Toast.LENGTH_SHORT).show()
            return
        }
        val tenancy = editTenancy.text?.toString()?.trim().orEmpty()
        val user = editUser.text?.toString()?.trim().orEmpty()
        val region = editRegion.text?.toString()?.trim().orEmpty()
        val fingerprint = editFingerprint.text?.toString()?.trim().orEmpty()
        if (tenancy.isBlank() || user.isBlank() || region.isBlank() || fingerprint.isBlank()) {
            txtTestStatus.text = "Fill in tenancy / user / region / fingerprint first."
            return
        }
        txtTestStatus.text = "Calling identity.$region.oci.oraclecloud.com…"
        btnTest.isEnabled = false
        lifecycleScope.launch {
            try {
                val client = OciApiClient(tenancy, user, fingerprint, region, km)
                val ok = client.validateCredentials()
                txtTestStatus.text = if (ok) {
                    "✅ OCI accepted the signed request."
                } else {
                    "❌ OCI rejected the request (HTTP error). See logs."
                }
            } catch (e: Exception) {
                txtTestStatus.text = "❌ Network error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                btnTest.isEnabled = true
            }
        }
    }

    private fun runSave() {
        val km = keyMaterial ?: run {
            Toast.makeText(this, "Pick a PEM key first", Toast.LENGTH_SHORT).show()
            return
        }
        val pem = pemText ?: return
        val name = editName.text?.toString()?.trim().orEmpty()
        val tenancy = editTenancy.text?.toString()?.trim().orEmpty()
        val user = editUser.text?.toString()?.trim().orEmpty()
        val region = editRegion.text?.toString()?.trim().orEmpty()
        val fingerprint = editFingerprint.text?.toString()?.trim().orEmpty()
        val compartment = editCompartment.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: tenancy
        if (name.isBlank() || tenancy.isBlank() || user.isBlank() ||
            region.isBlank() || fingerprint.isBlank()) {
            Toast.makeText(this, "Fill in every field first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!fingerprint.equals(km.fingerprint, ignoreCase = true)) {
            Toast.makeText(this, "Fingerprint doesn't match the loaded key", Toast.LENGTH_LONG).show()
            return
        }

        btnSave.isEnabled = false
        lifecycleScope.launch {
            try {
                val profile = HypervisorProfile(
                    name = name,
                    type = HypervisorType.OCI,
                    host = region, // legacy column — display only
                    port = 443,
                    username = user, // free-text; OCI auth doesn't use this
                    password = "",
                    realm = null,
                    verifySsl = true,
                    authType = "oci_api_key",
                    ociTenancyOcid = tenancy,
                    ociUserOcid = user,
                    ociRegion = region,
                    ociFingerprint = fingerprint,
                    ociCompartmentOcid = compartment
                )
                val newId = withContext(Dispatchers.IO) {
                    app.database.hypervisorDao().insert(profile)
                }
                val pm = app.securePasswordManager
                val pemOk = withContext(Dispatchers.IO) {
                    pm.storePassword(
                        "oci_private_key_$newId", pem,
                        SecurePasswordManager.StorageLevel.ENCRYPTED
                    )
                }
                val pp = keyPassphrase
                if (!pp.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) {
                        pm.storePassword(
                            "oci_passphrase_$newId", pp,
                            SecurePasswordManager.StorageLevel.ENCRYPTED
                        )
                    }
                }
                if (!pemOk) {
                    Toast.makeText(
                        this@OciOnboardingActivity,
                        "Saved row but Keystore PEM write failed — re-import to retry",
                        Toast.LENGTH_LONG
                    ).show()
                }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_HYPERVISOR_ID, newId))
                finish()
            } catch (e: Exception) {
                Logger.e("OciOnboarding", "Save failed", e)
                Toast.makeText(
                    this@OciOnboardingActivity,
                    "Save failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                btnSave.isEnabled = true
            }
        }
    }

    // ---------- shared helpers ------------------------------------------

    private suspend fun readTextFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            Logger.w("OciOnboarding", "Read failed for $uri", e)
            null
        }
    }

    companion object {
        const val EXTRA_HYPERVISOR_ID = "oci_hypervisor_id"

        /**
         * Seed list of public OCI commercial regions (Oracle adds a
         * region every few months — `MaterialAutoCompleteTextView` lets
         * the user type a custom one). Kept in `TODO.AI.md` as the
         * canonical list.
         */
        private val REGION_SEED = arrayOf(
            "us-ashburn-1", "us-phoenix-1", "us-chicago-1", "us-sanjose-1",
            "ca-toronto-1", "ca-montreal-1",
            "sa-saopaulo-1", "sa-vinhedo-1", "sa-santiago-1",
            "uk-london-1", "uk-cardiff-1",
            "eu-frankfurt-1", "eu-amsterdam-1", "eu-zurich-1", "eu-stockholm-1",
            "eu-marseille-1", "eu-milan-1", "eu-madrid-1", "eu-paris-1",
            "me-jeddah-1", "me-dubai-1", "me-abudhabi-1",
            "ap-tokyo-1", "ap-osaka-1", "ap-seoul-1", "ap-sydney-1",
            "ap-melbourne-1", "ap-mumbai-1", "ap-hyderabad-1", "ap-singapore-1",
            "af-johannesburg-1", "il-jerusalem-1",
            "mx-queretaro-1", "mx-monterrey-1"
        )
    }
}
