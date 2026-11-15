package io.github.tabssh.hypervisor.proxmox

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Proxmox TFA challenge that offers only WebAuthn cannot be answered by the app. */
class PasskeyChallengeTest {

    private val client = ProxmoxApiClient("pve", 8006, "u", "p", "pam")

    @Test
    fun `webauthn alone is a passkey-only challenge`() {
        assertTrue(client.isPasskeyOnlyChallenge("""PVE:!tfa!{"webauthn":"{}"}:abc"""))
    }

    @Test
    fun `webauthn next to totp or recovery can still be answered`() {
        assertFalse(client.isPasskeyOnlyChallenge("""PVE:!tfa!{"webauthn":"{}","totp":true}:abc"""))
        assertFalse(client.isPasskeyOnlyChallenge("""PVE:!tfa!{"webauthn":"{}","recovery":"available"}:abc"""))
    }

    @Test
    fun `a totp-only challenge is not passkey-only`() {
        assertFalse(client.isPasskeyOnlyChallenge("""PVE:!tfa!{"totp":true}:abc"""))
    }
}
