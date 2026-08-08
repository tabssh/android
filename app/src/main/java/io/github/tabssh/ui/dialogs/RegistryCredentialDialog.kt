package io.github.tabssh.ui.dialogs

import android.view.LayoutInflater
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.RegistryCredentialStore
import io.github.tabssh.storage.database.entities.RegistryCredential
import kotlinx.coroutines.launch

/**
 * Registry credential manager (PLAN.AI.md step 30): list, add, edit, and
 * delete credentials for private registries. Both the list and the editor
 * explain what the credentials are for: the auto-update checker's on-device
 * HEAD /v2 manifest digest checks. The secret never touches Room — it goes
 * straight to RegistryCredentialStore (Keystore-encrypted) keyed by the
 * credential row id.
 */
object RegistryCredentialDialog {

    // Wire values for RegistryCredential.authType, in dropdown order.
    private val AUTH_TYPES = listOf("basic", "token", "anonymous")

    // Display-label resource per wire value, same order as AUTH_TYPES.
    private val AUTH_TYPE_LABELS = listOf(
        R.string.docker_registry_auth_basic,
        R.string.docker_registry_auth_token,
        R.string.docker_registry_auth_anonymous
    )

    /** Show the credential list for [app]'s database. */
    fun show(activity: AppCompatActivity, app: TabSSHApplication) {
        activity.lifecycleScope.launch {
            val credentials = app.database.registryCredentialDao().getAllList()
            val view = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_registry_credential_list, null)
            val list = view.findViewById<ListView>(R.id.list_credentials)
            val empty = view.findViewById<TextView>(R.id.text_registry_empty)

            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.docker_registry_title)
                .setView(view)
                .setPositiveButton(R.string.docker_registry_add) { _, _ ->
                    showEditor(activity, app, null)
                }
                .setNegativeButton(R.string.close, null)
                .create()

            if (credentials.isEmpty()) {
                empty.isVisible = true
            } else {
                list.isVisible = true
                val rows = credentials.map { credential ->
                    mapOf(
                        "host" to credential.registryHost,
                        "detail" to listItemDetail(activity, credential)
                    )
                }
                list.adapter = SimpleAdapter(
                    activity, rows, android.R.layout.simple_list_item_2,
                    arrayOf("host", "detail"),
                    intArrayOf(android.R.id.text1, android.R.id.text2)
                )
                list.setOnItemClickListener { _, _, position, _ ->
                    dialog.dismiss()
                    showItemMenu(activity, app, credentials[position])
                }
            }
            dialog.show()
        }
    }

    /** Second list-row line: username when present, else the auth-type label. */
    private fun listItemDetail(
        activity: AppCompatActivity,
        credential: RegistryCredential
    ): String {
        if (credential.username.isNotEmpty()) return credential.username
        val index = AUTH_TYPES.indexOf(credential.authType)
        return activity.getString(
            if (index >= 0) AUTH_TYPE_LABELS[index] else AUTH_TYPE_LABELS[0]
        )
    }

    private fun showItemMenu(
        activity: AppCompatActivity,
        app: TabSSHApplication,
        credential: RegistryCredential
    ) {
        val options = arrayOf(
            activity.getString(R.string.edit),
            activity.getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(credential.registryHost)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditor(activity, app, credential)
                    1 -> confirmDelete(activity, app, credential)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditor(
        activity: AppCompatActivity,
        app: TabSSHApplication,
        existing: RegistryCredential?
    ) {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_registry_credential, null)
        val tilHost = view.findViewById<TextInputLayout>(R.id.til_host)
        val editHost = view.findViewById<TextInputEditText>(R.id.edit_host)
        val tilUsername = view.findViewById<TextInputLayout>(R.id.til_username)
        val editUsername = view.findViewById<TextInputEditText>(R.id.edit_username)
        val tilSecret = view.findViewById<TextInputLayout>(R.id.til_secret)
        val editSecret = view.findViewById<TextInputEditText>(R.id.edit_secret)
        val dropdownAuthType =
            view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_auth_type)

        val labels = AUTH_TYPE_LABELS.map { activity.getString(it) }
        dropdownAuthType.setSimpleItems(labels.toTypedArray())

        // Anonymous needs neither field; bearer tokens need no username.
        fun applyAuthTypeState(wireType: String) {
            val anonymous = wireType == "anonymous"
            tilUsername.isEnabled = wireType == "basic"
            tilSecret.isEnabled = !anonymous
        }

        var selectedIndex = existing?.let { AUTH_TYPES.indexOf(it.authType) }
            ?.takeIf { it >= 0 } ?: 0
        dropdownAuthType.setText(labels[selectedIndex], false)
        applyAuthTypeState(AUTH_TYPES[selectedIndex])
        dropdownAuthType.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            applyAuthTypeState(AUTH_TYPES[position])
        }

        if (existing != null) {
            editHost.setText(existing.registryHost)
            editUsername.setText(existing.username)
            tilSecret.helperText =
                activity.getString(R.string.docker_registry_secret_keep_helper)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(
                if (existing == null) {
                    R.string.docker_registry_add
                } else {
                    R.string.docker_registry_edit_title
                }
            )
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        // Validate on the button so a bad host keeps the dialog (and input) open.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener {
                val host = editHost.text?.toString()?.trim().orEmpty()
                if (host.isEmpty()) {
                    tilHost.error = activity.getString(R.string.docker_registry_error_host)
                    return@setOnClickListener
                }
                tilHost.error = null
                dialog.dismiss()
                save(
                    activity, app, existing, host,
                    editUsername.text?.toString()?.trim().orEmpty(),
                    editSecret.text?.toString().orEmpty(),
                    AUTH_TYPES[selectedIndex]
                )
            }
    }

    private fun save(
        activity: AppCompatActivity,
        app: TabSSHApplication,
        existing: RegistryCredential?,
        host: String,
        username: String,
        secret: String,
        authType: String
    ) {
        activity.lifecycleScope.launch {
            val dao = app.database.registryCredentialDao()
            val id = if (existing == null) {
                dao.insert(
                    RegistryCredential(
                        registryHost = host, username = username, authType = authType
                    )
                )
            } else {
                dao.update(
                    existing.copy(
                        registryHost = host, username = username, authType = authType
                    )
                )
                existing.id
            }
            // Secret goes to the Keystore-backed store only; blank keeps the old one.
            if (secret.isNotEmpty()) {
                RegistryCredentialStore.store(activity, id, secret)
            }
            Toast.makeText(activity, R.string.docker_registry_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(
        activity: AppCompatActivity,
        app: TabSSHApplication,
        credential: RegistryCredential
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(credential.registryHost)
            .setMessage(
                activity.getString(
                    R.string.docker_registry_delete_message, credential.registryHost
                )
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                activity.lifecycleScope.launch {
                    RegistryCredentialStore.clear(activity, credential.id)
                    app.database.containerAutoUpdatePolicyDao()
                        .clearRegistryCredentialId(credential.id)
                    app.database.registryCredentialDao().delete(credential)
                    io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                        activity.applicationContext,
                        io.github.tabssh.sync.tombstone.TombstoneRecorder.REGISTRY_CREDENTIAL,
                        io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(credential))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
