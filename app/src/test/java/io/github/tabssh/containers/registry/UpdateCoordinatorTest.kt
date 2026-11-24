package io.github.tabssh.containers.registry

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCoordinatorTest {

    @Test
    fun `work on one host never overlaps`() = runTest {
        val events = mutableListOf<String>()
        val first = async {
            UpdateCoordinator.withHost(7L) {
                events += "a-start"
                delay(100)
                events += "a-end"
            }
        }
        val second = async {
            UpdateCoordinator.withHost(7L) {
                events += "b-start"
                events += "b-end"
            }
        }
        first.await()
        second.await()
        assertEquals(listOf("a-start", "a-end", "b-start", "b-end"), events)
    }

    @Test
    fun `different hosts do not wait on each other`() = runTest {
        val events = mutableListOf<String>()
        val slow = async {
            UpdateCoordinator.withHost(1L) {
                events += "slow-start"
                delay(100)
                events += "slow-end"
            }
        }
        val other = async {
            UpdateCoordinator.withHost(2L) { events += "other" }
        }
        other.await()
        slow.await()
        assertTrue(events.indexOf("other") < events.indexOf("slow-end"))
    }
}
