package io.github.tabssh.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.BuildConfig
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.paste.PasteProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet that lets the user:
 *  1. Pick a paste service (overrides the setting for this upload only)
 *  2. Create Paste — upload content and display the URL to copy/share
 *  3. Open Issue  — upload + open a pre-filled GitHub issue in the browser
 *
 * Call [create] to attach log content, or [createStandalone] if you want
 * to paste arbitrary text (no pre-loaded log).
 */
class ReportIssueDialog : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_LOG_CONTENT = "log_content"
        private const val ARG_LOG_TYPE    = "log_type"
        private const val MAX_CONTENT_BYTES = 100 * 1024 // 100 KB

        private val SERVICE_IDS     = listOf("stikked", "microbin", "lenpaste", "pastebin")

        /**
         * Open the dialog pre-loaded with [logContent] (typically a debug or app log).
         * [logType] is "debug", "app", "error", "audit", or "host".
         */
        fun create(logContent: String, logType: String = "app"): ReportIssueDialog =
            ReportIssueDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_LOG_CONTENT, logContent)
                    putString(ARG_LOG_TYPE, logType)
                }
            }

        /** Open with empty content — user can still manually paste anything they want. */
        fun createStandalone(): ReportIssueDialog = create("", "app")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx         = requireContext()
        val logContent  = arguments?.getString(ARG_LOG_CONTENT) ?: ""
        val logType     = arguments?.getString(ARG_LOG_TYPE) ?: "app"

        val app   = requireActivity().application as TabSSHApplication
        val prefs = app.preferencesManager

        // ── dimension helpers ────────────────────────────────────────────────
        val density = ctx.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()

        // ── root layout ──────────────────────────────────────────────────────
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        fun lp(
            w: Int = LinearLayout.LayoutParams.MATCH_PARENT,
            h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
            bottomMargin: Int = dp(12)
        ) = LinearLayout.LayoutParams(w, h).also { it.bottomMargin = bottomMargin }

        // ── heading ──────────────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text = "Create Paste"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        }, lp(bottomMargin = dp(16)))

        // ── service picker ───────────────────────────────────────────────────
        var selectedServiceId = prefs.getPasteService().let {
            if (it in SERVICE_IDS) it else "stikked"
        }

        val serviceLabels = SERVICE_IDS.map { PasteProviderFactory.labelForService(it, prefs) }

        val serviceLayout = TextInputLayout(
            ctx,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "Paste service"
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        val serviceSpinner = AutoCompleteTextView(ctx).apply {
            setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, serviceLabels))
            setText(PasteProviderFactory.labelForService(selectedServiceId, prefs), false)
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                selectedServiceId = SERVICE_IDS[pos]
            }
        }
        serviceLayout.addView(serviceSpinner)
        root.addView(serviceLayout, lp())

        // ── title field ──────────────────────────────────────────────────────
        val titleLayout = TextInputLayout(
            ctx,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply { hint = "Title (optional for paste, required for issue)" }
        val titleEdit = TextInputEditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            setSingleLine(true)
        }
        titleLayout.addView(titleEdit)
        root.addView(titleLayout, lp())

        // ── description field (hidden until Open Issue is tapped) ────────────
        val descLayout = TextInputLayout(
            ctx,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "Describe the issue — steps to reproduce, expected vs actual"
            visibility = View.GONE
        }
        val descEdit = TextInputEditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            maxLines = 7
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        descLayout.addView(descEdit)
        root.addView(descLayout, lp())

        // ── progress ─────────────────────────────────────────────────────────
        val progress = ProgressBar(ctx).apply { visibility = View.GONE }
        root.addView(progress, lp(
            w = LinearLayout.LayoutParams.WRAP_CONTENT,
            bottomMargin = dp(8)
        ).also { it.gravity = android.view.Gravity.CENTER_HORIZONTAL })

        // ── result row (URL + Copy) — hidden until upload succeeds ────────────
        val resultRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility  = View.GONE
        }
        val resultUrl = TextView(ctx).apply {
            textSize = 13f
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val copyBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Copy"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(8) }
        }
        resultRow.addView(resultUrl)
        resultRow.addView(copyBtn)
        root.addView(resultRow, lp(bottomMargin = dp(16)))

        // ── action buttons ───────────────────────────────────────────────────
        val buttonRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        val pasteBtn = MaterialButton(ctx).apply {
            text = "Create Paste"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = dp(8) }
        }
        val issueBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Open Issue"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttonRow.addView(pasteBtn)
        buttonRow.addView(issueBtn)
        root.addView(buttonRow, lp(bottomMargin = 0))

        // ── helpers ───────────────────────────────────────────────────────────

        fun setUploading(uploading: Boolean) {
            progress.visibility = if (uploading) View.VISIBLE else View.GONE
            pasteBtn.isEnabled  = !uploading
            issueBtn.isEnabled  = !uploading
        }

        fun showResult(url: String) {
            resultUrl.text = url
            resultRow.visibility = View.VISIBLE
            copyBtn.setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Paste URL", url))
                Toast.makeText(ctx, "URL copied", Toast.LENGTH_SHORT).show()
            }
        }

        fun preparedContent(): String {
            // Both debug and app logs are sanitized at write time in Logger —
            // no post-hoc full-blob regex pass needed here.
            val bytes = logContent.toByteArray(Charsets.UTF_8)
            return if (bytes.size > MAX_CONTENT_BYTES) {
                String(bytes, 0, MAX_CONTENT_BYTES, Charsets.UTF_8)
            } else {
                logContent
            }
        }

        fun upload(
            onSuccess: (url: String) -> Unit
        ) {
            setUploading(true)
            lifecycleScope.launch {
                try {
                    val title = titleEdit.text.toString().trim().ifBlank { "Log" }
                    val url = withContext(Dispatchers.IO) {
                        val content = preparedContent()
                        PasteProviderFactory
                            .createForService(selectedServiceId, prefs)
                            .upload(title, content)
                    }
                    setUploading(false)
                    showResult(url)
                    onSuccess(url)
                } catch (e: Exception) {
                    setUploading(false)
                    Toast.makeText(ctx, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // ── "Create Paste" ────────────────────────────────────────────────────
        pasteBtn.setOnClickListener {
            upload { /* just show the result row — nothing else to do */ }
        }

        // ── "Open Issue" ──────────────────────────────────────────────────────
        issueBtn.setOnClickListener {
            // First tap: reveal the description field so the user can fill it.
            // Second tap (description already visible): validate and proceed.
            if (descLayout.visibility != View.VISIBLE) {
                descLayout.visibility = View.VISIBLE
                issueBtn.text = "Submit Issue"
                return@setOnClickListener
            }

            val issueTitle = titleEdit.text.toString().trim()
            val description = descEdit.text.toString().trim()
            if (issueTitle.isBlank()) {
                titleLayout.error = "Title is required"
                return@setOnClickListener
            }
            titleLayout.error = null
            if (description.isBlank()) {
                descLayout.error = "Description is required"
                return@setOnClickListener
            }
            descLayout.error = null

            upload { url ->
                val deviceInfo = buildString {
                    appendLine("- App: TabSSH ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("- Commit: ${BuildConfig.GIT_COMMIT_ID}")
                    appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    append("- Log type: $logType")
                }
                val body = buildString {
                    appendLine("## Description")
                    appendLine(description)
                    appendLine()
                    appendLine("## Log")
                    appendLine(url)
                    appendLine()
                    appendLine("## Environment")
                    append(deviceInfo)
                }
                val githubUrl = "https://github.com/tabssh/android/issues/new" +
                        "?title=${Uri.encode(issueTitle)}" +
                        "&body=${Uri.encode(body)}" +
                        "&labels=bug"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                dismiss()
            }
        }

        return root
    }
}
