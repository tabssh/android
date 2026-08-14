package io.github.tabssh.ui.activities

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the VMware manager's guest-address validator and string redactors.
 *
 * `vm.ipAddress` is reported by VMware Tools running *inside* the guest, so on a
 * compromised VM it is attacker-controlled — and "Open SSH" writes it straight
 * into a saved connection's host field. Before the fix any string the guest
 * returned was persisted verbatim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VMwareManagerGuestAddressTest {

    @Test
    fun `IPv4 IPv6 and hostname literals are accepted`() {
        assertTrue(VMwareManagerActivity.isValidGuestAddress("10.0.0.5"))
        assertTrue(VMwareManagerActivity.isValidGuestAddress("fe80::1"))
        assertTrue(VMwareManagerActivity.isValidGuestAddress("fe80::1%eth0"))
        assertTrue(VMwareManagerActivity.isValidGuestAddress("guest-01.internal"))
    }

    @Test
    fun `a scheme or path is rejected`() {
        assertFalse(VMwareManagerActivity.isValidGuestAddress("http://10.0.0.5"))
        assertFalse(VMwareManagerActivity.isValidGuestAddress("10.0.0.5/admin"))
        assertFalse(VMwareManagerActivity.isValidGuestAddress("10.0.0.5?x=1"))
        assertFalse(VMwareManagerActivity.isValidGuestAddress("10.0.0.5#f"))
    }

    @Test
    fun `userinfo is rejected — it would redirect the SSH target`() {
        assertFalse(VMwareManagerActivity.isValidGuestAddress("10.0.0.5@evil.example.com"))
    }

    @Test
    fun `whitespace and control characters are rejected`() {
        assertFalse(VMwareManagerActivity.isValidGuestAddress("10.0.0.5 evil"))
        assertFalse(VMwareManagerActivity.isValidGuestAddress("10.0.0.5\r\n"))
    }

    @Test
    fun `blank and over-long addresses are rejected`() {
        assertFalse(VMwareManagerActivity.isValidGuestAddress(""))
        assertFalse(VMwareManagerActivity.isValidGuestAddress("   "))
        assertFalse(VMwareManagerActivity.isValidGuestAddress("9".repeat(256)))
    }

    @Test
    fun `safeName strips newlines bidi overrides and caps length`() {
        assertEquals("vm-01fake", VMwareManagerActivity.safeName("vm-01\nfake"))
        assertEquals("harmlessdestroy", VMwareManagerActivity.safeName("harmless‮destroy‬"))
        assertEquals(128, VMwareManagerActivity.safeName("v".repeat(900)).length)
        assertEquals("(unnamed)", VMwareManagerActivity.safeName(null))
    }

    @Test
    fun `safeDetail strips controls caps length and has a fallback`() {
        assertEquals("fault:denied", VMwareManagerActivity.safeDetail("fault:\ndenied"))
        assertEquals(300, VMwareManagerActivity.safeDetail("x".repeat(9000)).length)
        assertEquals("unknown error", VMwareManagerActivity.safeDetail(null))
    }
}
