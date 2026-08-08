package io.github.tabssh.docker.registry

import io.github.tabssh.docker.runconfig.RecreateContainer
import io.github.tabssh.docker.runconfig.RecreateContainer.RecreateStep
import io.github.tabssh.docker.runconfig.RunConfigException
import io.github.tabssh.docker.transport.ContainerAction
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.DockerTransport
import io.github.tabssh.docker.transport.PullProgressEvent
import io.github.tabssh.storage.database.dao.ContainerAutoUpdatePolicyDao
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * Execution half of the pull-and-recreate flow (PLAN.AI.md step 32) — walks
 * [RecreateContainer.PLAN] against a [DockerTransport]:
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
    fun apply(policyId: Long, transport: DockerTransport): Flow<ApplyEvent> = flow {
        val policy = policyDao.getById(policyId)
        if (policy == null) {
            emit(ApplyEvent.Failed(RecreateStep.PULL_IMAGE, "Policy $policyId no longer exists", false))
            return@flow
        }
        val name = policy.containerNameOrStackName
        val oldName = RecreateContainer.oldName(name)

        // Snapshot the running container before touching anything.
        val inspect = when (val r = transport.inspectContainer(name)) {
            is DockerResult.Success -> UpdateChecker.normalizeInspect(r.value)
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
        if (pullError != null) {
            return@flow emit(ApplyEvent.Failed(RecreateStep.PULL_IMAGE, pullError!!, false))
        }

        // STOP_OLD — recoverable by simply restarting the old container.
        emit(ApplyEvent.StepStarted(RecreateStep.STOP_OLD))
        when (val r = transport.containerAction(name, ContainerAction.STOP)) {
            is DockerResult.Success -> Unit
            else -> return@flow emit(ApplyEvent.Failed(RecreateStep.STOP_OLD, failureMessage(r), false))
        }

        // RENAME_OLD — from here on, failure means full rollback.
        emit(ApplyEvent.StepStarted(RecreateStep.RENAME_OLD))
        when (val r = transport.renameContainer(name, oldName)) {
            is DockerResult.Success -> Unit
            else -> {
                // The old container is merely stopped — restart it.
                val restarted = transport.containerAction(name, ContainerAction.START)
                return@flow emit(ApplyEvent.Failed(
                    RecreateStep.RENAME_OLD, failureMessage(r),
                    restarted is DockerResult.Success
                ))
            }
        }

        // CREATE_NEW — each tier consumes its half of the plan.
        emit(ApplyEvent.StepStarted(RecreateStep.CREATE_NEW))
        when (val r = transport.createAndStartContainer(name, createBody, runArgv)) {
            is DockerResult.Success -> Unit
            else -> return@flow emit(rollback(
                transport, RecreateStep.CREATE_NEW, failureMessage(r), name, oldName,
                removeNew = true
            ))
        }

        // VERIFY_NEW — healthcheck status when the image defines one,
        // still-running-after-window otherwise.
        emit(ApplyEvent.StepStarted(RecreateStep.VERIFY_NEW))
        val verifyError = verifyNew(transport, name)
        if (verifyError != null) {
            return@flow emit(rollback(
                transport, RecreateStep.VERIFY_NEW, verifyError, name, oldName, removeNew = true
            ))
        }

        // REMOVE_OLD — success path; a failed remove is logged, not fatal.
        emit(ApplyEvent.StepStarted(RecreateStep.REMOVE_OLD))
        when (val r = transport.removeContainer(oldName, force = true)) {
            is DockerResult.Success -> Unit
            else -> Logger.w(TAG, "could not remove $oldName: ${failureMessage(r)}")
        }

        policyDao.updatePendingUpdateDigest(policyId, null)
        emit(ApplyEvent.Completed(name))
    }

    /**
     * Watch the replacement container until it proves healthy or fails.
     * Returns null on success, a failure message otherwise.
     */
    private suspend fun verifyNew(transport: DockerTransport, name: String): String? {
        val start = System.currentTimeMillis()
        var sawHealthcheck = false
        while (true) {
            delay(VERIFY_POLL_MS)
            val inspect = when (val r = transport.inspectContainer(name)) {
                is DockerResult.Success -> UpdateChecker.normalizeInspect(r.value)
                    ?: return "Unparseable inspect output during verify"
                else -> return "Replacement container disappeared: ${failureMessage(r)}"
            }
            val state = inspect.optJSONObject("State")
            if (state == null || !state.optBoolean("Running", false)) {
                return "Replacement container is not running"
            }
            val health = state.optJSONObject("Health")?.optString("Status").orEmpty()
            val elapsed = System.currentTimeMillis() - start
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
            }
        }
    }

    /**
     * Recovery for failures after RENAME_OLD: remove the (possibly created)
     * new container, rename `{name}_old` back, restart it.
     */
    private suspend fun rollback(
        transport: DockerTransport,
        step: RecreateStep,
        message: String,
        name: String,
        oldName: String,
        removeNew: Boolean
    ): ApplyEvent.Failed {
        Logger.w(TAG, "recreate of $name failed at $step ($message) — rolling back")
        var ok = true
        if (removeNew) {
            val removed = transport.removeContainer(name, force = true)
            // NotFound is fine — create may never have happened.
            if (removed !is DockerResult.Success && removed !is DockerResult.NotFound) ok = false
        }
        if (transport.renameContainer(oldName, name) !is DockerResult.Success) ok = false
        if (transport.containerAction(name, ContainerAction.START) !is DockerResult.Success) ok = false
        return ApplyEvent.Failed(step, message, ok)
    }

    /** Pre-plan failure — nothing was touched, so nothing to roll back. */
    private fun failNoRollback(message: String): ApplyEvent.Failed =
        ApplyEvent.Failed(RecreateStep.PULL_IMAGE, message, false)

    /** Human-readable message for any DockerResult failure. */
    private fun failureMessage(result: DockerResult<*>): String = when (result) {
        is DockerResult.Success -> "unexpected success"
        is DockerResult.PermissionDenied -> result.message
        is DockerResult.NotFound -> result.message
        is DockerResult.TransportUnavailable -> result.message
        is DockerResult.Error -> result.message
    }
}
