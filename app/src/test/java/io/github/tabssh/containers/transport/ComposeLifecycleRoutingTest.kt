package io.github.tabssh.containers.transport

import io.github.tabssh.storage.database.entities.ContainerHost
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose lifecycle routing in [RemoteExecOps]: composemgr is preferred for every stack verb when
 * installed, compose is the fallback, and stacks that are down stay listed from the base directory.
 */
class ComposeLifecycleRoutingTest {

    private val host = ContainerHost(
        name = "h", engine = "docker", composeBasePath = "/srv/me/compose"
    )

    private class Recorder(val withComposemgr: Boolean, val findOutput: String = "") {
        val commands = mutableListOf<String>()
        val runner: SshExecRunner = mock()

        suspend fun install() {
            whenever(runner.run(any(), any())).thenAnswer { call ->
                val cmd = call.getArgument<String>(0)
                commands += cmd
                when {
                    cmd.startsWith("command -v composemgr") ->
                        ExecResult("", "", if (withComposemgr) 0 else 1)
                    cmd.contains("compose version") -> ExecResult("v2", "", 0)
                    cmd.startsWith("find ") -> ExecResult(findOutput, "", 0)
                    cmd.contains(" ls ") -> ExecResult("[]", "", 0)
                    else -> ExecResult("ok", "", 0)
                }
            }
        }
    }

    private fun ops(rec: Recorder): RemoteExecOps {
        val ctx: EngineCliContext = mock()
        runBlocking { whenever(ctx.envPrefix()).thenReturn("") }
        return RemoteExecOps(rec.runner, host, ctx)
    }

    @Test
    fun `every lifecycle verb goes through composemgr when installed`() = runBlocking {
        val rec = Recorder(withComposemgr = true).also { it.install() }
        val ops = ops(rec)
        ops.composeUp("/srv/me/compose/web")
        ops.composeDown("/srv/me/compose/web")
        ops.composePull("/srv/me/compose/web")
        ops.composeRestart("/srv/me/compose/web")
        val used = rec.commands.filter { !it.startsWith("command -v") }
        assertEquals(
            listOf("up", "down", "pull", "restart").map {
                "composemgr --dir '/srv/me/compose/web' $it"
            },
            used
        )
    }

    @Test
    fun `without composemgr compose runs inside the stack directory`() = runBlocking {
        val rec = Recorder(withComposemgr = false).also { it.install() }
        ops(rec).composeUp("/srv/me/compose/web")
        assertTrue(rec.commands.any { it.contains("cd '/srv/me/compose/web' &&") && it.endsWith("up -d") })
        assertTrue(rec.commands.none { it.contains("composemgr --dir") })
    }

    @Test
    fun `a discovered project uses composemgr only when its directory carries its name`() = runBlocking {
        val rec = Recorder(withComposemgr = true).also { it.install() }
        val ops = ops(rec)
        ops.composeDownByProject("web", "/srv/me/compose/web/compose.yaml")
        ops.composeDownByProject("other", "/srv/me/compose/web/compose.yaml")
        assertTrue(rec.commands.any { it == "composemgr --dir '/srv/me/compose/web' down" })
        assertTrue(rec.commands.any { it.contains("-p 'other' down") })
    }

    @Test
    fun `a stack that is down is still listed from the compose base directory`() = runBlocking {
        val rec = Recorder(
            withComposemgr = true,
            findOutput = "/srv/me/compose/web/compose.yaml\n/srv/me/compose/db/docker-compose.yml\n"
        ).also { it.install() }
        val entries = (ops(rec).composeLs() as ContainerResult.Success).value
        assertEquals(listOf("db", "web"), entries.map { it.name })
        assertTrue(entries.all { it.status == "down" })
        assertEquals("/srv/me/compose/db/docker-compose.yml", entries.first().primaryConfigFile)
    }
}
