package io.github.tabssh.sync.merge

import android.content.Context
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.ThemeDefinition
import io.github.tabssh.sync.encryption.SyncEncryptor
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Post-sync base state for the three-way merge (§9.6).
 *
 * Holds the reconciled values of exactly the four merge-tracked entity types
 * after a successful sync. On the next sync this becomes the `base` argument to
 * `MergeEngine`, so genuine divergence (both sides changed a field since the
 * shared ancestor) is detected as a conflict instead of degenerating to
 * last-write-wins.
 *
 * None of these four entities carry plaintext credentials: connection passwords
 * live in `PreferenceManager` (`conn_pw_{id}`) and SSH private key material is
 * Keystore-bound, neither of which is a column here. The snapshot is still
 * encrypted at rest with the sync password so it matches the sync-file posture.
 */
@Serializable
data class SyncBaseSnapshot(
    val connections: List<ConnectionProfile> = emptyList(),
    val keys: List<StoredKey> = emptyList(),
    val themes: List<ThemeDefinition> = emptyList(),
    val hostKeys: List<HostKeyEntry> = emptyList()
)

/**
 * Loads and persists the encrypted base snapshot in the app's private files dir.
 */
class SyncBaseSnapshotStore(private val context: Context) {

    companion object {
        private const val TAG = "SyncBaseSnapshot"
        private const val FILE_NAME = "sync_base_snapshot.dat"
    }

    private val encryptor = SyncEncryptor()
    private val json = Json { ignoreUnknownKeys = true }

    private fun snapshotFile(): File = File(context.filesDir, FILE_NAME)

    /**
     * Load the previous post-sync snapshot, or null on first sync / read error.
     * A missing or unreadable snapshot degrades gracefully to last-write-wins.
     */
    suspend fun load(password: String): SyncBaseSnapshot? = withContext(Dispatchers.IO) {
        val file = snapshotFile()
        if (!file.exists()) return@withContext null
        try {
            val plaintext = encryptor.decrypt(file.readBytes(), password)
            json.decodeFromString<SyncBaseSnapshot>(plaintext.decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Base snapshot unreadable; treating this as a first sync", e)
            null
        }
    }

    /**
     * Persist the reconciled state atomically (temp file + rename) so a crash
     * mid-write can never leave a half-encrypted, undecryptable snapshot.
     */
    suspend fun save(snapshot: SyncBaseSnapshot, password: String) = withContext(Dispatchers.IO) {
        try {
            val plaintext = json.encodeToString(snapshot).encodeToByteArray()
            val encrypted = encryptor.encrypt(plaintext, password)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeBytes(encrypted)
            if (!tmp.renameTo(snapshotFile())) {
                snapshotFile().writeBytes(encrypted)
                tmp.delete()
            }
            Logger.d(TAG, "Saved base snapshot (${encrypted.size} bytes)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save base snapshot", e)
        }
    }

    /**
     * Remove the snapshot — call when sync is cleared/reset so the next sync
     * starts fresh instead of merging against a stale ancestor.
     */
    fun clear() {
        try {
            snapshotFile().delete()
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to clear base snapshot", e)
        }
    }
}
