package io.github.tabssh.hypervisor.console.rfb

/**
 * Server-class fingerprint derived from the RFB ServerInit desktop name and
 * (optionally) the capability signals that arrive later in the stream.
 *
 * The profile is the single place where "this server is QEMU" or "this server
 * is TightVNC" lives.  Every protocol decision that depends on server identity
 * — should we send SetDesktopSize?  is this server known to discard FBURs?
 * does it understand TightPng? — reads the matching field here instead of
 * sprinkling `name.startsWith("QEMU (")` checks across the codebase.
 *
 * Detection is intentionally conservative: when the desktop name is empty or
 * unrecognized the profile reports [Vendor.UNKNOWN] with every capability
 * defaulting to the spec-compliant behaviour.  Heuristics are additive — a new
 * vendor adds a branch in [detect]; existing branches stay untouched.
 */
data class VncServerProfile(
    /** Best-guess server vendor from the desktop name. */
    val vendor: Vendor,
    /** The raw desktop name as sent on the wire (UTF-8, untrimmed). */
    val desktopName: String,
    /**
     * Whether the server is known to reject client-initiated SetDesktopSize.
     * True for QEMU's std-vga / cirrus / text-mode consoles (and Proxmox
     * vncproxy, which is a passthrough to QEMU).  When true, [RfbClient]
     * suppresses SetDesktopSize so the viewport-autodetect path becomes a
     * clean no-op instead of triggering the rejection→close→reconnect dance.
     */
    val suppressClientResize: Boolean,
    /**
     * Whether the server discards (rather than queues) FramebufferUpdateRequests
     * that arrive while one is already in flight.  True for QEMU-class servers:
     * the keepalive loop in [RfbClient] re-arms a FBUR every [RfbClient]
     * KEEPALIVE_MS so dirty regions are not silently dropped.
     */
    val needsFburKeepalive: Boolean,
    /** Whether the server is expected to accept the TightPng pseudo-encoding. */
    val supportsTightPng: Boolean,
) {
    enum class Vendor {
        UNKNOWN,
        QEMU,
        PROXMOX_VNCPROXY,
        TIGER_VNC,
        TIGHT_VNC,
        REAL_VNC,
        ULTRA_VNC,
        X11VNC,
        LIBVIRT,
        VMWARE,
        XEN,
    }

    companion object {
        /**
         * Build a profile from the desktop name string sent in ServerInit.
         *
         * The detection table:
         *   "QEMU (...)"                → QEMU            — guest-controlled display
         *   "pve-qemu-...":             → PROXMOX_VNCPROXY — wraps QEMU
         *   contains "TigerVNC"         → TIGER_VNC
         *   contains "TightVNC"         → TIGHT_VNC
         *   contains "RealVNC"          → REAL_VNC
         *   contains "UltraVNC"         → ULTRA_VNC
         *   contains "x11vnc"           → X11VNC
         *   starts with "libvirt-"      → LIBVIRT
         *   contains "VMware"           → VMWARE
         *   starts with "Xen "          → XEN
         *   anything else               → UNKNOWN with spec-default capabilities
         *
         * Matching is case-sensitive on purpose — every known emitter uses
         * the exact casing shown.  Adding a vendor adds one branch and one
         * test; existing branches stay byte-for-byte identical.
         */
        fun detect(desktopName: String): VncServerProfile {
            val name = desktopName

            val vendor = when {
                name.startsWith("QEMU (") -> Vendor.QEMU
                name.startsWith("pve-qemu") -> Vendor.PROXMOX_VNCPROXY
                name.contains("TigerVNC") -> Vendor.TIGER_VNC
                name.contains("TightVNC") -> Vendor.TIGHT_VNC
                name.contains("RealVNC") -> Vendor.REAL_VNC
                name.contains("UltraVNC") -> Vendor.ULTRA_VNC
                name.contains("x11vnc") -> Vendor.X11VNC
                name.startsWith("libvirt-") -> Vendor.LIBVIRT
                name.contains("VMware") -> Vendor.VMWARE
                name.startsWith("Xen ") -> Vendor.XEN
                else -> Vendor.UNKNOWN
            }

            val suppressClientResize = vendor == Vendor.QEMU ||
                                       vendor == Vendor.PROXMOX_VNCPROXY ||
                                       vendor == Vendor.VMWARE ||
                                       vendor == Vendor.XEN
            val needsFburKeepalive = vendor == Vendor.QEMU ||
                                     vendor == Vendor.PROXMOX_VNCPROXY
            val supportsTightPng = vendor == Vendor.TIGHT_VNC ||
                                   vendor == Vendor.ULTRA_VNC

            return VncServerProfile(
                vendor = vendor,
                desktopName = desktopName,
                suppressClientResize = suppressClientResize,
                needsFburKeepalive = needsFburKeepalive,
                supportsTightPng = supportsTightPng,
            )
        }
    }
}
