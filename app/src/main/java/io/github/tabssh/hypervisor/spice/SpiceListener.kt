package io.github.tabssh.hypervisor.spice

/**
 * Callback surface for [SpiceClient].
 *
 * Deliberately shaped to mirror
 * `io.github.tabssh.hypervisor.console.rfb.RfbListener` so the display
 * view (task #14) can dispatch VNC and SPICE through a common
 * rendering path. Every method is called from a native worker
 * thread — implementations must marshal to the main thread themselves
 * before touching UI state.
 */
interface SpiceListener {
    /**
     * First primary display surface has been created. [framebuffer] is
     * the shared pixel array the display channel writes into; the
     * listener should keep a reference — subsequent
     * [onFramebufferUpdate] calls mutate it in place.
     *
     * [name] is the SPICE session display name (may be empty for
     * hypervisors that do not populate it).
     */
    fun onConnected(width: Int, height: Int, name: String, framebuffer: IntArray)

    /**
     * A rectangular region of the primary surface has been repainted.
     * The dirty region is `[x, y] .. [x+w, y+h]` within the framebuffer
     * handed out by [onConnected] / [onDesktopResize].
     */
    fun onFramebufferUpdate(x: Int, y: Int, w: Int, h: Int, framebuffer: IntArray)

    /**
     * The remote guest changed its primary surface size. A fresh
     * [framebuffer] is provided; the old one is invalid after this call
     * returns.
     */
    fun onDesktopResize(width: Int, height: Int, framebuffer: IntArray)

    /**
     * Cursor image update from the display channel. [pixels] is BGRA
     * `w × h`. [hotX] / [hotY] are the cursor hotspot relative to the
     * top-left corner. [mask] is empty for full-alpha cursors — SPICE
     * does not use an X11-style bitmap mask.
     */
    fun onCursorUpdate(hotX: Int, hotY: Int, w: Int, h: Int,
                       pixels: IntArray, mask: ByteArray) {}

    /**
     * The SPICE agent channel is up on the guest side — clipboard,
     * dynamic resize, and mouse capture are now available. Default is
     * a no-op; the display view uses this to enable those features.
     */
    fun onAgentConnected() {}

    /**
     * Clipboard text arrived from the guest agent. UTF-8.
     */
    fun onClipboardText(text: String) {}

    /**
     * Fatal transport or protocol error. The client is torn down after
     * this call and callers should not attempt further operations.
     */
    fun onError(message: String)

    /**
     * Session closed cleanly — either the guest powered off, the
     * hypervisor revoked the ticket, or [SpiceClient.stop] was called.
     */
    fun onDisconnected(reason: String) {}
}
