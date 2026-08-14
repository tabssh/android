package io.github.tabssh.ui.activities

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Tests for the Proxmox manager's server-string redactors.
 *
 * VM names, node names and API error bodies are all supplied by the hypervisor.
 * Before the fix they went straight into row text, dialog titles, toasts and log
 * lines, so a VM named with bidi overrides could reorder a confirmation dialog
 * (making a destructive action look like it targets a different VM) and a
 * newline-bearing name could forge whole log entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProxmoxManagerSanitizeTest {

    @Test
    fun `plain names pass through unchanged`() {
        assertEquals("web-01", ProxmoxManagerActivity.safeName("web-01"))
        assertEquals("pve node 2", ProxmoxManagerActivity.safeName("pve node 2"))
    }

    @Test
    fun `newlines cannot forge a log line`() {
        assertEquals(
            "web-01I/Auth: login ok",
            ProxmoxManagerActivity.safeName("web-01\nI/Auth: login ok")
        )
    }

    @Test
    fun `bidi overrides and isolates are stripped`() {
        assertEquals("harmlessdestroy", ProxmoxManagerActivity.safeName("harmless‮destroy‬"))
        assertEquals("ab", ProxmoxManagerActivity.safeName("a⁦b⁩"))
    }

    @Test
    fun `names are length-capped`() {
        assertEquals(128, ProxmoxManagerActivity.safeName("v".repeat(4000)).length)
    }

    @Test
    fun `empty and control-only names fall back to a placeholder`() {
        assertEquals("(unnamed)", ProxmoxManagerActivity.safeName(null))
        assertEquals("(unnamed)", ProxmoxManagerActivity.safeName(""))
        assertEquals("(unnamed)", ProxmoxManagerActivity.safeName("‮‬"))
    }

    @Test
    fun `safeDetail strips controls and caps length`() {
        assertEquals("500 ServerError", ProxmoxManagerActivity.safeDetail("500 Server\nError"))
        assertEquals(300, ProxmoxManagerActivity.safeDetail("x".repeat(9000)).length)
    }

    @Test
    fun `safeDetail falls back for null and blank input`() {
        assertEquals("unknown error", ProxmoxManagerActivity.safeDetail(null))
        assertEquals("unknown error", ProxmoxManagerActivity.safeDetail("  "))
    }
}
