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

        var applyJob: Job? = null
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.container_update_apply_action)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                applyJob?.cancel()
                onDone()
            }
            .create()

        applyJob = lifecycleOwner.lifecycleScope.launch {
            try {
                UpdateApplier(policyDao).apply(policyId, transport).collect { event ->
                    when (event) {
                        is UpdateApplier.ApplyEvent.StepStarted ->
                            textStep.text = stepLabel(context, event.step)
                        is UpdateApplier.ApplyEvent.PullProgress -> {
                            val status = ContainerText.display(event.event.status, MAX_STATUS)
                            textStatus.visibility = View.VISIBLE
                            textStatus.text = status
                        }
                        is UpdateApplier.ApplyEvent.Completed -> {
                            textStep.setText(R.string.container_update_apply_done)
                            textStatus.visibility = View.GONE
                            dialog.setCancelable(true)
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
                            dialog.setCancelable(true)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                textStatus.visibility = View.VISIBLE
                textStatus.text = context.getString(
                    R.string.container_update_apply_failed,
                    "",
                    ContainerText.display(e.message, MAX_STATUS)
                )
                dialog.setCancelable(true)
            }
        }

        dialog.show()
    }
}
