package io.github.tabssh.containers.registry

import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerTransport
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A stack directory counts as gone only on a definite "no such directory"; any other failure keeps it. */
class StackPrunerTest {

    private fun transportReturning(result: ContainerResult<List<io.github.tabssh.containers.transport.RemoteDirEntry>>): ContainerTransport {
        val transport: ContainerTransport = mock()
        runBlocking { whenever(transport.listRemoteDir("/srv/me/compose/web")).thenReturn(result) }
        return transport
    }

    @Test
    fun `a missing directory is reported as gone`() = runBlocking {
        val transport = transportReturning(ContainerResult.NotFound("no such file or directory"))
        assertFalse(StackPruner.directoryExists(transport, "/srv/me/compose/web"))
    }

    @Test
    fun `an existing directory is kept`() = runBlocking {
        val transport = transportReturning(ContainerResult.Success(emptyList()))
        assertTrue(StackPruner.directoryExists(transport, "/srv/me/compose/web"))
    }

    @Test
    fun `a permission or transport failure never removes a stack`() = runBlocking {
        val denied = transportReturning(ContainerResult.PermissionDenied("denied"))
        val down = transportReturning(ContainerResult.TransportUnavailable("ssh gone"))
        val error = transportReturning(ContainerResult.Error("boom"))
        assertTrue(StackPruner.directoryExists(denied, "/srv/me/compose/web"))
        assertTrue(StackPruner.directoryExists(down, "/srv/me/compose/web"))
        assertTrue(StackPruner.directoryExists(error, "/srv/me/compose/web"))
    }
}
