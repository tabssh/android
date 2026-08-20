package io.github.tabssh.utils

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Covers the human-readable value rules in AI.md PART 7 § Human-Readable
 * Values: the spec's worked examples plus every unit boundary and the
 * singular/plural and trailing-`.0` behavior around them.
 *
 * Runs under Robolectric because the unit names come from R.plurals, which
 * needs a real resource table. A stock Application is forced so the real
 * TabSSHApplication (which reaches AndroidKeyStore on teardown) is never
 * instantiated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FormatTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun size(bytes: Long) = Format.size(context, bytes)

    private fun duration(millis: Long) = Format.duration(context, millis)

    // ── size: the spec's worked examples ─────────────────────────────────────

    @Test
    fun `size matches the spec examples`() {
        assertEquals("1 byte", size(1))
        assertEquals("512 bytes", size(512))
        assertEquals("1 kilobyte", size(1024))
        assertEquals("2.5 megabytes", size(2_621_440))
        assertEquals("5 gigabytes", size(5L * 1024 * 1024 * 1024))
        assertEquals("1.2 terabytes", size((1.2 * 1024 * 1024 * 1024 * 1024).toLong()))
    }

    // ── size: boundaries ─────────────────────────────────────────────────────

    @Test
    fun `size stays in bytes up to 1023 and promotes at 1024`() {
        assertEquals("1,023 bytes", size(1023))
        assertEquals("1 kilobyte", size(1024))
    }

    @Test
    fun `size promotes at every unit boundary`() {
        assertEquals("1,023 kilobytes", size(1023L * 1024))
        assertEquals("1 megabyte", size(1024L * 1024))
        assertEquals("1 gigabyte", size(1024L * 1024 * 1024))
        assertEquals("1 terabyte", size(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `size promotes rather than rounding up into the next unit`() {
        // 1048575 bytes is 1023.999 kilobytes; rounding to one decimal would
        // print "1024 kilobytes" if the promotion re-check were missing.
        assertEquals("1 megabyte", size(1_048_575))
    }

    @Test
    fun `size keeps terabytes as the largest unit`() {
        assertEquals("2,048 terabytes", size(2048L * 1024 * 1024 * 1024 * 1024))
    }

    // ── size: plural and decimal handling ────────────────────────────────────

    @Test
    fun `size uses the singular form only for exactly one`() {
        assertEquals("1 byte", size(1))
        assertEquals("2 bytes", size(2))
        assertEquals("1 kilobyte", size(1024))
        assertEquals("2 kilobytes", size(2048))
    }

    @Test
    fun `size uses the plural form for fractional values`() {
        assertEquals("1.5 kilobytes", size(1536))
        assertEquals("1.5 megabytes", size(1_572_864))
    }

    @Test
    fun `size drops a trailing zero decimal`() {
        assertEquals("3 megabytes", size(3L * 1024 * 1024))
        assertEquals("2 gigabytes", size(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `size renders zero and negative input as zero bytes`() {
        assertEquals("0 bytes", size(0))
        assertEquals("0 bytes", size(-1))
    }

    // ── duration: the spec's worked examples ─────────────────────────────────

    @Test
    fun `duration matches the spec examples`() {
        assertEquals("1 second", duration(1_000))
        assertEquals("45 seconds", duration(45_000))
        assertEquals("3 minutes", duration(180_000))
        assertEquals("2 minutes 5 seconds", duration(125_000))
        assertEquals("2 hours", duration(7_200_000))
        assertEquals("1 hour 30 minutes", duration(5_400_000))
        assertEquals("3 days 4 hours", duration(273_600_000))
    }

    // ── duration: boundaries ─────────────────────────────────────────────────

    @Test
    fun `duration stays in seconds up to 59 and promotes at 60`() {
        assertEquals("59 seconds", duration(59_000))
        assertEquals("1 minute", duration(60_000))
    }

    @Test
    fun `duration stays in minutes up to 59 and promotes at 60`() {
        assertEquals("59 minutes", duration(59L * 60_000))
        assertEquals("1 hour", duration(60L * 60_000))
    }

    @Test
    fun `duration stays in hours up to 23 and promotes at 24`() {
        assertEquals("23 hours", duration(23L * 3_600_000))
        assertEquals("1 day", duration(24L * 3_600_000))
    }

    // ── duration: unit count and plural handling ─────────────────────────────

    @Test
    fun `duration never shows more than two units`() {
        // 1 day, 2 hours, 3 minutes and 4 seconds truncates after the hours.
        assertEquals("1 day 2 hours", duration(93_784_000))
    }

    @Test
    fun `duration omits a zero smaller unit`() {
        assertEquals("5 minutes", duration(300_000))
        assertEquals("2 hours", duration(7_200_000))
        assertEquals("1 day", duration(86_400_000))
    }

    @Test
    fun `duration uses the singular form only for exactly one`() {
        assertEquals("1 second", duration(1_000))
        assertEquals("2 seconds", duration(2_000))
        assertEquals("1 minute 1 second", duration(61_000))
        assertEquals("1 hour 1 minute", duration(3_660_000))
        assertEquals("1 day 1 hour", duration(90_000_000))
    }

    @Test
    fun `duration floors sub-second and negative input to zero seconds`() {
        assertEquals("0 seconds", duration(999))
        assertEquals("0 seconds", duration(0))
        assertEquals("0 seconds", duration(-5_000))
    }

    // ── rate ─────────────────────────────────────────────────────────────────

    @Test
    fun `rate appends the per-second suffix to a formatted size`() {
        assertEquals("1.5 kilobytes/s", Format.rate(context, 1536))
        assertEquals("1 byte/s", Format.rate(context, 1))
    }

    // ── count ────────────────────────────────────────────────────────────────

    @Test
    fun `count groups thousands`() {
        assertEquals("12,847", Format.count(12_847))
        assertEquals("999", Format.count(999))
    }
}
