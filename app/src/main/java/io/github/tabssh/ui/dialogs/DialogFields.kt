package io.github.tabssh.ui.dialogs

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R

/**
 * Factory for Material text fields used by programmatic AlertDialogs.
 * Replaces the bare-EditText pattern app-wide: every prompt gets an outlined
 * TextInputLayout with a floating label, optional helper text, and a
 * visibility toggle on secret fields. Callers pass already-resolved strings
 * so all user-visible text stays in res/values/strings.xml.
 */
object DialogFields {

    /** A vertical, padded field column wrapped in a ScrollView for setView(). */
    class Form(context: Context) {
        val root = ScrollView(context)

        /** The column new fields are appended to. */
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.resources.getDimensionPixelSize(R.dimen.space_lg)
            val padTop = context.resources.getDimensionPixelSize(R.dimen.space_sm)
            setPadding(pad, padTop, pad, 0)
        }

        init {
            root.addView(column)
        }
    }

    /** Creates a Form container; add fields with the functions below. */
    fun form(context: Context): Form = Form(context)

    /**
     * Appends an outlined single-line text field to [form] and returns its
     * edit view. [inputType] defaults to plain text; pass e.g.
     * InputType.TYPE_CLASS_NUMBER for numeric prompts.
     */
    fun addText(
        form: Form,
        hint: CharSequence,
        initial: CharSequence? = null,
        helper: CharSequence? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        monospace: Boolean = false
    ): TextInputEditText = addField(form, hint, initial, helper, inputType,
        singleLine = true, endIcon = TextInputLayout.END_ICON_NONE, monospace = monospace)

    /**
     * Appends an outlined password/token field with a visibility toggle.
     * Use for every secret prompt so the value is masked by default.
     */
    fun addSecret(
        form: Form,
        hint: CharSequence,
        initial: CharSequence? = null,
        helper: CharSequence? = null
    ): TextInputEditText = addField(
        form, hint, initial, helper,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        singleLine = true, endIcon = TextInputLayout.END_ICON_PASSWORD_TOGGLE,
        monospace = false
    )

    /**
     * Appends an outlined multi-line field ([minLines]..[maxLines]) for
     * key material, YAML, and other block input.
     */
    fun addMultiline(
        form: Form,
        hint: CharSequence,
        initial: CharSequence? = null,
        helper: CharSequence? = null,
        minLines: Int = 3,
        maxLines: Int = 12,
        monospace: Boolean = false
    ): TextInputEditText {
        val edit = addField(
            form, hint, initial, helper,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            singleLine = false, endIcon = TextInputLayout.END_ICON_NONE,
            monospace = monospace
        )
        edit.minLines = minLines
        edit.maxLines = maxLines
        return edit
    }

    /** The TextInputLayout wrapping [edit], for setting inline errors. */
    fun layoutOf(edit: TextInputEditText): TextInputLayout? =
        generateSequence(edit.parent) { it.parent }
            .filterIsInstance<TextInputLayout>()
            .firstOrNull()

    private fun addField(
        form: Form,
        hint: CharSequence,
        initial: CharSequence?,
        helper: CharSequence?,
        inputType: Int,
        singleLine: Boolean,
        endIcon: Int,
        monospace: Boolean
    ): TextInputEditText {
        val context = form.column.context
        val til = TextInputLayout(
            context, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            this.hint = hint
            helperText = helper
            endIconMode = endIcon
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.bottomMargin = context.resources.getDimensionPixelSize(R.dimen.space_sm)
            }
        }
        val edit = TextInputEditText(til.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            this.inputType = inputType
            if (singleLine) setSingleLine(true)
            if (monospace) typeface = Typeface.MONOSPACE
            if (initial != null) setText(initial)
        }
        til.addView(edit)
        form.column.addView(til)
        return edit
    }
}
