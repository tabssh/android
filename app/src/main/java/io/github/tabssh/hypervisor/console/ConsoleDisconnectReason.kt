package io.github.tabssh.hypervisor.console

/**
 * Why a graphical console session (RFB or SPICE) ended when the end was NOT
 * user-initiated. Mirrors the terminal exit-code gate: CLEAN behaves like a
 * shell exiting 0 (the tab auto-closes), ERROR behaves like a non-zero exit
 * (the user gets a reconnect dialog). User-initiated stops never produce a
 * reason — the tab is simply closed by the code path that requested the stop.
 */
enum class ConsoleDisconnectReason {
    /** Orderly server EOF at a protocol message boundary — session ended normally. */
    CLEAN,

    /** Abrupt drop: socket reset, mid-message EOF, protocol error, or native SPICE error. */
    ERROR
}

/**
 * Pure classifier for RFB session-ending exceptions, kept free of any Android
 * or transport dependency so the decision table is unit-testable.
 */
object ConsoleDisconnectClassifier {

    /**
     * Classify the exception that ended the RFB reader loop.
     *
     * A clean server shutdown surfaces as [java.io.EOFException] from
     * `DataInputStream.readUnsignedByte()` exactly between protocol messages
     * (`atMessageBoundary` true). EOF mid-message means the stream was cut
     * while a payload was in flight, and every other exception type (socket
     * reset, protocol desync, TLS failure) is an abnormal drop — all ERROR.
     */
    fun classifyRfb(atMessageBoundary: Boolean, e: Throwable): ConsoleDisconnectReason =
        if (atMessageBoundary && e is java.io.EOFException) ConsoleDisconnectReason.CLEAN
        else ConsoleDisconnectReason.ERROR
}
