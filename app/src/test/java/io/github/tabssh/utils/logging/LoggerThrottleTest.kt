package io.github.tabssh.utils.logging

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Call-site rate limiting (Logger.dThrottled / Logger.wThrottled) — the
 * per-(tag, key) gate that stops per-frame and per-read call sites from
 * flooding the writer thread. Drives Logger's injectable clock so the test
 * never sleeps.
 */
class LoggerThrottleTest {

    private var clock = 0L

    @Before
    fun installClock() {
        Logger.throttleClock = { clock }
    }

    @After
    fun restoreClock() {
        Logger.throttleClock = { System.currentTimeMillis() }
    }

    @Test
    fun `first call for a key always emits`() {
        assertTrue(Logger.shouldEmitThrottled("Tag", "firstCall", 500))
    }

    @Test
    fun `repeat inside the interval is suppressed`() {
        assertTrue(Logger.shouldEmitThrottled("Tag", "inside", 500))
        clock += 499
        assertFalse(Logger.shouldEmitThrottled("Tag", "inside", 500))
    }

    @Test
    fun `call exactly at the interval boundary emits`() {
        assertTrue(Logger.shouldEmitThrottled("Tag", "boundary", 500))
        clock += 500
        assertTrue(Logger.shouldEmitThrottled("Tag", "boundary", 500))
    }

    @Test
    fun `a suppressed call does not extend the window`() {
        assertTrue(Logger.shouldEmitThrottled("Tag", "noExtend", 500))
        clock += 400
        assertFalse(Logger.shouldEmitThrottled("Tag", "noExtend", 500))
        clock += 100
        assertTrue(Logger.shouldEmitThrottled("Tag", "noExtend", 500))
    }

    @Test
    fun `keys are throttled independently`() {
        assertTrue(Logger.shouldEmitThrottled("Tag", "keyA", 500))
        assertTrue(Logger.shouldEmitThrottled("Tag", "keyB", 500))
        clock += 100
        assertFalse(Logger.shouldEmitThrottled("Tag", "keyA", 500))
        assertFalse(Logger.shouldEmitThrottled("Tag", "keyB", 500))
    }

    @Test
    fun `the same key under different tags does not share a window`() {
        assertTrue(Logger.shouldEmitThrottled("TagOne", "shared", 500))
        assertTrue(Logger.shouldEmitThrottled("TagTwo", "shared", 500))
    }

    @Test
    fun `levels sharing a key share one window`() {
        // wThrottled and dThrottled both route through shouldEmitThrottled, so
        // a call site must not be able to emit twice by changing level.
        assertTrue(Logger.shouldEmitThrottled("Tag", "sharedLevel", 500))
        clock += 100
        assertFalse(Logger.shouldEmitThrottled("Tag", "sharedLevel", 500))
    }
}
