package io.github.tabssh.automation

import android.app.Application
import android.os.Bundle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for LocalePlugin.isBundleValid — the only guard between an
 * arbitrary app's FIRE_SETTING broadcast and TaskerWorker. Runs under
 * Robolectric because android.os.Bundle needs an Android runtime.
 *
 * A stock android.app.Application is forced via @Config so Robolectric does
 * not instantiate the real TabSSHApplication, whose teardown reaches the
 * AndroidKeyStore provider that Robolectric does not shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocalePluginBundleTest {

    private fun validBundle(): Bundle = LocalePlugin.buildBundle(
        action = TaskerWorker.ACTION_SEND_COMMAND,
        connectionId = "8f1c0f7a-1f2e-4c3d-9a5b-6d7e8f901234",
        connectionName = "devbox",
        command = "uptime",
        keys = null,
        waitForResult = false
    )

    @Test
    fun `bundle built by the edit screen validates`() {
        assertTrue(LocalePlugin.isBundleValid(validBundle()))
    }

    @Test
    fun `null and empty bundles are rejected`() {
        assertFalse(LocalePlugin.isBundleValid(null))
        assertFalse(LocalePlugin.isBundleValid(Bundle()))
    }

    @Test
    fun `version mismatch is rejected`() {
        val bundle = validBundle()
        bundle.putInt(LocalePlugin.BUNDLE_KEY_VERSION, LocalePlugin.BUNDLE_VERSION + 1)
        assertFalse(LocalePlugin.isBundleValid(bundle))
    }

    @Test
    fun `unknown action is rejected`() {
        val bundle = validBundle()
        bundle.putString(LocalePlugin.BUNDLE_KEY_ACTION, "io.github.tabssh.action.WIPE")
        assertFalse(LocalePlugin.isBundleValid(bundle))
    }

    @Test
    fun `name-only bundle is rejected so a guessed name cannot target a profile`() {
        val bundle = validBundle()
        bundle.remove(LocalePlugin.BUNDLE_KEY_CONNECTION_ID)
        assertFalse(LocalePlugin.isBundleValid(bundle))
        bundle.putString(LocalePlugin.BUNDLE_KEY_CONNECTION_ID, "")
        assertFalse(LocalePlugin.isBundleValid(bundle))
    }

    @Test
    fun `oversized fields are rejected`() {
        val longId = validBundle()
        longId.putString(LocalePlugin.BUNDLE_KEY_CONNECTION_ID, "x".repeat(LocalePlugin.MAX_NAME_LENGTH + 1))
        assertFalse(LocalePlugin.isBundleValid(longId))

        val longName = validBundle()
        longName.putString(LocalePlugin.BUNDLE_KEY_CONNECTION_NAME, "x".repeat(LocalePlugin.MAX_NAME_LENGTH + 1))
        assertFalse(LocalePlugin.isBundleValid(longName))

        val longCommand = validBundle()
        longCommand.putString(LocalePlugin.BUNDLE_KEY_COMMAND, "x".repeat(LocalePlugin.MAX_COMMAND_LENGTH + 1))
        assertFalse(LocalePlugin.isBundleValid(longCommand))

        val longKeys = LocalePlugin.buildBundle(
            action = TaskerWorker.ACTION_SEND_KEYS,
            connectionId = "id",
            connectionName = "devbox",
            command = null,
            keys = "x".repeat(LocalePlugin.MAX_KEYS_LENGTH + 1),
            waitForResult = false
        )
        assertFalse(LocalePlugin.isBundleValid(longKeys))
    }

    @Test
    fun `payload actions require their payload`() {
        val noCommand = validBundle()
        noCommand.remove(LocalePlugin.BUNDLE_KEY_COMMAND)
        assertFalse(LocalePlugin.isBundleValid(noCommand))

        val noKeys = validBundle()
        noKeys.putString(LocalePlugin.BUNDLE_KEY_ACTION, TaskerWorker.ACTION_SEND_KEYS)
        assertFalse(LocalePlugin.isBundleValid(noKeys))
    }
}
