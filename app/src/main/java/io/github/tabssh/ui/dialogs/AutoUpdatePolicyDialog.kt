package io.github.tabssh.ui.dialogs

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Per-container auto-update policy editor: toggles the
 * digest check and the pull+recreate gate, and links an optional registry
 * credential for private images.
 */
object AutoUpdatePolicyDialog {

    /** Edit (or create) the policy row for [containerName] on [hostId]. */
    fun show(context: Context, app: TabSSHApplication, hostId: Long, containerName: String) {
        // Callers always pass an activity, but an unchecked cast would crash the
        // app on a themed/application context — fail closed instead.
        val owner = context as? LifecycleOwner ?: return
        val activity = context as? Activity
        owner.lifecycleScope.launch {
            val policyDao = app.database.containerAutoUpdatePolicyDao()
            val existing = policyDao.getPoliciesForHost(hostId).first()
                .firstOrNull {
                    it.containerNameOrStackName == containerName && it.scope == "container"
                }
            val credentials = app.database.registryCredentialDao().getAllList()

            // Two DAO reads have suspended by this point — showing a dialog on
            // a finished activity throws BadTokenException.
            if (activity != null && (activity.isFinishing || activity.isDestroyed)) return@launch

            val view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_auto_update_policy, null)
            val switchEnabled = view.findViewById<SwitchMaterial>(R.id.switch_enabled)
            val switchAutoRecreate = view.findViewById<SwitchMaterial>(R.id.switch_auto_recreate)
            val spinnerCredential = view.findViewById<Spinner>(R.id.spinner_credential)

            // First spinner row means "no credential" (anonymous pulls).
            val labels = mutableListOf(context.getString(R.string.docker_policy_none))
            credentials.forEach { labels.add(it.registryHost) }
            spinnerCredential.adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_dropdown_item, labels
            )

            switchEnabled.isChecked = existing?.enabled ?: true
            switchAutoRecreate.isChecked = existing?.autoRecreateOnUpdate ?: false
            val selectedIndex = credentials.indexOfFirst {
                it.id == existing?.registryCredentialId
            }
            if (selectedIndex >= 0) spinnerCredential.setSelection(selectedIndex + 1)

            MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.docker_policy_title))
                .setView(view)
                .setPositiveButton(R.string.save) { _, _ ->
                    val credentialId = credentials
                        .getOrNull(spinnerCredential.selectedItemPosition - 1)?.id
                    owner.lifecycleScope.launch {
                        val policy = ContainerAutoUpdatePolicy(
                            id = existing?.id ?: 0,
                            dockerHostId = hostId,
                            containerNameOrStackName = containerName,
                            scope = "container",
                            enabled = switchEnabled.isChecked,
                            autoRecreateOnUpdate = switchAutoRecreate.isChecked,
                            registryCredentialId = credentialId,
                            lastCheckedAt = existing?.lastCheckedAt ?: 0,
                            lastDigestSeen = existing?.lastDigestSeen,
                            pendingUpdateDigest = existing?.pendingUpdateDigest
                        )
                        if (existing == null) {
                            policyDao.insert(policy)
                        } else {
                            policyDao.update(policy)
                        }
                        if (activity != null &&
                            (activity.isFinishing || activity.isDestroyed)
                        ) {
                            return@launch
                        }
                        Toast.makeText(
                            context, R.string.docker_policy_saved, Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
