package io.github.tabssh.ui.dialogs

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the two free-text Docker dialog inputs that reach the transport:
 * the pull dialog's image reference and the registry credential host.
 *
 * Both values become command/URL components, so an option-like or
 * whitespace-bearing value must be rejected in the dialog rather than being
 * split into extra arguments by the remote shell.
 */
class ContainerDialogInputValidationTest {

    @Test
    fun `accepts ordinary image references`() {
        assertTrue(PullImageDialog.isPlausibleRef("nginx"))
        assertTrue(PullImageDialog.isPlausibleRef("nginx:1.25-alpine"))
        assertTrue(PullImageDialog.isPlausibleRef("library/nginx:latest"))
        assertTrue(PullImageDialog.isPlausibleRef("ghcr.io/owner/app:v1.2.3"))
        assertTrue(
            PullImageDialog.isPlausibleRef(
                "nginx@sha256:0000000000000000000000000000000000000000000000000000000000000000"
            )
        )
    }

    @Test
    fun `rejects empty oversized and option-like references`() {
        assertFalse(PullImageDialog.isPlausibleRef(""))
        assertFalse(PullImageDialog.isPlausibleRef("-v"))
        assertFalse(PullImageDialog.isPlausibleRef("--all-tags"))
        assertFalse(PullImageDialog.isPlausibleRef("a".repeat(257)))
        assertTrue(PullImageDialog.isPlausibleRef("a".repeat(256)))
    }

    @Test
    fun `rejects references with whitespace or shell metacharacters`() {
        assertFalse(PullImageDialog.isPlausibleRef("nginx latest"))
        assertFalse(PullImageDialog.isPlausibleRef("nginx;id"))
        assertFalse(PullImageDialog.isPlausibleRef("nginx\$(id)"))
        assertFalse(PullImageDialog.isPlausibleRef("nginx`id`"))
        assertFalse(PullImageDialog.isPlausibleRef("nginx\nid"))
    }

    @Test
    fun `rejects unicode homoglyph references`() {
        assertFalse(PullImageDialog.isPlausibleRef("ng\u0456nx"))
    }

    @Test
    fun `accepts bare registry hosts with an optional port`() {
        assertTrue(RegistryCredentialDialog.isPlausibleRegistryHost("docker.io"))
        assertTrue(RegistryCredentialDialog.isPlausibleRegistryHost("ghcr.io"))
        assertTrue(RegistryCredentialDialog.isPlausibleRegistryHost("registry.local:5000"))
        assertTrue(RegistryCredentialDialog.isPlausibleRegistryHost("127.0.0.1:5000"))
    }

    @Test
    fun `rejects schemes paths and embedded credentials`() {
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("https://docker.io"))
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("docker.io/v2/"))
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("user:pass@docker.io"))
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("docker.io?x=1"))
    }

    @Test
    fun `rejects malformed ports`() {
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("registry.local:"))
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("registry.local:0"))
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("registry.local:65536"))
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("registry.local:abc"))
        assertTrue(RegistryCredentialDialog.isPlausibleRegistryHost("registry.local:65535"))
    }

    @Test
    fun `rejects empty oversized and dot-anchored hosts`() {
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost(""))
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost(".docker.io"))
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("docker.io."))
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("a".repeat(256)))
    }

    @Test
    fun `rejects hosts with whitespace or control characters`() {
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("docker.io evil.io"))
        assertFalse(RegistryCredentialDialog.isPlausibleRegistryHost("docker.io\nevil.io"))
    }
}
