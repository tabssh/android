package io.github.tabssh.ui.dialogs

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.containers.registry.HostUpdateScanner
import io.github.tabssh.containers.registry.Resolved
import io.github.tabssh.containers.registry.UpdateSettingsResolver
import io.github.tabssh.containers.registry.UpdateTier
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.ui.utils.ContainerText
import io.github.tabssh.ui.utils.TriStateToggle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Per-image (or per-compose-stack) update override editor. Two tri-state
 * selectors, Inherit / On / Off, for "check for updates" and "apply updates
 * automatically"; Inherit stores null and shows the value it currently resolves to
 * and which tier supplies it. Container rows may also link a registry credential for
 * private images; stack rows are override-only and have no credential.
 */
object AutoUpdatePolicyDialog {

    /**
     * Edit (or lazily create) the override row for [name] on [hostId]. [project] is the
     * container's compose project label (null for a standalone container) and decides the
     * scope, "container" or "stack_service". With [isStack] the dialog edits the
     * project-wide "stack" row, where [name] is the project name. [onSaved] runs after
     * the row is written.
     */
    fun show(
        context: Context,
        app: TabSSHApplication,
        hostId: Long,
        name: String,
        project: String? = null,
        isStack: Boolean = false,
        onSaved: () -> Unit = {}
    ) {
        // Callers always pass an activity, but an unchecked cast would crash the
        // app on a themed/application context — fail closed instead.
        val owner = context as? LifecycleOwner ?: return
        val activity = context as? Activity
        owner.lifecycleScope.launch {
            val policyDao = app.database.containerAutoUpdatePolicyDao()
            val host = app.database.containerHostDao().getById(hostId) ?: return@launch
            val policies = policyDao.getPoliciesForHost(hostId).first()
            val existing = policies.firstOrNull {
                it.containerNameOrStackName == name &&
                    (it.scope == HostUpdateScanner.SCOPE_STACK) == isStack
            }
            val stackRow = if (!isStack && project != null) {
                policies.firstOrNull {
                    it.scope == HostUpdateScanner.SCOPE_STACK && it.containerNameOrStackName == project
                }
            } else {
                null
            }
            val credentials = app.database.registryCredentialDao().getAllList()
            val prefs = app.preferencesManager
            val inherited = UpdateSettingsResolver.resolve(
                prefs.isContainerUpdateCheckEnabled(),
                prefs.isContainerAutoApplyEnabled(),
                prefs.getContainerUpdateIntervalHours().toLong(),
                host.updateEnabledOverride, host.updateAutoApply, host.updateCheckIntervalHours,
                stackRow?.enabledOverride, stackRow?.autoApplyOverride
            )

            // DAO reads have suspended by this point — showing a dialog on
            // a finished activity throws BadTokenException.
            if (activity != null && (activity.isFinishing || activity.isDestroyed)) return@launch

            val view = LayoutInflater.from(context).inflate(R.layout.dialog_auto_update_policy, null)
            val toggleEnabled = view.findViewById<MaterialButtonToggleGroup>(R.id.toggle_enabled)
            val toggleAutoApply = view.findViewById<MaterialButtonToggleGroup>(R.id.toggle_auto_apply)
            val hintEnabled = view.findViewById<TextView>(R.id.text_enabled_hint)
            val hintAutoApply = view.findViewById<TextView>(R.id.text_auto_apply_hint)
            val spinnerCredential = view.findViewById<Spinner>(R.id.spinner_credential)
            val safeName = ContainerText.display(name)
            view.findViewById<TextView>(R.id.text_subject).text = context.getString(
                if (isStack) R.string.container_policy_name_stack else R.string.container_policy_name_image,
                safeName
            )

            TriStateToggle.prepare(toggleEnabled) {
                hintEnabled.text = hint(context, it, inherited.enabled, isStack)
            }
            TriStateToggle.prepare(toggleAutoApply) {
                hintAutoApply.text = hint(context, it, inherited.autoApply, isStack)
            }
            TriStateToggle.set(toggleEnabled, existing?.enabledOverride)
            TriStateToggle.set(toggleAutoApply, existing?.autoApplyOverride)

            // First spinner row means "no credential" (anonymous pulls).
            val labels = mutableListOf(context.getString(R.string.container_policy_none))
            credentials.forEach { labels.add(it.registryHost) }
            spinnerCredential.adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_dropdown_item, labels
            )
            val selectedIndex = credentials.indexOfFirst { it.id == existing?.registryCredentialId }
            if (selectedIndex >= 0) spinnerCredential.setSelection(selectedIndex + 1)
            if (isStack) view.findViewById<View>(R.id.group_credential).visibility = View.GONE

            MaterialAlertDialogBuilder(context)
                .setTitle(
                    context.getString(
                        if (isStack) R.string.container_policy_title_stack else R.string.container_policy_title
                    )
                )
                .setView(view)
                .setPositiveButton(R.string.save) { _, _ ->
                    val credentialId = if (isStack) {
                        existing?.registryCredentialId
                    } else {
                        credentials.getOrNull(spinnerCredential.selectedItemPosition - 1)?.id
                    }
                    val enabledOverride = TriStateToggle.get(toggleEnabled)
                    val autoApplyOverride = TriStateToggle.get(toggleAutoApply)
                    owner.lifecycleScope.launch {
                        if (existing == null) {
                            policyDao.insert(
                                ContainerAutoUpdatePolicy(
                                    containerHostId = hostId,
                                    containerNameOrStackName = name,
                                    scope = when {
                                        isStack -> HostUpdateScanner.SCOPE_STACK
                                        project != null -> HostUpdateScanner.SCOPE_STACK_SERVICE
                                        else -> HostUpdateScanner.SCOPE_CONTAINER
                                    },
                                    enabledOverride = enabledOverride,
                                    autoApplyOverride = autoApplyOverride,
                                    registryCredentialId = credentialId,
                                    modifiedAt = System.currentTimeMillis()
                                )
                            )
                        } else {
                            policyDao.update(
                                existing.copy(
                                    enabledOverride = enabledOverride,
                                    autoApplyOverride = autoApplyOverride,
                                    registryCredentialId = credentialId,
                                    modifiedAt = System.currentTimeMillis()
                                )
                            )
                        }
                        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
                            return@launch
                        }
                        Toast.makeText(
                            context, R.string.container_policy_saved, Toast.LENGTH_SHORT
                        ).show()
                        onSaved()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Caption under a selector: the inherited value and its tier, or the explicit value set here. */
    private fun hint(context: Context, selected: Boolean?, inherited: Resolved<Boolean>, isStack: Boolean): String {
        if (selected == null) {
            return context.getString(
                R.string.container_updates_hint_inherited,
                valueLabel(context, inherited.value),
                tierLabel(context, inherited.tier)
            )
        }
        return context.getString(
            if (isStack) R.string.container_updates_hint_set_stack else R.string.container_updates_hint_set_image,
            valueLabel(context, selected)
        )
    }

    private fun valueLabel(context: Context, on: Boolean): String = context.getString(
        if (on) R.string.container_updates_value_on else R.string.container_updates_value_off
    )

    private fun tierLabel(context: Context, tier: UpdateTier): String = context.getString(
        when (tier) {
            UpdateTier.GLOBAL -> R.string.container_updates_tier_global
            UpdateTier.HOST -> R.string.container_updates_tier_host
            UpdateTier.STACK -> R.string.container_updates_tier_stack
            UpdateTier.IMAGE -> R.string.container_updates_tier_image
        }
    )
}
