package io.github.tabssh.ui.views

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for the "scrollbar sends arrow keys" bug: with no local
 * scrollback (alt-screen app, empty transcript) the thumb refused the touch,
 * so a vertical drag on the scrollbar fell through to the swipe path and was
 * converted into wheel/arrow forwarding — remote TUIs then complained
 * "Scroll wheel is sending arrow keys". A desktop scrollbar (xfce4-terminal)
 * is inert there and never synthesizes keys, so the thumb must claim and
 * consume vertical drags in its strip even when there is nothing to scrub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalViewThumbClaimTest {

    private fun field(view: TerminalView, name: String): Any {
        val f = TerminalView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view)!!
    }

    private fun makeView(): TerminalView {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = TerminalView(context)
        view.initialize(24, 80)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 1080, 1920)
        shadowOf(Looper.getMainLooper()).idle()
        return view
    }

    private fun touch(view: TerminalView, downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        view.onTouch(view, event)
        event.recycle()
    }

    @Test
    fun `vertical drag in the strip claims the thumb even with no scrollback`() {
        val view = makeView()
        // Fresh buffer, no termuxBridge: maxScrollYPx() == 0 — the exact
        // state (alt-screen / empty transcript) that used to leak the drag.
        val x = 1080f - 2f
        val downTime = SystemClock.uptimeMillis()
        touch(view, downTime, MotionEvent.ACTION_DOWN, x, 800f)
        touch(view, downTime, MotionEvent.ACTION_MOVE, x, 950f)
        assertTrue(
            field(view, "thumbDragging") as Boolean,
            "vertical drag on the scrollbar strip must be claimed (and consumed) even when maxScroll == 0"
        )
        touch(view, downTime, MotionEvent.ACTION_UP, x, 950f)
        assertFalse(field(view, "thumbDragging") as Boolean)
    }

    @Test
    fun `horizontal drag in the strip is released to other gestures`() {
        val view = makeView()
        val downTime = SystemClock.uptimeMillis()
        touch(view, downTime, MotionEvent.ACTION_DOWN, 1078f, 800f)
        touch(view, downTime, MotionEvent.ACTION_MOVE, 900f, 810f)
        assertFalse(
            field(view, "thumbDragging") as Boolean,
            "predominantly horizontal movement must never claim the thumb (edge-swipe tab fling)"
        )
        touch(view, downTime, MotionEvent.ACTION_UP, 900f, 810f)
    }
}
