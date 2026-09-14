package io.github.tabssh.utils

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.utils.logging.Logger

/**
 * Grants and checks the "file-manager-class" full filesystem access the local
 * SFTP browser uses (AI.md PART 2/5 — the local browser's core declared
 * feature is unrestricted filesystem access, so `MANAGE_EXTERNAL_STORAGE` is
 * the narrowest correct API rather than a broad-permission shortcut).
 *
 * ## API-level behavior
 *
 *  | SDK level | Full access check                          | Grant flow |
 *  |-----------|---------------------------------------------|------------|
 *  | ≥ 30      | [Environment.isExternalStorageManager]       | [Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION] |
 *  | 29        | [Environment.isExternalStorageLegacy] + legacy runtime grant (`requestLegacyExternalStorage` opts back into the legacy model) | [legacyPermissionLauncher] |
 *  | ≤ 28      | legacy `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` runtime grant | [legacyPermissionLauncher] |
 *
 * If the user declines (or an API 29 install is stuck in scoped storage —
 * legacy mode is fixed at install time), callers fall back to the existing
 * Storage Access Framework (SAF) tree-picker flow — this helper never forces
 * the permission, it only offers the faster path.
 */
object StorageAccessHelper {

    private const val TAG = "StorageAccessHelper"

    private val LEGACY_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true when the app can already read/write the shared filesystem
     * directly (no SAF `DocumentFile` indirection needed).
     */
    fun hasFullAccess(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                Environment.isExternalStorageManager()
            // API 29: requestLegacyExternalStorage keeps the pre-scoped-storage model,
            // so direct access works when legacy mode is active and both perms are held
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                Environment.isExternalStorageLegacy() && LEGACY_PERMISSIONS.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
            else ->
                LEGACY_PERMISSIONS.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
        }
    }

    /**
     * Shows a rationale dialog explaining why full file access is requested,
     * then launches the appropriate grant flow. If already granted, calls
     * [onAlreadyGranted] immediately instead of showing the dialog.
     *
     * On API 29 the legacy runtime permissions are only worth requesting when
     * the install actually runs in legacy mode ([Environment.isExternalStorageLegacy]);
     * a scoped-storage install skips the dialog and stays on the SAF fallback.
     *
     * The actual grant result for the API ≥ 30 Settings flow is not delivered
     * via [ActivityResultLauncher] (no such contract exists for that intent);
     * [onSettingsLaunched] fires right before that intent is started so the
     * caller can arm an `onResume()` re-check — it does NOT fire merely for
     * the rationale dialog being shown, only for an actual Settings round
     * trip, so dismissing the dialog without tapping OK never arms a
     * re-check that could misfire on some unrelated later resume.
     */
    fun requestFullAccessIfNeeded(
        activity: Activity,
        legacyPermissionLauncher: ActivityResultLauncher<Array<String>>,
        onAlreadyGranted: () -> Unit = {},
        onSettingsLaunched: () -> Unit = {}
    ) {
        if (hasFullAccess(activity)) {
            Logger.d(TAG, "Full storage access already granted")
            onAlreadyGranted()
            return
        }

        // API 29 outside legacy mode: the legacy permissions grant nothing under
        // scoped storage, so requesting them would be a pointless prompt — stay on SAF
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && !Environment.isExternalStorageLegacy()) {
            Logger.d(TAG, "API 29 install is in scoped-storage mode; staying on SAF fallback")
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.storage_helper_exemption_title))
            .setMessage(activity.getString(R.string.storage_helper_exemption_message))
            .setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    onSettingsLaunched()
                    openManageAllFilesSettings(activity)
                } else {
                    legacyPermissionLauncher.launch(LEGACY_PERMISSIONS)
                }
            }
            .setNegativeButton(activity.getString(R.string.fileopen_not_now), null)
            .show()
    }

    /**
     * Opens the system "Allow access to manage all files" screen for this
     * app (API ≥ 30), falling back to the app's own settings page, and then
     * to the general "manage all files access" list, if the per-app intent
     * cannot be resolved.
     */
    fun openManageAllFilesSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Logger.w(TAG, "Per-app all-files-access settings unavailable, falling back", e)
            try {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: ActivityNotFoundException) {
                Logger.w(TAG, "All-files-access settings unavailable, falling back to App Info", e2)
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
