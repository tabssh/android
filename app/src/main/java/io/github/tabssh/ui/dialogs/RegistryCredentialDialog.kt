package io.github.tabssh.ui.dialogs

import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.RegistryCredentialStore
import io.github.tabssh.storage.database.entities.RegistryCredential
import kotlinx.coroutines.launch

/**
 * Registry credential manager (PLAN.AI.md step 30): list, add, edit, and
 * delete credentials for private registries. The secret never touches Room —
 * it goes straight to RegistryCredentialStore (Keystore-encrypted) keyed by
 * the credential row id.
 */
object RegistryCredentialDialog {

    // Wire values for RegistryCredential.authType, shown as-is.
    private val AUTH_TYPES = listOf("basic", "token", "anonymous")

    /** Show the credential list for [app]'s database. */
    fun show(activity: AppCompatActivity, app: TabSSHApplication) {
        activity.lifecycleScope.launch {
            val credentials = app.database.registryCredentialDao().getAllList()
            val builder = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.docker_registry_title)
                .setPositiveButton(R.string.docker_registry_add) { _, _ ->
                    showEditor(activity, app, null)
                }
                .setNegativeButton(R.string.close, null)
            if (credentials.isEmpty()) {
                builder.setMessage(R.string.docker_registry_empty)
            } else {
                val labels = credentials.map { credential ->
                    if (credential.username.isEmpty()) {
                        credential.registryHost
                    } else {
                        "${credential.registryHost} (${credential.username})"
                    }
                }
                builder.setItems(labels.toTypedArray()) { _, which ->
                    showItemMenu(activity, app, credentials[which])
                }
            }
            builder.show()
        }
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
            .show()
    }

    private fun showEditor(
        activity: AppCompatActivity,
        app: TabSSHApplication,
        existing: RegistryCredential?
    ) {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_registry_credential, null)
        val editHost = view.findViewById<TextInputEditText>(R.id.edit_host)
        val editUsername = view.findViewById<TextInputEditText>(R.id.edit_username)
        val editSecret = view.findViewById<TextInputEditText>(R.id.edit_secret)
        val spinnerAuthType = view.findViewById<Spinner>(R.id.spinner_auth_type)

        spinnerAuthType.adapter = ArrayAdapter(
            activity, android.R.layout.simple_spinner_dropdown_item, AUTH_TYPES
        )
        if (existing != null) {
            editHost.setText(existing.registryHost)
            editUsername.setText(existing.username)
            val index = AUTH_TYPES.indexOf(existing.authType)
            if (index >= 0) spinnerAuthType.setSelection(index)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(
                if (existing == null) {
                    R.string.docker_registry_add
                } else {
                    R.string.docker_registry_title
                }
            )
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val host = editHost.text?.toString()?.trim().orEmpty()
                if (host.isEmpty()) {
                    Toast.makeText(
                        activity, R.string.docker_registry_error_host, Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                save(
                    activity, app, existing, host,
                    editUsername.text?.toString()?.trim().orEmpty(),
                    editSecret.text?.toString().orEmpty(),
                    spinnerAuthType.selectedItem as String
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
