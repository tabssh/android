package io.github.tabssh.containers.transport

import io.github.tabssh.storage.database.entities.ContainerHost
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A failed pull must reach the update flow as an error event, and a failed request must leave a
 * one-line, bounded summary for the log; an empty or silently ended stream used to read as success.
 */
class PullFailureReportingTest {

    private val host = ContainerHost(name = "h", engine = "docker")

    private fun transportStreaming(vararg lines: String): CliExecTransport {
        val runner: SshExecRunner = mock()
        whenever(runner.stream(any())).thenReturn(flowOf(*lines))
        return CliExecTransport(host, runner)
    }

    @Test
    fun `a pull with a non-zero exit ends with an error event carrying the output`() = runBlocking {
        val events = transportStreaming(
            "Using default tag: latest",
            "Error response from daemon: pull access denied for x/y",
            "__tabssh_pull_exit__:1"
        ).pullImage("x/y").toList()
        val last = events.last()
        assertNotNull(last.error)
        assertTrue(last.error.orEmpty().contains("exited 1"))
        assertTrue(last.error.orEmpty().contains("pull access denied"))
    }

    @Test
    fun `a pull with exit zero reports no error`() = runBlocking {
        val events = transportStreaming(
            "latest: Pulling from x/y",
            "Status: Image is up to date",
            "__tabssh_pull_exit__:0"
        ).pullImage("x/y").toList()
        assertTrue(events.all { it.error == null })
        assertEquals("pull finished", events.last().status)
    }

    @Test
    fun `a failed result summary names the kind, message and a bounded detail`() {
        val summary = ContainerResult.Error("Failed to create container web (HTTP 409)", "x".repeat(900)).failureSummary()
        assertTrue(summary.startsWith("error: Failed to create container web (HTTP 409) | "))
        assertTrue(summary.length < 400)
    }

    @Test
    fun `a failure without detail has no trailing separator and success is not a failure`() {
        assertEquals("not found: no such container", ContainerResult.NotFound("no such container").failureSummary())
        assertEquals("success", ContainerResult.Success("ok").failureSummary())
        assertNull(ContainerResult.Success("ok").detailOrNull())
    }
}
