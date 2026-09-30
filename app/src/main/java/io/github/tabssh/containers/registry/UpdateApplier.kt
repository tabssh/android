package io.github.tabssh.containers.registry

import io.github.tabssh.containers.runconfig.RecreateContainer
import io.github.tabssh.containers.runconfig.RecreateContainer.RecreateStep
import io.github.tabssh.containers.runconfig.RunConfigException
import io.github.tabssh.containers.transport.ContainerAction
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerTransport
import io.github.tabssh.containers.transport.PullProgressEvent
import io.github.tabssh.storage.database.dao.ContainerAutoUpdatePolicyDao
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject

/**
 * Execution half of the pull-and-recreate flow — walks
 * [RecreateContainer.PLAN] against a [ContainerTransport]:
 *
 * pull → stop → rename to `{name}_old` → create+start under the original
 * name (API tier: verbatim create body; CLI tier: `docker run` argv) →
 * health-verify → remove the old container. Any failure after the rename
 * rolls back: remove the new container, rename the old one back, restart it.
 *
 * Progress is a cold [Flow] of [ApplyEvent]s so callers (UI dialog, the
 * background worker) can render step + pull progress live. On success the
 * policy's `pendingUpdateDigest` is cleared through the DAO.
 */
class UpdateApplier(
    private val policyDao: ContainerAutoUpdatePolicyDao
) {

    /** One progress emission of an apply run. */
    sealed class ApplyEvent {
        /** A plan step began. */
        data class StepStarted(val step: RecreateStep) : ApplyEvent()

        /** A pull progress line/event during [RecreateStep.PULL_IMAGE]. */
        data class PullProgress(val event: PullProgressEvent) : ApplyEvent()

        /** The whole plan succeeded; the old container is gone. */
        data class Completed(val containerName: String) : ApplyEvent()

        /** The plan failed at [step]; [rolledBack] reports recovery success. */
        data class Failed(
            val step: RecreateStep,
            val message: String,
            val rolledBack: Boolean
        ) : ApplyEvent()
    }

    private companion object {
        private const val TAG = "UpdateApplier"

        /** Poll cadence while verifying the replacement container. */
        private const val VERIFY_POLL_MS = 2_000L

        /** Upper bound for a HEALTHCHECK to reach a terminal state. */
        private const val HEALTH_WINDOW_MS = 90_000L
    }

    /** Terminal outcome of one verify poll. */
    private enum class Verify { OK, FAILED, PENDING }

    /**
     * Apply the pending update for [policyId]: recreate the container named
     * in the policy on the current registry image. Runs the plan when
     * collected; safe to collect exactly once.
     */
    fun apply(policyId: Long, transport: ContainerTransport): Flow<ApplyEvent> = flow {
        val policy = policyDao.getById(policyId)
        if (policy == null) {
            emit(ApplyEvent.Failed(RecreateStep.PULL_IMAGE, "Policy $policyId no longer exists", false))
            return@flow
        }
        val name = policy.containerNameOrStackName
        val oldName = RecreateContainer.oldName(name)
        Logger.i(TAG, "applying pending update for policy ${policy.id} ($name)")

        // Snapshot the running container before touching anything.
        val inspect = when (val r = transport.inspectContainer(name)) {
            is ContainerResult.Success -> UpdateChecker.normalizeInspect(r.value)
                ?: return@flow emit(failNoRollback("Unparseable inspect output"))
            else -> return@flow emit(failNoRollback(failureMessage(r)))
        }
        val image = inspect.optJSONObject("Config")?.optString("Image").orEmpty()
        if (image.isEmpty()) {
            return@flow emit(failNoRollback("Container $name has no Config.Image"))
        }
        val createBody: JSONObject
        val runArgv: List<String>
        try {
            createBody = RecreateContainer.createBodyFromInspect(inspect, image)
            runArgv = RecreateContainer.runArgvFromInspect(inspect, image)
        } catch (e: RunConfigException) {
            return@flow emit(failNoRollback(e.message ?: "Recreate plan failed"))
        }

        // PULL_IMAGE — before any disruptive step, so a pull failure is free.
        emit(ApplyEvent.StepStarted(RecreateStep.PULL_IMAGE))
        var pullError: String? = null
        transport.pullImage(image).collect { event ->
            emit(ApplyEvent.PullProgress(event))
            if (event.error != null && pullError == null) pullError = event.error
        }
        val capturedPullError = pullError
        if (capturedPullError != null) {
            return@flow emit(ApplyEvent.Failed(RecreateStep.PULL_IMAGE, capturedPullError, false))
        }

        var currentStep = RecreateStep.STOP_OLD
        var stopAttempted = false
        var renameAttempted = false
        var createAttempted = false
        try {
            // STOP_OLD — recoverable by simply restarting the old container.
            emit(ApplyEvent.StepStarted(RecreateStep.STOP_OLD))
            stopAttempted = true
            when (val r = transport.containerAction(name, ContainerAction.STOP)) {
                is ContainerResult.Success -> Unit
                else -> {
                    stopAttempted = false
                    return@flow emit(ApplyEvent.Failed(RecreateStep.STOP_OLD, failureMessage(r), false))
                }
            }

            // RENAME_OLD — from here on, failure means full rollback.
            currentStep = RecreateStep.RENAME_OLD
            emit(ApplyEvent.StepStarted(currentStep))
            renameAttempted = true
            when (val r = transport.renameContainer(name, oldName)) {
                is ContainerResult.Success -> Unit
                else -> {
                    renameAttempted = false
                    val restarted = transport.containerAction(name, ContainerAction.START)
                    stopAttempted = false
                    return@flow emit(ApplyEvent.Failed(
                        RecreateStep.RENAME_OLD, failureMessage(r),
                        restarted is ContainerResult.Success
                    ))
                }
            }

            // CREATE_NEW — each tier consumes its half of the plan.
            currentStep = RecreateStep.CREATE_NEW
            emit(ApplyEvent.StepStarted(currentStep))
            createAttempted = true
            when (val r = transport.createAndStartContainer(name, createBody, runArgv)) {
                is ContainerResult.Success -> Unit
                else -> {
                    val event = rollback(
                        transport, currentStep, failureMessage(r), name, oldName,
                        removeNew = true
                    )
                    stopAttempted = false
                    renameAttempted = false
                    createAttempted = false
                    return@flow emit(event)
                }
            }

            // VERIFY_NEW — healthcheck status when the image defines one,
            // still-running-after-window otherwise.
            currentStep = RecreateStep.VERIFY_NEW
            emit(ApplyEvent.StepStarted(currentStep))
            val verifyError = verifyNew(transport, name)
            if (verifyError != null) {
                val event = rollback(
                    transport, currentStep, verifyError, name, oldName, removeNew = true
                )
                stopAttempted = false
                renameAttempted = false
                createAttempted = false
                return@flow emit(event)
            }

            // REMOVE_OLD — success path; a failed remove is logged, not fatal.
            stopAttempted = false
            renameAttempted = false
            createAttempted = false
            currentStep = RecreateStep.REMOVE_OLD
            emit(ApplyEvent.StepStarted(currentStep))
            when (val r = transport.removeContainer(oldName, force = true)) {
                is ContainerResult.Success -> Unit
                else -> Logger.w(TAG, "could not remove $oldName: ${failureMessage(r)}")
            }
            stopAttempted = false
            renameAttempted = false
            createAttempted = false
        } catch (e: CancellationException) {
            if (stopAttempted) {
                withContext(NonCancellable) {
                    try {
                        if (renameAttempted) {
                            rollback(
                                transport, currentStep, "Update cancelled", name, oldName,
                                removeNew = createAttempted
                            )
                        } else {
                            transport.containerAction(name, ContainerAction.START)
                        }
                    } catch (cleanupError: Exception) {
                        Logger.e(TAG, "Failed to recover after update cancellation", cleanupError)
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            val rolledBack = if (stopAttempted) {
                withContext(NonCancellable) {
                    try {
                        if (renameAttempted) {
                            rollback(
                                transport, currentStep, e.message ?: "Update failed", name, oldName,
                                removeNew = createAttempted
                            ).rolledBack
                        } else {
                            transport.containerAction(name, ContainerAction.START) is ContainerResult.Success
                        }
                    } catch (cleanupError: Exception) {
                        Logger.e(TAG, "Failed to recover after update failure", cleanupError)
                        false
                    }
                }
            } else {
                false
            }
            emit(ApplyEvent.Failed(currentStep, e.message ?: "Update failed", rolledBack))
            return@flow
        }

        policyDao.updatePendingUpdateDigest(policyId, null)
        emit(ApplyEvent.Completed(name))
    }

    /**
     * Watch the replacement container until it proves healthy or fails.
     * Returns null on success, a failure message otherwise.
     */
    private suspend fun verifyNew(transport: ContainerTransport, name: String): String? {
        val start = System.nanoTime()
        var sawHealthcheck = false
        while (true) {
            delay(VERIFY_POLL_MS)
            val inspect = when (val r = transport.inspectContainer(name)) {
                is ContainerResult.Success -> UpdateChecker.normalizeInspect(r.value)
                    ?: return "Unparseable inspect output during verify"
                else -> return "Replacement container disappeared: ${failureMessage(r)}"
            }
            val state = inspect.optJSONObject("State")
            if (state == null || !state.optBoolean("Running", false)) {
                return "Replacement container is not running"
            }
            val health = state.optJSONObject("Health")?.optString("Status").orEmpty()
            val elapsed = (System.nanoTime() - start) / 1_000_000L
            when {
                health == "healthy" -> return null
                health == "unhealthy" -> return "Replacement container reported unhealthy"
                health.isNotEmpty() && health != "none" -> {
                    // "starting" — keep waiting inside the health window.
                    sawHealthcheck = true
                    if (elapsed >= HEALTH_WINDOW_MS) {
                        return "Healthcheck did not pass within ${HEALTH_WINDOW_MS / 1000}s"
                    }
                }
                // No HEALTHCHECK: sustained uptime is the success signal.
                !sawHealthcheck && elapsed >= RecreateContainer.VERIFY_WINDOW_MS -> return null
                // Terminal guard: a container whose Health block appears and
                // then vanishes (image swapped, healthcheck disabled mid-run)
                // matches no branch above, so without this the loop would
                // never end.
                elapsed >= HEALTH_WINDOW_MS ->
                    return "Replacement container did not verify within " +
                        "${HEALTH_WINDOW_MS / 1000}s"
            }
        }
    }

    /**
     * Recovery for failures after RENAME_OLD: remove the (possibly created)
     * new container, rename `{name}_old` back, restart it.
     */
    private suspend fun rollback(
        transport: ContainerTransport,
        step: RecreateStep,
        message: String,
        name: String,
        oldName: String,
        removeNew: Boolean
    ): ApplyEvent.Failed = withContext(NonCancellable) {
        Logger.w(TAG, "recreate of $name failed at $step ($message) — rolling back")
        var ok = true
        if (removeNew) {
            try {
                val removed = transport.removeContainer(name, force = true)
                // NotFound is fine — create may never have happened.
                if (removed !is ContainerResult.Success && removed !is ContainerResult.NotFound) ok = false
            } catch (e: Exception) {
                ok = false
                Logger.e(TAG, "Could not remove replacement container $name", e)
            }
        }
        try {
            if (transport.renameContainer(oldName, name) !is ContainerResult.Success) ok = false
        } catch (e: Exception) {
            ok = false
            Logger.e(TAG, "Could not restore container name $name", e)
        }
        try {
            if (transport.containerAction(name, ContainerAction.START) !is ContainerResult.Success) ok = false
        } catch (e: Exception) {
            ok = false
            Logger.e(TAG, "Could not restart restored container $name", e)
        }
        ApplyEvent.Failed(step, message, ok)
    }

    /** Pre-plan failure — nothing was touched, so nothing to roll back. */
    private fun failNoRollback(message: String): ApplyEvent.Failed =
        ApplyEvent.Failed(RecreateStep.PULL_IMAGE, message, false)

    /** Human-readable message for any ContainerResult failure. */
    private fun failureMessage(result: ContainerResult<*>): String = when (result) {
        is ContainerResult.Success -> "unexpected success"
        is ContainerResult.PermissionDenied -> result.message
        is ContainerResult.NotFound -> result.message
        is ContainerResult.EngineNotInstalled -> result.message
        is ContainerResult.TransportUnavailable -> result.message
        is ContainerResult.Error -> result.message
    }
}
