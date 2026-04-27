package io.github.tabssh.crypto.fido

import android.content.Context
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import io.github.tabssh.utils.logging.Logger

/**
 * Wave 8.3 — **ALPHA** FIDO2 / U2F security-key detection.
 *
 * Status: detection only. SSH authentication via FIDO2 (sk-ed25519,
 * sk-ecdsa-sha2-nistp256) is NOT WIRED — JSch (the SSH library this app
 * uses) does not support `sk-*` key types, and reimplementing the CTAP2
 * client-side wire protocol over USB-HID / NFC / BLE is a multi-day
 * subsystem of its own.
 *
 * What this DOES today:
 *  - Scans USB devices for known FIDO2 vendor IDs.
 *  - Reports the device's vendor / product / interface info.
 *  - Reports whether NFC is available on the device (FIDO2 over NFC works
 *    when an authenticator is tapped).
 *
 * What this does NOT do:
 *  - Speak CTAP2.
 *  - Sign SSH challenges.
 *  - Persist any FIDO2 credentials.
 *
 * Use this in the Settings → "FIDO2 Hardware (Alpha)" entry to confirm
 * a YubiKey or similar is plugged in / detected. Do NOT rely on it for
 * authentication; that's a separate (deferred) project.
 */
object Fido2Detector {

    private const val TAG = "Fido2Detector"

    /**
     * Known FIDO2 / U2F authenticator vendors. Lifted from the FIDO Alliance
     * MDS list (public). Add as needed; the lookup is best-effort — an
     * unknown VID still gets reported with raw IDs.
     */
    private val KNOWN_VENDORS = mapOf(
        0x1050 to "Yubico",
        0x20A0 to "Nitrokey",
        0x0483 to "STMicro / SoloKeys",
        0x096E to "Feitian",
        0x10C4 to "Silicon Labs (Token2)",
        0x4C4D to "OnlyKey",
        0x1209 to "Generic / pid.codes",
        0x0BDA to "Realtek (some FIDO2)",
        0x32A3 to "Trezor"
    )

    data class DetectedAuthenticator(
        val vendorId: Int,
        val productId: Int,
        val vendorName: String,
        val productName: String,
        val transport: Transport
    ) {
        enum class Transport { USB, NFC_AVAILABLE }

        fun summary(): String = when (transport) {
            Transport.USB -> "USB · ${vendorName} · ${productName} · " +
                "0x%04X:%04X".format(vendorId, productId)
            Transport.NFC_AVAILABLE -> "NFC available — tap an authenticator to read it"
        }
    }

    /**
     * Snapshot of currently-connected USB authenticators + NFC capability.
     * Empty list = nothing detected. Call from a coroutine if you don't
     * want to block; this is fast (< 50ms) but synchronous.
     */
    fun detect(context: Context): List<DetectedAuthenticator> {
        val out = mutableListOf<DetectedAuthenticator>()

        // ── USB Host ─────────────────────────────────────────────────────
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            usbManager?.deviceList?.values?.forEach { dev ->
                if (KNOWN_VENDORS.containsKey(dev.vendorId) || isProbablyFidoInterface(dev)) {
                    val vendorName = KNOWN_VENDORS[dev.vendorId] ?: "Vendor 0x%04X".format(dev.vendorId)
                    val productName = (dev.productName ?: "").ifBlank {
                        dev.manufacturerName ?: "Unknown"
                    }
                    out += DetectedAuthenticator(
                        vendorId = dev.vendorId,
                        productId = dev.productId,
                        vendorName = vendorName,
                        productName = productName,
                        transport = DetectedAuthenticator.Transport.USB
                    )
                    Logger.i(TAG, "USB FIDO2 candidate: $vendorName / $productName 0x%04X:%04X".format(dev.vendorId, dev.productId))
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "USB detection failed: ${e.message}")
        }

        // ── NFC capability ────────────────────────────────────────────────
        // We can only report whether NFC is *available*; we don't poll for
        // tags here — that needs an Activity foreground dispatch.
        try {
            val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
            if (nfcAdapter != null && nfcAdapter.isEnabled) {
                out += DetectedAuthenticator(
                    vendorId = 0,
                    productId = 0,
                    vendorName = "NFC",
                    productName = "NFC reader available",
                    transport = DetectedAuthenticator.Transport.NFC_AVAILABLE
                )
            }
        } catch (e: Exception) {
            Logger.w(TAG, "NFC detection failed: ${e.message}")
        }

        return out
    }

    /**
     * Heuristic: a USB device exposing a HID interface with usage page
     * 0xF1D0 (the FIDO U2F HID usage page) is almost certainly an
     * authenticator. The Android UsbDevice API doesn't expose HID report
     * descriptors directly, so we fall back to "exposes an HID interface
     * with non-keyboard / non-mouse class" — imperfect but lets unknown
     * vendors still register.
     */
    private fun isProbablyFidoInterface(dev: android.hardware.usb.UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            if (iface.interfaceClass == 0x03 /* HID */) {
                // Subclass 0 (no boot protocol) + protocol 0 (none) is the
                // standard for security keys. Keyboards have subclass 1 +
                // protocol 1; mice have subclass 1 + protocol 2.
                if (iface.interfaceSubclass == 0 && iface.interfaceProtocol == 0) {
                    return true
                }
            }
        }
        return false
    }
}
