package io.github.tabssh.utils.coroutines

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [SingleFlightLoader] cancels an in-flight load when a newer one
 * starts, so a slower stale result can never land after (and overwrite) a
 * faster fresh one — the DockerDashboardFragment/DockerPageFragment
 * `onSessionReady` re-entrancy race, where `sessionFlow` and `refreshFlow`
 * can both fire in quick succession.
 */
class SingleFlightLoaderTest {

    @Test
    fun `a superseded load cannot overwrite a newer one`() = runTest {
        val loader = SingleFlightLoader()
        var applied = ""

        // Started first but resolves slowest — simulates the stale
        // sessionFlow emission that would otherwise land last.
        loader.launchIn(this) {
            delay(1_000L)
            applied = "stale"
        }
        // Started second (before the first finishes) and resolves faster —
        // simulates the refreshFlow tick that should win.
        loader.launchIn(this) {
            delay(100L)
            applied = "fresh"
        }

        advanceUntilIdle()

        assertEquals("fresh", applied)
    }

    @Test
    fun `starting a new load cancels the previous job`() = runTest {
        val loader = SingleFlightLoader()

        val firstJob = loader.launchIn(this) {
            delay(Long.MAX_VALUE)
        }
        loader.launchIn(this) {
            delay(10L)
        }
        advanceUntilIdle()

        assertTrue(firstJob.isCancelled)
    }
}
