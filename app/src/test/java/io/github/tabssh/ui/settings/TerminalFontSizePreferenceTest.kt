package io.github.tabssh.ui.settings

import android.app.Application
import android.content.Context
import android.widget.SeekBar
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import androidx.test.core.app.ApplicationProvider
import io.github.tabssh.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The terminal font size chosen in Settings must survive leaving and re-opening the screen (issue 16). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalFontSizePreferenceTest {

    class Screen : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: android.os.Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_terminal, rootKey)
        }
    }

    private fun open(): Pair<Screen, SeekBarPreference> {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        activity.setTheme(R.style.Theme_TabSSH)
        val screen = Screen()
        activity.supportFragmentManager.beginTransaction().add(screen, null).commitNow()
        return screen to assertNotNull(screen.findPreference<SeekBarPreference>("terminal_font_size"))
    }

    @Test
    fun `the bar runs from 8 to 32 so every position is a usable size`() {
        val (_, pref) = open()
        assertEquals(8, pref.min)
        assertEquals(32, pref.max)
    }

    @Test
    fun `a size set on the bar is still there when the screen is opened again`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (_, first) = open()
        assertEquals(14, first.value)
        first.value = 8
        val (_, second) = open()
        assertEquals(8, second.value)
        assertEquals(8, PreferenceManager.getDefaultSharedPreferences(context).getInt("terminal_font_size", 14))
        assertNotNull(SeekBar::class.java)
    }
}
