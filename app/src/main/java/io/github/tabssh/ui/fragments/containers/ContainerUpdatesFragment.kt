package io.github.tabssh.ui.fragments.containers

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.registry.HostUpdateScanner
import io.github.tabssh.containers.registry.StackPruner
import io.github.tabssh.containers.registry.RegistryClient
import io.github.tabssh.containers.registry.Resolved
import io.github.tabssh.containers.registry.UpdateApplier
import io.github.tabssh.containers.registry.UpdateChecker
import io.github.tabssh.containers.registry.UpdateSettings
import io.github.tabssh.containers.registry.UpdateSettingsResolver
import io.github.tabssh.containers.registry.UpdateTier
import io.github.tabssh.containers.runconfig.RecreateContainer
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerSummary
import io.github.tabssh.crypto.storage.RegistryCredentialStore
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.ui.dialogs.AutoUpdatePolicyDialog
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.dialogs.UpdateApplyDialog
import io.github.tabssh.ui.utils.ContainerText
import io.github.tabssh.ui.utils.TriStateToggle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.tabssh.utils.logging.Logger

/**
 * Updates destination (Docker and Podman). Top to bottom: a summary of this host's update
 * settings with a "Check now" scan (the settings themselves are in the overflow menu), the containers and compose projects that have a
 * newer image waiting, and every running image and stack with its effective setting
 * and the tier that decided it. Precedence is image or stack, then host, then global
 * ([UpdateSettingsResolver]); a tap on any row edits that row's own override.
 */
class ContainerUpdatesFragment : ContainerPageFragment() {

    private lateinit var progressBar: ProgressBar
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView
    private lateinit var content: View
    private lateinit var textHostSummary: TextView
    private lateinit var textLastChecked: TextView
    private lateinit var buttonCheckNow: MaterialButton
    private lateinit var textAvailableEmpty: TextView
    private lateinit var listAvailable: LinearLayout
    private lateinit var textAllEmpty: TextView
    private lateinit var listAll: LinearLayout

    private var currentHost: ContainerHost? = null
    private var containers: List<ContainerSummary> = emptyList()

    /** Every container on the host, running or not; what the pending-update list is matched against. */
    private var allContainers: List<ContainerSummary> = emptyList()
    private var policies: List<ContainerAutoUpdatePolicy> = emptyList()

    // True while the selectors are being set from stored values, so that does not read as a user edit.
    private var binding = false
    private var checking = false
    private val hostSaveMutex = Mutex()

    /** Views of the Update settings dialog while it is showing; null otherwise. */
    private class SettingsViews(
        val toggleEnabled: MaterialButtonToggleGroup,
        val toggleAutoApply: MaterialButtonToggleGroup,
        val hintEnabled: TextView,
        val hintAutoApply: TextView,
        val layoutInterval: TextInputLayout,
        val editInterval: TextInputEditText
    )

    private var settingsViews: SettingsViews? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_container_updates, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        progressBar = view.findViewById(R.id.progress_bar)
        errorState = view.findViewById(R.id.error_state)
        textError = view.findViewById(R.id.text_error)
        content = view.findViewById(R.id.content)
        textHostSummary = view.findViewById(R.id.text_host_summary)
        textLastChecked = view.findViewById(R.id.text_last_checked)
        buttonCheckNow = view.findViewById(R.id.button_check_now)
        textAvailableEmpty = view.findViewById(R.id.text_available_empty)
        listAvailable = view.findViewById(R.id.list_available)
        textAllEmpty = view.findViewById(R.id.text_all_empty)
        listAll = view.findViewById(R.id.list_all)

        view.findViewById<MaterialButton>(R.id.button_retry).setOnClickListener {
            session?.let { onSessionReady(it) }
        }

        buttonCheckNow.setOnClickListener { checkNow() }

        // The host's update settings live in the overflow menu, only while this tab is the visible one.
        requireActivity().addMenuProvider(object : androidx.core.view.MenuProvider {
            override fun onCreateMenu(menu: android.view.Menu, menuInflater: android.view.MenuInflater) {
                menu.add(android.view.Menu.NONE, R.id.action_update_settings, android.view.Menu.NONE, R.string.container_updates_menu_settings)
                    .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
            }

            override fun onMenuItemSelected(menuItem: android.view.MenuItem): Boolean {
                if (menuItem.itemId != R.id.action_update_settings) return false
                showSettingsDialog()
                return true
            }
        }, viewLifecycleOwner, androidx.lifecycle.Lifecycle.State.RESUMED)

        // Base class wires sessionFlow/refreshFlow into onSessionReady.
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onSessionReady(session: ContainerSessionManager.ContainerSession) {
        progressBar.visibility = View.VISIBLE
        errorState.visibility = View.GONE
        startLoad {
            val host = app.database.containerHostDao().getById(manager.hostId) ?: session.host
            val listed = session.transport.listContainers(all = false)
            // Stopped containers still exist and can still be updated, so the pending list is judged
            // against every container on the host, not only the running ones.
            val everything = (session.transport.listContainers(all = true) as? ContainerResult.Success)?.value
            allContainers = everything.orEmpty()
            val policyDao = app.database.containerAutoUpdatePolicyDao()
            var loadedPolicies = policyDao.getPoliciesForHost(manager.hostId).first()
            if (everything != null) {
                val existing = everything
                    .flatMap { c -> c.names.map { it.trimStart('/') }.ifEmpty { listOf(c.id.take(12)) } }
                    .toSet()
                val gone = HostUpdateScanner.clearPendingForMissing(
                    policyDao, host.name, loadedPolicies, existing
                ).map { it.id }.toSet()
                if (gone.isNotEmpty()) loadedPolicies = loadedPolicies.filter { it.id !in gone }
            }
            if (!isAdded) return@startLoad
            progressBar.visibility = View.GONE
            when (listed) {
                is ContainerResult.Success -> {
                    currentHost = host
                    containers = listed.value.filter { c ->
                        c.names.firstOrNull()?.trimStart('/')
                            ?.endsWith(RecreateContainer.OLD_NAME_SUFFIX) == false
                    }
                    policies = loadedPolicies
                    content.visibility = View.VISIBLE
                    render()
                }
                else -> {
                    content.visibility = View.GONE
                    errorState.visibility = View.VISIBLE
                    textError.text = ContainerErrorPresenter.messageFor(requireContext(), listed)
                }
            }
        }
    }

    private fun globalSettings(): HostUpdateScanner.GlobalSettings {
        val prefs = app.preferencesManager
        return HostUpdateScanner.GlobalSettings(
            enabled = prefs.isContainerUpdateCheckEnabled(),
            autoApply = prefs.isContainerAutoApplyEnabled(),
            intervalHours = prefs.getContainerUpdateIntervalHours().toLong()
        )
    }

    private fun resolve(
        host: ContainerHost,
        stack: ContainerAutoUpdatePolicy?,
        image: ContainerAutoUpdatePolicy?
    ): UpdateSettings {
        val g = globalSettings()
        return UpdateSettingsResolver.resolve(
            g.enabled, g.autoApply, g.intervalHours,
            host.updateEnabledOverride, host.updateAutoApply, host.updateCheckIntervalHours,
            stack?.enabledOverride, stack?.autoApplyOverride,
            image?.enabledOverride, image?.autoApplyOverride
        )
    }

    private fun render() {
        val host = currentHost ?: return
        renderHostCard(host)
        renderAvailable()
        renderAll(host)
    }

    private fun renderHostCard(host: ContainerHost) {
        val resolved = resolve(host, null, null)
        val hours = host.updateCheckIntervalHours ?: globalSettings().intervalHours.toInt()
        textHostSummary.text = getString(
            R.string.container_updates_host_summary,
            valueLabel(resolved.enabled.value),
            valueLabel(resolved.autoApply.value),
            hours
        )
        textLastChecked.text = if (host.lastUpdateCheck > 0) {
            getString(
                R.string.container_updates_last_checked,
                DateUtils.getRelativeTimeSpanString(
                    host.lastUpdateCheck, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                )
            )
        } else {
            getString(R.string.container_updates_never_checked)
        }
        settingsViews?.let { bindSettings(it, host) }
    }

    /** Fill the settings dialog from the stored host row. */
    private fun bindSettings(views: SettingsViews, host: ContainerHost) {
        val inherited = resolve(host.copy(updateEnabledOverride = null, updateAutoApply = null), null, null)
        binding = true
        TriStateToggle.set(views.toggleEnabled, host.updateEnabledOverride)
        TriStateToggle.set(views.toggleAutoApply, host.updateAutoApply)
        binding = false
        views.hintEnabled.text = hostHint(host.updateEnabledOverride, inherited.enabled)
        views.hintAutoApply.text = hostHint(host.updateAutoApply, inherited.autoApply)
        if (!views.editInterval.hasFocus()) {
            views.editInterval.setText(host.updateCheckIntervalHours?.toString().orEmpty())
        }
        views.layoutInterval.error = null
        views.layoutInterval.helperText = getString(
            if (host.updateCheckIntervalHours == null) {
                R.string.container_updates_interval_helper_global
            } else {
                R.string.container_updates_interval_helper_custom
            },
            globalSettings().intervalHours.toInt()
        )
    }

    private fun showSettingsDialog() {
        val host = currentHost ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_container_update_settings, null)
        val views = SettingsViews(
            toggleEnabled = dialogView.findViewById(R.id.toggle_enabled),
            toggleAutoApply = dialogView.findViewById(R.id.toggle_auto_apply),
            hintEnabled = dialogView.findViewById(R.id.text_enabled_hint),
            hintAutoApply = dialogView.findViewById(R.id.text_auto_apply_hint),
            layoutInterval = dialogView.findViewById(R.id.layout_interval),
            editInterval = dialogView.findViewById(R.id.edit_interval)
        )
        TriStateToggle.prepare(views.toggleEnabled) { value ->
            if (!binding) saveHost { it.copy(updateEnabledOverride = value) }
        }
        TriStateToggle.prepare(views.toggleAutoApply) { value ->
            if (!binding) saveHost { it.copy(updateAutoApply = value) }
        }
        views.editInterval.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                views.editInterval.clearFocus()
                true
            } else {
                false
            }
        }
        views.editInterval.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitInterval()
        }
        settingsViews = views
        bindSettings(views, host)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.container_updates_settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.container_updates_settings_done, null)
            .setOnDismissListener {
                commitInterval()
                settingsViews = null
            }
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (commitInterval()) dialog.dismiss()
        }
    }

    private fun hostHint(selected: Boolean?, inherited: Resolved<Boolean>): String =
        if (selected == null) {
            getString(
                R.string.container_updates_hint_inherited,
                valueLabel(inherited.value),
                getString(R.string.container_updates_tier_global)
            )
        } else {
            getString(R.string.container_updates_hint_set_host, valueLabel(selected))
        }

    private fun valueLabel(on: Boolean): String = getString(
        if (on) R.string.container_updates_value_on else R.string.container_updates_value_off
    )

    private fun nameOf(container: ContainerSummary): String =
        container.names.firstOrNull()?.trimStart('/') ?: container.id.take(12)

    private fun projectOf(container: ContainerSummary): String? =
        container.labels[UpdateApplier.COMPOSE_PROJECT_LABEL]?.takeIf { it.isNotEmpty() }

    private fun renderAvailable() {
        listAvailable.removeAllViews()
        val byName = (allContainers.ifEmpty { containers }).associateBy { nameOf(it) }
        val pending = policies.filter {
            it.scope != HostUpdateScanner.SCOPE_STACK && it.pendingUpdateDigest != null &&
                byName.containsKey(it.containerNameOrStackName)
        }
        val units = pending.groupBy { policy ->
            byName[policy.containerNameOrStackName]?.let { projectOf(it) }
                ?: "container:${policy.containerNameOrStackName}"
        }
        textAvailableEmpty.visibility = if (units.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(requireContext())
        for ((key, members) in units) {
            val isProject = !key.startsWith("container:")
            val label = if (isProject) key else members.first().containerNameOrStackName
            val row = inflater.inflate(R.layout.item_update_available, listAvailable, false)
            val safeLabel = ContainerText.display(label)
            row.findViewById<TextView>(R.id.text_title).text = safeLabel
            row.findViewById<TextView>(R.id.text_subtitle).text = if (isProject) {
                getString(
                    R.string.container_updates_available_members,
                    members.size,
                    members.joinToString(", ") { ContainerText.display(it.containerNameOrStackName) }
                )
            } else {
                ContainerText.display(byName[label]?.image)
            }
            row.findViewById<MaterialButton>(R.id.button_update).apply {
                contentDescription = getString(R.string.container_updates_update_desc, safeLabel)
                setOnClickListener { applyUpdate(members.first(), label) }
            }
            listAvailable.addView(row)
        }
    }

    private fun applyUpdate(policy: ContainerAutoUpdatePolicy, label: String) {
        val current = session ?: return
        UpdateApplyDialog.show(
            requireContext(),
            viewLifecycleOwner,
            app.database.containerAutoUpdatePolicyDao(),
            policy.id,
            current.transport,
            label
        ) {
            if (isAdded) onSessionReady(current)
        }
    }

    private fun renderAll(host: ContainerHost) {
        listAll.removeAllViews()
        textAllEmpty.visibility = if (containers.isEmpty()) View.VISIBLE else View.GONE
        val byName = policies.filter { it.scope != HostUpdateScanner.SCOPE_STACK }
            .associateBy { it.containerNameOrStackName }
        val byProject = policies.filter { it.scope == HostUpdateScanner.SCOPE_STACK }
            .associateBy { it.containerNameOrStackName }
        val standalone = containers.filter { projectOf(it) == null }.sortedBy { nameOf(it) }
        val projects = containers.mapNotNull { projectOf(it) }.distinct().sorted()
        val inflater = LayoutInflater.from(requireContext())

        for (project in projects) {
            val stack = byProject[project]
            val title = getString(R.string.container_updates_stack_row_title, ContainerText.display(project))
            addSettingRow(inflater, title, null, resolve(host, null, stack), 0) {
                AutoUpdatePolicyDialog.show(
                    requireContext(), app, manager.hostId, project, project, true
                ) { session?.let { onSessionReady(it) } }
            }
            for (container in containers.filter { projectOf(it) == project }.sortedBy { nameOf(it) }) {
                val name = nameOf(container)
                addSettingRow(
                    inflater, ContainerText.display(name), container.image,
                    resolve(host, stack, byName[name]), resources.getDimensionPixelSize(R.dimen.space_lg)
                ) {
                    AutoUpdatePolicyDialog.show(
                        requireContext(), app, manager.hostId, name, project
                    ) { session?.let { onSessionReady(it) } }
                }
            }
        }
        for (container in standalone) {
            val name = nameOf(container)
            addSettingRow(
                inflater, ContainerText.display(name), container.image,
                resolve(host, null, byName[name]), 0
            ) {
                AutoUpdatePolicyDialog.show(
                    requireContext(), app, manager.hostId, name
                ) { session?.let { onSessionReady(it) } }
            }
        }
    }

    private fun addSettingRow(
        inflater: LayoutInflater,
        title: String,
        image: String?,
        settings: UpdateSettings,
        indentPx: Int,
        onClick: () -> Unit
    ) {
        val row = inflater.inflate(R.layout.item_update_setting, listAll, false)
        (row.layoutParams as ViewGroup.MarginLayoutParams).marginStart = indentPx
        row.findViewById<TextView>(R.id.text_title).text = title
        val summary = getString(
            R.string.container_updates_row_summary,
            valueLabel(settings.enabled.value),
            valueLabel(settings.autoApply.value)
        )
        row.findViewById<TextView>(R.id.text_subtitle).text =
            if (image.isNullOrEmpty()) summary else "${ContainerText.display(image)} - $summary"
        row.findViewById<TextView>(R.id.text_source).text = sourceLabel(settings)
        row.contentDescription = getString(R.string.container_updates_edit_desc, title)
        row.setOnClickListener { onClick() }
        listAll.addView(row)
    }

    private fun sourceLabel(settings: UpdateSettings): String {
        val enabled = tierSource(settings.enabled.tier)
        val autoApply = tierSource(settings.autoApply.tier)
        return if (settings.enabled.tier == settings.autoApply.tier) {
            enabled
        } else {
            getString(R.string.container_updates_row_source_split, enabled, autoApply)
        }
    }

    private fun tierSource(tier: UpdateTier): String = getString(
        when (tier) {
            UpdateTier.GLOBAL -> R.string.container_updates_source_global
            UpdateTier.HOST -> R.string.container_updates_source_host
            UpdateTier.STACK -> R.string.container_updates_source_stack
            UpdateTier.IMAGE -> R.string.container_updates_source_image
        }
    )

    /** Persist a host-tier edit on a fresh copy of the row, then redraw so every effective value follows. */
    private fun saveHost(transform: (ContainerHost) -> ContainerHost) {
        viewLifecycleOwner.lifecycleScope.launch {
            hostSaveMutex.withLock {
                val dao = app.database.containerHostDao()
                val fresh = dao.getById(manager.hostId) ?: return@withLock
                val updated = transform(fresh).copy(modifiedAt = System.currentTimeMillis())
                try {
                    dao.update(updated)
                } catch (e: Exception) {
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(), R.string.container_updates_settings_save_failed, Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@withLock
                }
                if (!isAdded) return@withLock
                currentHost = updated
                render()
            }
        }
    }

    private fun commitInterval(): Boolean {
        val host = currentHost ?: return false
        val views = settingsViews ?: return false
        val text = views.editInterval.text?.toString()?.trim().orEmpty()
        val hours = if (text.isEmpty()) null else text.toIntOrNull()
        if (text.isNotEmpty() && (hours == null || hours !in MIN_INTERVAL_HOURS..MAX_INTERVAL_HOURS)) {
            views.layoutInterval.error = getString(R.string.container_updates_interval_invalid)
            return false
        }
        views.layoutInterval.error = null
        if (hours == host.updateCheckIntervalHours) return true
        saveHost { it.copy(updateCheckIntervalHours = hours) }
        return true
    }

    /** Scan this host now over the live session, honoring the resolved settings, then redraw. */
    private fun checkNow() {
        val current = session ?: return
        val host = currentHost ?: return
        if (checking) return
        checking = true
        buttonCheckNow.isEnabled = false
        buttonCheckNow.setText(R.string.container_updates_checking)
        Logger.event(TAG, "update check on ${host.name} started")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val appContext = requireContext().applicationContext
                val dao = app.database.containerAutoUpdatePolicyDao()
                val checker = UpdateChecker(
                    dao, app.database.registryCredentialDao(), RegistryClient()
                ) { credentialId -> RegistryCredentialStore.retrieve(appContext, credentialId) }
                val hostPolicies = dao.getPoliciesForHost(host.id).first()
                val failures = mutableListOf<String>()
                val found = HostUpdateScanner(dao, checker, StackPruner(appContext, app.database))
                    .scan(host, hostPolicies, globalSettings(), current.transport, failures)
                // Same record the scheduled check leaves, so the Container Updates page reports a manual
                // check too instead of showing a stale "last known" state.
                val removed = if (found == null) {
                    HostUpdateScanner.discardAllUnconfirmed(dao, host.name, host.id)
                } else {
                    0
                }
                io.github.tabssh.containers.registry.UpdateCheckStatus.record(
                    host.id,
                    io.github.tabssh.containers.registry.UpdateCheckStatus.HostResult(
                        if (found == null) io.github.tabssh.containers.registry.UpdateCheckStatus.Outcome.LIST_FAILED
                        else io.github.tabssh.containers.registry.UpdateCheckStatus.Outcome.CHECKED,
                        System.currentTimeMillis(), failures.size, failures.firstOrNull(), removed
                    )
                )
                if (found != null) {
                    app.database.containerHostDao().updateLastUpdateCheck(host.id, System.currentTimeMillis())
                    Logger.event(TAG, "update check on ${host.name} finished: ${found.size} update(s) available")
                } else {
                    Logger.w(TAG, "update check on ${host.name} could not read the container list")
                }
                if (!isAdded) return@launch
                val message = when {
                    found == null && removed > 0 -> getString(R.string.container_updates_check_failed_removed, removed)
                    found == null -> getString(R.string.container_updates_check_failed)
                    failures.isNotEmpty() -> getString(R.string.container_updates_check_partial, failures.size)
                    found.isEmpty() -> getString(R.string.container_updates_check_none)
                    else -> resources.getQuantityString(
                        R.plurals.container_updates_check_found, found.size, found.size
                    )
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                onSessionReady(current)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "update check on ${host.name} failed", e)
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.container_updates_check_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                checking = false
                if (isAdded) {
                    buttonCheckNow.isEnabled = true
                    buttonCheckNow.setText(R.string.container_updates_check_now)
                }
            }
        }
    }

    private companion object {
        const val TAG = "ContainerUpdatesFragment"
        const val MIN_INTERVAL_HOURS = 1
        const val MAX_INTERVAL_HOURS = 8760
    }
}
