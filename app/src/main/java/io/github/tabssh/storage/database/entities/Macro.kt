package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Issue #173 — recordable macro: an opaque byte sequence captured from
 * the user's keystrokes (and any escape sequences sent by paste / soft
 * key panel) during a record session, replayable verbatim into any
 * connected terminal.
 *
 * This is distinct from [Snippet]:
 *   - Snippets are typed text with optional `{?var}` substitutions.
 *   - Macros are raw bytes — they capture function-key escape codes,
 *     CTL+letter codes, paste payloads, etc., exactly as the user
 *     entered them. Useful for replaying complex tmux/screen flows
 *     ("split, switch, run X, switch, run Y") without typing each
 *     keystroke again.
 *
 * The `sequence` column is the Base64-encoded byte stream so we can
 * persist binary safely as TEXT (Room's default for ByteArray would
 * also work but base64 round-trips through sync export cleanly).
 */
@Serializable
@Entity(tableName = "macros")
data class Macro(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    /** Base64-encoded recorded bytes. */
    @ColumnInfo(name = "sequence_b64")
    val sequenceB64: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 0
) {
    fun decodedSequence(): ByteArray =
        android.util.Base64.decode(sequenceB64, android.util.Base64.NO_WRAP)

    companion object {
        fun fromBytes(name: String, bytes: ByteArray): Macro = Macro(
            name = name,
            sequenceB64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        )
    }
}
