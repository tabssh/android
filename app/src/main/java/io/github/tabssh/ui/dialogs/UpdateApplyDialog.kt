package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.containers.registry.UpdateApplier
import io.github.tabssh.containers.runconfig.RecreateContainer.RecreateStep
import io.github.tabssh.containers.transport.ContainerTransport
import io.github.tabssh.storage.database.dao.ContainerAutoUpdatePolicyDao
import io.github.tabssh.ui.utils.ContainerText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.tabssh.utils.logging.Logger

/**
 * Manual "apply this pending update now" dialog — the UI-driven counterpart
 * to [ContainerUpdateCheckWorker][io.github.tabssh.background.ContainerUpdateCheckWorker]'s
 * unattended `autoRecreateOnUpdate` path, which was previously the only
 * caller of [UpdateApplier]. Confirms once (the container restarts), then
 * runs the pull-and-recreate plan live with a step-by-step status line.
 */
object UpdateApplyDialog {

    /** Status/error text is one line in a small dialog. */
    private const val MAX_STATUS = 200
    private const val MAX_LIVE_LINES = 400

    private fun stepLabel(context: Context, step: RecreateStep): String = when (step) {
        RecreateStep.PULL_IMAGE -> context.getString(R.string.container_update_step_pull_image)
        RecreateStep.STOP_OLD -> context.getString(R.string.container_update_step_stop_old)
        RecreateStep.RENAME_OLD -> context.getString(R.string.container_update_step_rename_old)
        RecreateStep.CREATE_NEW -> context.getString(R.string.container_update_step_create_new)
        RecreateStep.VERIFY_NEW -> context.getString(R.string.container_update_step_verify_new)
        RecreateStep.REMOVE_OLD -> context.getString(R.string.container_update_step_remove_old)
        RecreateStep.ROLLBACK -> context.getString(R.string.container_update_step_rollback)
    }

    /**
     * Confirm, then show live progress while [UpdateApplier.apply] runs.
     * [onDone] fires once, after the dialog's own transport call resolves
     * (success or failure) — the caller refreshes its list either way so
     * the pending-update badge always reflects the outcome.
     */
    fun show(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        policyDao: ContainerAutoUpdatePolicyDao,
        policyId: Long,
        transport: ContainerTransport,
        containerName: String,
        onDone: () -> Unit
    ) {
        val safeName = ContainerText.display(containerName)
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.container_update_apply_title, safeName))
            .setMessage(context.getString(R.string.container_update_apply_confirm, safeName))
            .setPositiveButton(R.string.container_update_apply_action) { _, _ ->
                showProgress(context, lifecycleOwner, policyDao, policyId, transport, onDone)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showProgress(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        policyDao: ContainerAutoUpdatePolicyDao,
        policyId: Long,
        transport: ContainerTransport,
        onDone: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_update_apply, null)
        val textStep = view.findViewById<TextView>(R.id.text_step)
        val textStatus = view.findViewById<TextView>(R.id.text_status)
        val progressBar = view.findViewById<android.widget.ProgressBar>(R.id.progress_bar)
        val textOutput = view.findViewById<TextView>(R.id.text_output)
        val scrollOutput = view.findViewById<View>(R.id.scroll_output)

        var applyJob: Job? = null
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.container_update_apply_action)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                if (applyJob?.isActive == true) {
                    Logger.w("UpdateApplyDialog", "policy $policyId: dialog dismissed while the update was still running; cancelling")
                }
                applyJob?.cancel()
                onDone()
            }
            .create()

        Logger.event("UpdateApplyDialog", "policy $policyId: update started by the user")

        // Live output: command lines as they arrive, and one line per image layer that is updated in
        // place as the pull advances, so the dialog scrolls like a terminal instead of sitting still.
        val live = ArrayList<String>()
        val layerLine = HashMap<String, Int>()
        fun showLive() {
            textOutput.text = ContainerText.block(live.takeLast(MAX_LIVE_LINES).joinToString("\n"))
            scrollOutput.visibility = View.VISIBLE
            scrollOutput.post { (scrollOutput as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN) }
        }
        applyJob = lifecycleOwner.lifecycleScope.launch {
            try {
                UpdateApplier(policyDao).apply(policyId, transport).collect { event ->
                    when (event) {
                        is UpdateApplier.ApplyEvent.StepStarted ->
                            textStep.text = stepLabel(context, event.step)
                        is UpdateApplier.ApplyEvent.Output -> {
                            live += event.line
                            showLive()
                        }
                        is UpdateApplier.ApplyEvent.PullProgress -> {
                            val e = event.event
                            val status = ContainerText.display(e.status, MAX_STATUS)
                            textStatus.visibility = View.VISIBLE
                            textStatus.text = status
                            val bytes = if (e.totalBytes > 0) {
                                " " + android.text.format.Formatter.formatShortFileSize(context, e.currentBytes) +
                                    " / " + android.text.format.Formatter.formatShortFileSize(context, e.totalBytes)
                            } else ""
                            val line = (e.layerId?.let { "$it: " } ?: "") + status + bytes
                            val key = e.layerId
                            val at = key?.let { layerLine[it] }
                            if (at != null) {
                                live[at] = line
                            } else {
                                if (key != null) layerLine[key] = live.size
                                live += line
                            }
                            showLive()
                        }
                        is UpdateApplier.ApplyEvent.Completed -> {
                            textStep.setText(R.string.container_update_apply_done)
                            textStatus.visibility = View.GONE
                            showOutput(scrollOutput, textOutput, event.output)
                            finishProgress(dialog, progressBar)
                            verifyApplied(context, policyDao, policyId, transport, textStatus)
                        }
                        is UpdateApplier.ApplyEvent.Failed -> {
                            textStatus.visibility = View.VISIBLE
                            val stepName = stepLabel(context, event.step)
                            val message = ContainerText.display(event.message, MAX_STATUS)
                            textStatus.text = if (event.rolledBack) {
                                context.getString(
                                    R.string.container_update_apply_failed_rolled_back, stepName, message
                                )
                            } else {
                                context.getString(R.string.container_update_apply_failed, stepName, message)
                            }
                            showOutput(scrollOutput, textOutput, event.output)
                            finishProgress(dialog, progressBar)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("UpdateApplyDialog", "policy $policyId: update flow threw", e)
                textStatus.visibility = View.VISIBLE
                textStatus.text = context.getString(
                    R.string.container_update_apply_failed,
                    "",
                    ContainerText.display(e.message, MAX_STATUS)
                )
                finishProgress(dialog, progressBar)
            }
        }

        dialog.show()
    }

    /**
     * Re-checks the container against its registry after an apply and says so: verified current, or
     * still older (the pull did not bring a newer image, e.g. a pinned tag), so a "complete" update
     * that changed nothing is never silent. A failed check is reported, not guessed at.
     */
    private suspend fun verifyApplied(
        context: Context,
        policyDao: ContainerAutoUpdatePolicyDao,
        policyId: Long,
        transport: ContainerTransport,
        textStatus: TextView
    ) {
        try {
            val policy = policyDao.getById(policyId) ?: return
            if (policy.scope == "stack") return
            val db = (context.applicationContext as io.github.tabssh.TabSSHApplication).database
            val checker = io.github.tabssh.containers.registry.UpdateChecker(
                policyDao, db.registryCredentialDao(), io.github.tabssh.containers.registry.RegistryClient()
            ) { id -> io.github.tabssh.crypto.storage.RegistryCredentialStore.retrieve(context.applicationContext, id) }
            val result = checker.checkOne(policy, transport)
            textStatus.visibility = View.VISIBLE
            textStatus.setText(
                when (result.status) {
                    io.github.tabssh.containers.registry.UpdateChecker.Status.UPDATE_AVAILABLE ->
                        R.string.container_update_verify_still_older
                    io.github.tabssh.containers.registry.UpdateChecker.Status.ERROR ->
                        R.string.container_update_verify_error
                    else -> R.string.container_update_verify_ok
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("UpdateApplyDialog", "policy $policyId: post-apply verification failed: ${e.message}")
        }
    }

    /** Shows the complete command output (sanitised, nothing truncated short of the block cap). */
    private fun showOutput(scroll: View, text: TextView, output: String) {
        val clean = ContainerText.block(output).trim()
        if (clean.isEmpty()) return
        text.text = clean
        scroll.visibility = View.VISIBLE
    }

    /** The run is over: stop the progress animation and turn Cancel into Close. */
    private fun finishProgress(dialog: androidx.appcompat.app.AlertDialog, progressBar: android.widget.ProgressBar?) {
        progressBar?.visibility = View.GONE
        dialog.setCancelable(true)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setText(R.string.close)
    }
}
