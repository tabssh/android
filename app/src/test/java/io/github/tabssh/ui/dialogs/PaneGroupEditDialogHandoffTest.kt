package io.github.tabssh.ui.dialogs

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the Step 1 -> Step 2 handoff in
 * [PaneGroupEditDialog]: tapping "Next" dismissed the Step 1 dialog and then
 * scheduled Step 2 with `dialogView.postDelayed { ... }`. That never ran.
 *
 * `View.postDelayed` on a view that is not attached does not post to the main
 * looper at all — it parks the runnable in the view's own run-queue, which
 * `View.dispatchAttachedToWindow` drains only the next time the view is
 * attached. A dismissed dialog's content view is never re-attached, so the
 * runnable was silently dropped: the dialog closed and Step 2 never appeared,
 * leaving the whole pane group uneditable.
 *
 * The fix routes the handoff through [PaneGroupEditDialog.afterDialogDismissed],
 * which schedules on the caller's `CoroutineScope`. These tests cover that
 * function and pin the framework behaviour it exists to route around, so the
 * distinction cannot be quietly lost again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PaneGroupEditDialogHandoffTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    /**
     * A view in the state a dialog's content view is in from inside
     * `setOnDismissListener`: detached, and never attached here, so it takes
     * the same "not attached" branch the real one does.
     */
    private fun dismissedDialogView(): View = FrameLayout(context)

    /** Drains the main looper while the handoff matures. */
    private fun awaitLooper(condition: () -> Boolean, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        ShadowLooper.idleMainLooper()
        return condition()
    }

    @Test
    fun `afterDialogDismissed runs the handoff`() {
        // The fix's contract, exercised through the real entry point.
        val ran = AtomicBoolean(false)
        PaneGroupEditDialog.afterDialogDismissed(CoroutineScope(Dispatchers.Main), delayMs = 0L) {
            ran.set(true)
        }

        val handoffRan = awaitLooper({ ran.get() })
        assertTrue(handoffRan, "handoff never ran — Step 2 would never open")
    }

    @Test
    fun `afterDialogDismissed is not gated on any view being attached`() {
        // Guards the actual defect. The handoff must not depend on a view
        // being re-attached: the dismissed dialog's view never is, and that is
        // exactly how the original `postDelayed` version silently no-opped.
        val detachedView = dismissedDialogView()
        val ran = AtomicBoolean(false)
        var sawDetached = false

        PaneGroupEditDialog.afterDialogDismissed(CoroutineScope(Dispatchers.Main), delayMs = 0L) {
            // A dismissed dialog's view has no parent and no window token.
            sawDetached = detachedView.parent == null && detachedView.windowToken == null
            ran.set(true)
        }

        val handoffRan = awaitLooper({ ran.get() })
        assertTrue(handoffRan, "handoff never ran")
        assertTrue(sawDetached, "expected the dismissed dialog's view to be detached")
    }

    @Test
    fun `postDelayed on a detached view silently drops the handoff`() {
        // Pins the framework behaviour the fix routes around: this runnable
        // has the same shape and delay as the one Step 1 used, on the same
        // view state, and never runs. Were a framework change ever to make it
        // fire, this fails loudly so the routing can be re-examined — rather
        // than the distinction being lost and the detour looking pointless.
        val ran = AtomicBoolean(false)
        dismissedDialogView().postDelayed(
            { ran.set(true) },
            PaneGroupEditDialog.HANDOFF_DELAY_MS
        )

        val fired = awaitLooper({ ran.get() }, timeoutMs = 500L)
        assertFalse(
            fired,
            "postDelayed on a detached view fired; the handoff is no longer " +
                "silently dropped, so re-check whether scope-based " +
                "scheduling is still required"
        )
    }
}
