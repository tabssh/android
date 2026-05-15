package io.github.tabssh.ui.dialogs

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import io.github.tabssh.BuildConfig
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.paste.PasteProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

class ReportIssueDialog : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_LOG_CONTENT = "log_content"
        private const val ARG_LOG_TYPE = "log_type"
        private const val MAX_CONTENT_BYTES = 100 * 1024 // 100 KB

        fun create(logContent: String, logType: String): ReportIssueDialog {
            return ReportIssueDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_LOG_CONTENT, logContent)
                    putString(ARG_LOG_TYPE, logType)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val logContent = arguments?.getString(ARG_LOG_CONTENT) ?: ""
        val logType = arguments?.getString(ARG_LOG_TYPE) ?: "app"

        val app = requireActivity().application as TabSSHApplication
        val prefs = app.preferencesManager

        val serviceLabel = when (prefs.getPasteService()) {
            "lenpaste" -> "Lenpaste at ${prefs.getPasteLenpasteUrl()}"
            "stikked"  -> "Stikked at ${prefs.getPasteStikkedUrl()}"
            "pastebin" -> "pastebin.com"
            else       -> "MicroBin at ${prefs.getPasteMicrobinUrl()}"
        }

        val dp8 = (8 * ctx.resources.displayMetrics.density).toInt()
        val dp16 = dp8 * 2

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp16)
        }

        val titleView = TextView(ctx).apply {
            text = "Report Issue"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layout.addView(titleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp8 })

        val serviceInfoView = TextView(ctx).apply {
            text = "Will upload to: $serviceLabel"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
        }
        layout.addView(serviceInfoView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp16 })

        val titleEdit = EditText(ctx).apply {
            hint = "Brief summary of the problem"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            setSingleLine(true)
        }
        layout.addView(titleEdit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp8 })

        val descriptionEdit = EditText(ctx).apply {
            hint = "Steps to reproduce, what you expected vs what happened..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            maxLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            isVerticalScrollBarEnabled = true
            setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET)
        }
        layout.addView(descriptionEdit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp16 })

        val progressBar = ProgressBar(ctx).apply {
            visibility = View.INVISIBLE
        }
        layout.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also {
            it.gravity = android.view.Gravity.CENTER_HORIZONTAL
            it.bottomMargin = dp8
        })

        val uploadButton = MaterialButton(ctx).apply {
            text = "Upload & Open Issue"
        }
        layout.addView(uploadButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        uploadButton.setOnClickListener {
            val issueTitle = titleEdit.text.toString().trim()
            val description = descriptionEdit.text.toString().trim()

            if (issueTitle.isBlank()) {
                Toast.makeText(ctx, "Please enter an issue title", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (description.isBlank()) {
                Toast.makeText(ctx, "Please describe the issue", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            uploadButton.visibility = View.GONE
            progressBar.visibility = View.VISIBLE

            lifecycleScope.launch {
                try {
                    val pasteUrl = withContext(Dispatchers.IO) {
                        val content = buildString {
                            val rawContent = if (logType == "debug") {
                                Logger.sanitize(logContent)
                            } else {
                                logContent
                            }
                            // Trim to 100 KB max
                            val bytes = rawContent.toByteArray(Charsets.UTF_8)
                            if (bytes.size > MAX_CONTENT_BYTES) {
                                append(String(bytes, 0, MAX_CONTENT_BYTES, Charsets.UTF_8))
                            } else {
                                append(rawContent)
                            }
                        }
                        PasteProviderFactory.create(prefs).upload(issueTitle, content)
                    }

                    val deviceInfo = buildString {
                        appendLine("- App: TabSSH ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                        append("- Log type: $logType")
                    }

                    val issueBody = buildString {
                        appendLine("## Description")
                        appendLine(description)
                        appendLine()
                        appendLine("## Log")
                        appendLine(pasteUrl)
                        appendLine()
                        appendLine("## Environment")
                        append(deviceInfo)
                    }

                    val githubUrl = "https://github.com/tabssh/android/issues/new" +
                            "?title=${Uri.encode(issueTitle)}" +
                            "&body=${Uri.encode(issueBody)}" +
                            "&labels=bug"

                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                    dismiss()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.INVISIBLE
                        uploadButton.visibility = View.VISIBLE
                        Toast.makeText(ctx, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        return layout
    }
}
