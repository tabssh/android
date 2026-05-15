package io.github.tabssh.background

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import io.github.tabssh.utils.logging.Logger

/**
 * Helps ensure the app can perform reliable background monitoring by guiding
 * the user through Android's battery optimization settings.
 *
 * ## Why this matters
 *
 * Android's Doze mode and App Standby buckets can defer WorkManager periodic
 * work for hours when the device is idle and not charging. Without a battery
 * optimization exemption the availability worker may not fire at all during
 * an extended Doze session, meaning a host could be down for hours before the
 * user is notified.
 *
 * [REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] puts the app in the "unrestricted"
 * App Standby bucket, which is exactly what email, alarm, and monitoring apps
 * (K-9 Mail, FairEmail, ClockworkMod Tether, etc.) request.
 *
 * ## OEM layers
 *
 * Stock Android exemption is necessary but not always sufficient. Several
 * manufacturers ship their own battery management layer on top:
 *
 *  | OEM            | System     | Effect if not disabled           |
 *  |----------------|------------|----------------------------------|
 *  | Samsung One UI | Device Care → Battery → Sleeping apps | Kills work after screen-off |
 *  | Xiaomi MIUI    | Security → Battery saver → App battery saver | "Restricted" = no background |
 *  | Huawei EMUI    | Settings → Battery → App launch (manual) | No auto-start → no background |
 *  | OnePlus OxygenOS | Settings → Battery → Battery optimization | Standard + aggressive killing |
 *  | Oppo ColorOS   | Settings → Battery → Energy saver → App quick-freeze | Kills non-exempt apps |
 *  | Vivo FuntouchOS | iManager → Battery | Restricts background |
 *
 * [showManufacturerGuidance] attempts to deep-link to the OEM-specific screen
 * (falling back to the standard App Info page if the OEM intent is unavailable).
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimizationHelper"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true when the app is already exempt from battery optimization
     * (i.e., [PowerManager.isIgnoringBatteryOptimizations] returns true).
     */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Shows a rational dialog explaining why the exemption is needed, then
     * opens the system [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]
     * prompt.  If already exempt, does nothing.
     *
     * After the system prompt, [onResult] is called with true if the exemption
     * was granted (checked on resume by the caller), or the caller should
     * check [isExempt] again when the Activity resumes.
     */
    fun requestExemptionIfNeeded(context: Context, onAlreadyExempt: () -> Unit = {}) {
        if (isExempt(context)) {
            Logger.d(TAG, "Already exempt from battery optimization")
            onAlreadyExempt()
            return
        }

        AlertDialog.Builder(context)
            .setTitle("Allow background monitoring")
            .setMessage(
                "For reliable host alerts when the app is closed, TabSSH needs to be " +
                "exempt from battery optimization.\n\n" +
                "This is the same permission used by email and alarm apps. It does NOT " +
                "prevent the OS from pausing SSH sessions — it only ensures the " +
                "availability checker can run at its scheduled interval.\n\n" +
                "Tap OK to open the system prompt."
            )
            .setPositiveButton("OK") { _, _ -> launchExemptionRequest(context) }
            .setNegativeButton("Not now", null)
            .show()
    }

    /**
     * If a known OEM battery management screen can be reached, shows a
     * guidance dialog and offers to open it.  Falls back to the standard
     * App Info screen if no OEM intent is found.
     *
     * Call this AFTER [requestExemptionIfNeeded] so the user sees it as a
     * follow-up step ("also check your device's battery settings").
     */
    fun showManufacturerGuidanceIfNeeded(context: Context) {
        val (label, intent) = manufacturerBatteryIntent(context) ?: return
        AlertDialog.Builder(context)
            .setTitle("One more step")
            .setMessage(
                "Your device ($label) has additional battery restrictions that can " +
                "prevent background apps from running even after granting the exemption " +
                "above.\n\n" +
                "To ensure host alerts arrive promptly, open your device's battery " +
                "settings and make sure TabSSH is set to 'No restrictions' or 'Allow " +
                "background activity'."
            )
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Logger.w(TAG, "OEM battery intent not available, falling back to App Info")
                    openAppInfoSettings(context)
                }
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun launchExemptionRequest(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Device policy or ROM has disabled this prompt — fall back to App Info
            Logger.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS unavailable: ${e.message}")
            openAppInfoSettings(context)
        }
    }

    private fun openAppInfoSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Returns a human-readable OEM name and an Intent that opens the
     * manufacturer's own battery management screen, or null if the device
     * appears to be stock Android (no known OEM layer detected).
     */
    private fun manufacturerBatteryIntent(context: Context): Pair<String, Intent>? {
        val pm = context.packageManager
        val candidates = listOf(
            // Samsung One UI — Device Care → Battery → More battery settings
            "Samsung" to Intent().setClassName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            ),
            // Xiaomi / MIUI — Security → Battery saver
            "Xiaomi" to Intent().setClassName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            ),
            // Huawei / EMUI — Settings → Battery → App launch
            "Huawei" to Intent().setClassName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // OnePlus / OxygenOS — Settings → Battery
            "OnePlus" to Intent().setClassName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            ),
            // Oppo / ColorOS — Settings → Battery → Energy saver
            "Oppo" to Intent().setClassName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            // Vivo / FuntouchOS — iManager → Battery
            "Vivo" to Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
        )

        for ((label, intent) in candidates) {
            val resolved = pm.resolveActivity(intent, 0)
            if (resolved != null) {
                Logger.d(TAG, "Detected OEM battery screen: $label")
                return label to intent
            }
        }
        return null
    }
}
