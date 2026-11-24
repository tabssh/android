package io.github.tabssh.containers.registry

import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerTransport
import io.github.tabssh.storage.database.dao.ContainerAutoUpdatePolicyDao
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Applies every pending update in a list, one unit after another, and reports what happens as it
 * happens. A unit is a standalone container, or a whole compose project: compose recreates every
 * container of a project in one command, so the project is updated once however many of its
 * containers are listed, instead of once per container.
 *
 * An update that finishes with exit 0 is an updated image: the container is marked updated and leaves the
 * list. Nothing is re-checked afterwards to second-guess it.
 */
class UpdateAllRunner(
    private val policyDao: ContainerAutoUpdatePolicyDao,
    private val hostName: suspend (Long) -> String,
    private val transportFor: suspend (Long) -> ContainerTransport?
) {

    /** What the run reports; every item is meant to be shown to the user as it arrives. */
    sealed class Event {
        /** Starting a host: its name and how many containers are to be updated on it. */
        data class HostStarted(val host: String, val containers: Int) : Event()

        /** Starting unit [index] of [total]; [label] is a container or a compose project. */
        data class UnitStarted(val label: String, val index: Int, val total: Int) : Event()

        /** One line of live output (a command's output, a pull step, a status change). */
        data class Line(val text: String) : Event()

        /** A unit finished: how many containers were updated and how many failed. */
        data class UnitDone(
            val label: String,
            val updated: Int,
            val failed: Int,
            val detail: String?
        ) : Event()

        /** The run is over. */
        data class Finished(val updated: Int, val failed: Int) : Event()
    }

    private class UpdateUnit(val label: String, val members: List<ContainerAutoUpdatePolicy>)

    fun run(targets: List<ContainerAutoUpdatePolicy>): Flow<Event> = flow {
        var updated = 0
        var failed = 0
        val byHost = targets.groupBy { it.containerHostId }
        val unitsByHost = LinkedHashMap<Long, List<UpdateUnit>>()
        val transports = HashMap<Long, ContainerTransport?>()
        // Units are worked out first, so the progress count is the real total from the start.
        for ((hostId, members) in byHost) {
            val transport = transportFor(hostId)
            transports[hostId] = transport
            unitsByHost[hostId] = if (transport == null) {
                members.map { UpdateUnit(ContainerLabel.of(it), listOf(it)) }
            } else {
                unitsOf(members, transport)
            }
        }
        val total = unitsByHost.values.sumOf { it.size }
        var index = 0
        for ((hostId, units) in unitsByHost) {
            val host = hostName(hostId)
            emit(Event.HostStarted(host, units.sumOf { it.members.size }))
            val transport = transports[hostId]
            if (transport == null) {
                for (unit in units) {
                    index++
                    failed += unit.members.size
                    emit(Event.UnitStarted(unit.label, index, total))
                    emit(Event.UnitDone(unit.label, 0, unit.members.size, "could not connect to $host"))
                }
                continue
            }
            for (unit in units) {
                index++
                emit(Event.UnitStarted(unit.label, index, total))
                val done = applyUnit(unit, transport) { emit(Event.Line(it)) }
                updated += done.updated
                failed += done.failed
                emit(done)
            }
        }
        emit(Event.Finished(updated, failed))
    }

    /** Groups the listed policies into units using the host's own container list for compose labels. */
    private suspend fun unitsOf(
        members: List<ContainerAutoUpdatePolicy>,
        transport: ContainerTransport
    ): List<UpdateUnit> {
        val listed = (transport.listContainers(all = true) as? ContainerResult.Success)?.value.orEmpty()
        val projectOf = HashMap<String, String>()
        for (c in listed) {
            val project = c.labels[UpdateApplier.COMPOSE_PROJECT_LABEL]?.takeIf { it.isNotEmpty() } ?: continue
            c.names.forEach { projectOf[it.trimStart('/')] = project }
        }
        return members
            .groupBy { projectOf[it.containerNameOrStackName] ?: "container:${it.containerNameOrStackName}" }
            .map { (key, group) ->
                val label = if (key.startsWith("container:")) ContainerLabel.of(group.first()) else key
                UpdateUnit(label, group)
            }
    }

    /** Applies one unit through its first member, then re-checks every member against the registry. */
    private suspend fun applyUnit(
        unit: UpdateUnit,
        transport: ContainerTransport,
        line: suspend (String) -> kotlin.Unit
    ): Event.UnitDone {
        val applier = UpdateApplier(policyDao)
        val lead = unit.members.first()
        var completed = false
        var failure: UpdateApplier.ApplyEvent.Failed? = null
        try {
            applier.apply(lead.id, transport).collect { event ->
                when (event) {
                    is UpdateApplier.ApplyEvent.StepStarted -> line("-> ${event.step.name.lowercase().replace('_', ' ')}")
                    is UpdateApplier.ApplyEvent.Output -> line(event.line)
                    is UpdateApplier.ApplyEvent.PullProgress -> pullLine(event.event)?.let { line(it) }
                    is UpdateApplier.ApplyEvent.Completed -> completed = true
                    is UpdateApplier.ApplyEvent.Failed -> {
                        failure = event
                        if (event.output.isNotBlank()) event.output.lines().takeLast(MAX_FAILURE_LINES).forEach { line(it) }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "update of ${unit.label} threw", e)
            return Event.UnitDone(unit.label, 0, unit.members.size, e.message ?: "update failed")
        }
        val f = failure
        if (!completed) {
            val detail = f?.let { "failed at ${it.step.name.lowercase().replace('_', ' ')}: ${it.message}" + if (it.rolledBack) " (rolled back)" else "" }
            return Event.UnitDone(unit.label, 0, unit.members.size, detail ?: "update failed")
        }
        // Exit 0 means the image was updated. Every container of the unit leaves the list (compose
        // updates the whole project, so its siblings are done too).
        for (member in unit.members) {
            policyDao.clearPendingForContainer(member.containerHostId, member.containerNameOrStackName)
        }
        return Event.UnitDone(unit.label, unit.members.size, 0, null)
    }

    /** A line worth showing for a pull event: results and layer completions, not every progress tick. */
    private fun pullLine(e: io.github.tabssh.containers.transport.PullProgressEvent): String? {
        val status = e.status
        return when {
            e.error != null -> "pull error: ${e.error}"
            status.startsWith("Pull complete") || status.startsWith("Already exists") ->
                (e.layerId?.let { "$it: " } ?: "") + status
            status.startsWith("Digest:") || status.startsWith("Status:") -> status
            else -> null
        }
    }

    private object ContainerLabel {
        fun of(policy: ContainerAutoUpdatePolicy): String = policy.containerNameOrStackName
    }

    private companion object {
        const val TAG = "UpdateAllRunner"
        const val MAX_FAILURE_LINES = 12
    }
}
