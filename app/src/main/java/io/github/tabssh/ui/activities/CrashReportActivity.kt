package io.github.tabssh.ui.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shown automatically after a crash in debug builds.
 * Displays the full stack trace with Copy / Share / Restart options
 * so developers don't need ADB to diagnose crashes.
 */
class CrashReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_report)

        val prefs = getSharedPreferences(TabSSHApplication.STARTUP_PREFS, MODE_PRIVATE)
        val stackTrace = prefs.getString(TabSSHApplication.KEY_LAST_CRASH, null)
            ?: "No crash details available."
        val crashTime = prefs.getLong(TabSSHApplication.KEY_CRASH_TIME, 0L)
        val crashThread = prefs.getString(TabSSHApplication.KEY_CRASH_THREAD, "unknown") ?: "unknown"

        prefs.edit()
            .remove(TabSSHApplication.KEY_LAST_CRASH)
            .remove(TabSSHApplication.KEY_CRASH_TIME)
            .remove(TabSSHApplication.KEY_CRASH_THREAD)
            .apply()

        val timestamp = if (crashTime > 0) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(crashTime))
        } else ""

        findViewById<TextView>(R.id.text_timestamp).text = timestamp
        findViewById<TextView>(R.id.text_thread).text = "Thread: $crashThread"
        findViewById<TextView>(R.id.text_stacktrace).text = stackTrace

        findViewById<MaterialButton>(R.id.btn_copy).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Crash", stackTrace))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btn_share).setOnClickListener {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TabSSH crash report")
                    putExtra(Intent.EXTRA_TEXT, stackTrace)
                },
                "Share crash report"
            ))
        }

        findViewById<MaterialButton>(R.id.btn_restart).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
        }
    }
}
