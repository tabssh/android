package io.github.tabssh.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal object RecordingFileNames {
    /** A bounded ASCII name with a unique suffix for simultaneous sessions. */
    fun create(prefix: String, title: String, extension: String): String {
        val name = title.take(80).replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_${name}_${timestamp}_${UUID.randomUUID()}.$extension"
    }
}
