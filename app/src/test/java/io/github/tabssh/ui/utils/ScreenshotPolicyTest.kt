package io.github.tabssh.ui.utils

import io.github.tabssh.ui.utils.ScreenshotPolicy.MODE_ALLOW
import io.github.tabssh.ui.utils.ScreenshotPolicy.MODE_DISABLED
import io.github.tabssh.ui.utils.ScreenshotPolicy.MODE_LIMITED
import io.github.tabssh.ui.utils.ScreenshotPolicy.Screen
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenshotPolicyTest {

    @Test
    fun `the PIN screen is blocked at every level`() {
        listOf(MODE_DISABLED, MODE_LIMITED, MODE_ALLOW).forEach { assertTrue(ScreenshotPolicy.shouldBlock(it, Screen.PIN)) }
    }

    @Test
    fun `App Security is blocked at every level`() {
        listOf(MODE_DISABLED, MODE_LIMITED, MODE_ALLOW).forEach { assertTrue(ScreenshotPolicy.shouldBlock(it, Screen.APP_SECURITY)) }
    }

    @Test
    fun `secret screens are blocked unless screenshots are allowed`() {
        assertTrue(ScreenshotPolicy.shouldBlock(MODE_DISABLED, Screen.SECRET))
        assertTrue(ScreenshotPolicy.shouldBlock(MODE_LIMITED, Screen.SECRET))
        assertFalse(ScreenshotPolicy.shouldBlock(MODE_ALLOW, Screen.SECRET))
    }

    @Test
    fun `ordinary screens are blocked only when disabled`() {
        assertTrue(ScreenshotPolicy.shouldBlock(MODE_DISABLED, Screen.NORMAL))
        assertFalse(ScreenshotPolicy.shouldBlock(MODE_LIMITED, Screen.NORMAL))
        assertFalse(ScreenshotPolicy.shouldBlock(MODE_ALLOW, Screen.NORMAL))
    }

    private class Prefs(private val values: Map<String, Any?>) : android.content.SharedPreferences by org.mockito.Mockito.mock(android.content.SharedPreferences::class.java) {
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    }

    @Test
    fun `before a level is saved the old switch decides`() {
        assertEquals(MODE_DISABLED, ScreenshotPolicy.modeFrom(Prefs(mapOf("security_prevent_screenshots" to true))))
        assertEquals(MODE_LIMITED, ScreenshotPolicy.modeFrom(Prefs(emptyMap())))
        assertEquals(MODE_ALLOW, ScreenshotPolicy.modeFrom(Prefs(mapOf("security_screenshot_mode" to MODE_ALLOW, "security_prevent_screenshots" to true))))
    }
}
