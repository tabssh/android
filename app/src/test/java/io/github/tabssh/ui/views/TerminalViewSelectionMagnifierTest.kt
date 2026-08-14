package io.github.tabssh.ui.views

import android.app.Application
import android.os.Build
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the Item 12 selection-handle drag loupe: a
 * [SelectionMagnifier] must appear while a handle is being dragged
 * (ACTION_MOVE, tracking the finger) and disappear the instant the finger
 * lifts (ACTION_UP/ACTION_CANCEL), and must never be constructed at all
 * below API 28 — the project's minSdk is 24 and [android.widget.Magnifier]
 * does not exist there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalViewSelectionMagnifierTest {

    private class FakeSelectionMagnifier : SelectionMagnifier {
        val showCalls = mutableListOf<Pair<Float, Float>>()
        var dismissCallCount = 0

        override fun show(x: Float, y: Float) {
            showCalls.add(x to y)
        }

        override fun dismiss() {
            dismissCallCount++
        }
    }

    private fun newViewWithSelection(): Pair<TerminalView, FakeSelectionMagnifier> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = TerminalView(context)
        view.initialize(24, 80)
        view.layout(0, 0, 800, 600)

        val fake = FakeSelectionMagnifier()
        view.magnifierFactory = { fake }

        // Public entry point used by the real long-press flow: activates
        // selection mode and pre-grabs the focus handle (selectionDragHandle
        // = 1), exactly like the state handleSelectionTouch expects to see
        // an in-progress drag in.
        view.beginWordSelectionAtTouch(40f, 40f)
        return Pair(view, fake)
    }

    private fun dispatchSelectionTouch(view: TerminalView, event: MotionEvent): Boolean {
        val method = TerminalView::class.java.getDeclaredMethod("handleSelectionTouch", MotionEvent::class.java)
        method.isAccessible = true
        return method.invoke(view, event) as Boolean
    }

    @Config(sdk = [Build.VERSION_CODES.P])
    @Test
    fun `dragging a handle shows the magnifier and lifting dismisses it`() {
        val (view, fake) = newViewWithSelection()

        val move = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 55f, 60f, 0)
        assertTrue(dispatchSelectionTouch(view, move))
        move.recycle()

        assertEquals(1, fake.showCalls.size)
        // X tracks the finger; Y is locked to the vertical center of the
        // dragged row (gridTop + (row + 0.5) * cellHeight) — magnifying the
        // raw touch point showed the boundary above the selected line.
        // Compute the expectation from the view's real metrics so the test
        // holds regardless of Robolectric's font-metric behavior.
        val cellHeightField = TerminalView::class.java
            .getDeclaredField("cellHeight").apply { isAccessible = true }
        val gridTopMethod = TerminalView::class.java
            .getDeclaredMethod("getGridTop").apply { isAccessible = true }
        val focusRowField = TerminalView::class.java
            .getDeclaredField("selectionFocusRow").apply { isAccessible = true }
        val cellHeight = cellHeightField.getFloat(view)
        val gridTop = gridTopMethod.invoke(view) as Float
        val focusRow = focusRowField.getInt(view)
        val expectedY = gridTop + (focusRow + 0.5f) * cellHeight
        assertEquals(55f to expectedY, fake.showCalls[0])
        assertEquals(0, fake.dismissCallCount)

        val up = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 55f, 60f, 0)
        assertTrue(dispatchSelectionTouch(view, up))
        up.recycle()

        assertEquals(1, fake.dismissCallCount)
    }

    @Config(sdk = [Build.VERSION_CODES.O])
    @Test
    fun `no magnifier is constructed below API P`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = TerminalView(context)
        view.initialize(24, 80)
        view.layout(0, 0, 800, 600)

        var factoryCalls = 0
        view.magnifierFactory = { factoryCalls++; PlatformSelectionMagnifier(view) }
        view.beginWordSelectionAtTouch(40f, 40f)

        val move = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 55f, 60f, 0)
        dispatchSelectionTouch(view, move)
        move.recycle()

        // getOrCreateSelectionMagnifier() must short-circuit on the SDK
        // check before ever invoking the factory (which would otherwise
        // construct a real android.widget.Magnifier and crash below API 28).
        assertEquals(0, factoryCalls)
    }
}
