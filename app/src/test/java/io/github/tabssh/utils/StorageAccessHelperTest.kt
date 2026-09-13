package io.github.tabssh.utils

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [StorageAccessHelper.hasFullAccess]'s per-API-level branching
 * (AI.md PART 2 file-manager-class exception) for the two branches
 * Robolectric can actually shadow:
 *
 *  - API 29 (Q): permanently `false`, regardless of any granted permission —
 *    no full-filesystem-access API exists on that one OS version.
 *  - API ≤ 28: the legacy `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE`
 *    runtime-permission check.
 *
 * The API ≥ 30 [android.os.Environment.isExternalStorageManager] branch is
 * NOT covered here — Robolectric 4.14.1's `ShadowEnvironment` has no shadow
 * method for it, so it falls through to the real (unshadowed) framework
 * implementation under test, which is not a reliable signal. That path
 * needs the manual on-device verification called out in the Workstream 3
 * plan instead.
 */
@RunWith(RobolectricTestRunner::class)
class StorageAccessHelperTest {

    private fun context() = ApplicationProvider.getApplicationContext<Application>()

    @Config(sdk = [29])
    @Test
    fun `API 29 never reports full access, even with legacy permissions granted`() {
        Shadows.shadowOf(context()).grantPermissions(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        assertFalse(StorageAccessHelper.hasFullAccess(context()))
    }

    @Config(sdk = [28])
    @Test
    fun `API 28 reports full access once both legacy permissions are granted`() {
        val app = context()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        assertTrue(StorageAccessHelper.hasFullAccess(app))
    }

    @Config(sdk = [28])
    @Test
    fun `API 28 denies full access when only one legacy permission is granted`() {
        val app = context()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertFalse(StorageAccessHelper.hasFullAccess(app))
    }

    @Config(sdk = [28])
    @Test
    fun `API 28 denies full access when neither legacy permission is granted`() {
        val app = context()
        Shadows.shadowOf(app).denyPermissions(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        assertFalse(StorageAccessHelper.hasFullAccess(app))
    }
}
