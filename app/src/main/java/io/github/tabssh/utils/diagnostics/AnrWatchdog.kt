package io.github.tabssh.utils.diagnostics

import android.os.Handler
import android.os.Looper
import io.github.tabssh.utils.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects "Application Not Responding" conditions on the main thread and
 * dumps the main-thread stack trace into the debug log so they can be
 * recovered via "Copy Debug Logs". Runs only while debug logging is on.
 *
 * How it works:
 *   1. A daemon thread posts a no-op Runnable to the main Looper.
 *   2. It sleeps for `timeoutMs`.
 *   3. If the Runnable hasn't been observed running by then, the main
 *      thread is presumed blocked → capture the main thread's stack
 *      trace and write it to the debug log via `Logger.e`.
 *   4. After detecting an ANR, sleep `cooldownMs` extra so a sustained
 *      block (e.g. JIT compile, GC pause) doesn't produce dozens of
 *      identical traces in a row.
 *
 * The watchdog itself is intentionally tiny — no allocations in the
 * hot path beyond an `AtomicBoolean`, no callbacks back to UI code,
 * no behavior changes outside of writing to the debug log. Adding an
 * ANR catcher must not become a source of bugs.
 */
class AnrWatchdog(
    private val timeoutMs: Long = 5000,
    private val cooldownMs: Long = 5000,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var workerThread: Thread? = null

    /** Idempotent — calling start() while already running is a no-op. */
    fun start() {
        synchronized(this) {
            if (workerThread?.isAlive == true) return
            val t = Thread(::loop, "ANR-Watchdog").apply { isDaemon = true }
            workerThread = t
            t.start()
        }
        Logger.i(TAG, "ANR watchdog started (timeout=${timeoutMs}ms)")
    }

    fun stop() {
        synchronized(this) {
            workerThread?.interrupt()
            workerThread = null
        }
        Logger.i(TAG, "ANR watchdog stopped")
    }

    private fun loop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val tickedBack = AtomicBoolean(false)
                mainHandler.post { tickedBack.set(true) }

                Thread.sleep(timeoutMs)

                if (!tickedBack.get()) {
                    val main = Looper.getMainLooper().thread
                    val trace = main.stackTrace
                        .joinToString("\n") { "    at $it" }
                    Logger.e(
                        TAG,
                        "ANR — main thread blocked for >${timeoutMs}ms\n$trace"
                    )
                    Thread.sleep(cooldownMs)  // dedupe sustained blocks
                }
            }
        } catch (_: InterruptedException) {
            // Clean shutdown via stop() — no error.
        } catch (e: Exception) {
            // Defensive: don't let a bug in the watchdog escape and kill
            // the app or pollute the debug log indefinitely.
            Logger.w(TAG, "Watchdog loop bailed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "AnrWatchdog"
    }
}
