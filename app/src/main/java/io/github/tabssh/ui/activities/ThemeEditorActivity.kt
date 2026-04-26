package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.setMargins
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.themes.definitions.BuiltInThemes
import io.github.tabssh.themes.definitions.ImportThemeResult
import io.github.tabssh.themes.definitions.Theme
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Wave 2.4 — In-app theme editor.
 *
 * Pick a base theme to clone, tweak any of {background, foreground, cursor,
 * selection, 16 ANSI colors} via hex input + swatch chip, watch the live
 * preview update in real time, then save as a new custom theme.
 *
 * No 3rd-party color picker dependency: each color row is a hex EditText
 * (#AARRGGBB / #RRGGBB / RRGGBB accepted) plus a clickable swatch that
 * opens an HSV slider dialog. This keeps the APK lean — a colour picker
 * lib was already considered overkill for one editor.
 */
class ThemeEditorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ThemeEditor"
        const val EXTRA_BASE_THEME_ID = "base_theme_id"
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

        fun createIntent(context: Context, baseThemeId: String? = null): Intent =
            Intent(context, ThemeEditorActivity::class.java).apply {
                if (baseThemeId != null) putExtra(EXTRA_BASE_THEME_ID, baseThemeId)
            }
    }

    private lateinit var app: TabSSHApplication

    // Working theme state. We rebuild Theme on save.
    private var background: Int = 0xFF000000.toInt()
    private var foreground: Int = 0xFFFFFFFF.toInt()
    private var cursor: Int = 0xFFFFFFFF.toInt()
    private var selection: Int = 0xFF666666.toInt()
    private val ansi: IntArray = IntArray(16)
    private var isDark: Boolean = true
    private var nameInput: EditText? = null

    private val swatches = mutableMapOf<String, View>()
    private val previewText: TextView by lazy { TextView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication

        seedFromBase(intent.getStringExtra(EXTRA_BASE_THEME_ID))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        val toolbar = Toolbar(this).apply {
            title = "Theme Editor"
            setBackgroundResource(io.github.tabssh.R.color.primary_500)
            setTitleTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12))
        }
        scroll.addView(content)
        root.addView(scroll)

        // ── Base theme picker ─────────────────────────────────────────
        content.addView(sectionHeader("Base"))
        val baseBtn = Button(this).apply { text = "Pick base theme…" }
        baseBtn.setOnClickListener { showBasePicker { id -> seedFromBase(id); refreshAllSwatches(); refreshPreview() } }
        content.addView(baseBtn)

        // ── Name ───────────────────────────────────────────────────────
        content.addView(sectionHeader("Name"))
        nameInput = EditText(this).apply {
            hint = "My Custom Theme"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        content.addView(nameInput)

        // ── Live preview ───────────────────────────────────────────────
        content.addView(sectionHeader("Preview"))
        previewText.apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(12))
            gravity = Gravity.START or Gravity.TOP
        }
        content.addView(previewText)

        // ── Core colours ───────────────────────────────────────────────
        content.addView(sectionHeader("Core colors"))
        content.addView(colorRow("Background", "background"))
        content.addView(colorRow("Foreground", "foreground"))
        content.addView(colorRow("Cursor", "cursor"))
        content.addView(colorRow("Selection", "selection"))

        // ── ANSI 0-7 ───────────────────────────────────────────────────
        content.addView(sectionHeader("ANSI normal (0-7)"))
        val ansiNames = arrayOf("Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White")
        for (i in 0..7) content.addView(colorRow("$i • ${ansiNames[i]}", "ansi$i"))

        // ── ANSI 8-15 ──────────────────────────────────────────────────
        content.addView(sectionHeader("ANSI bright (8-15)"))
        for (i in 8..15) content.addView(colorRow("$i • ${ansiNames[i - 8]}", "ansi$i"))

        // ── Save bar ───────────────────────────────────────────────────
        val saveBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12))
        }
        val saveBtn = Button(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        saveBtn.setOnClickListener { saveTheme() }
        saveBar.addView(saveBtn)
        root.addView(saveBar)

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        refreshPreview()
    }

    private fun seedFromBase(baseId: String?) {
        val base = baseId?.let { id -> BuiltInThemes.getAllThemes().find { it.id == id } }
            ?: BuiltInThemes.getAllThemes().firstOrNull { it.id == "dracula" }
            ?: BuiltInThemes.getAllThemes().first()
        background = base.background
        foreground = base.foreground
        cursor = base.cursor
        selection = base.selection
        for (i in 0..15) ansi[i] = base.ansiColors.getOrNull(i) ?: 0xFFFFFFFF.toInt()
        isDark = base.isDark
        nameInput?.setText("${base.name} (custom)")
    }

    private fun showBasePicker(onPicked: (String) -> Unit) {
        val themes = BuiltInThemes.getAllThemes()
        val labels = themes.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Base theme")
            .setItems(labels) { _, which -> onPicked(themes[which].id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun colorRow(label: String, key: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val labelView = TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f)
        }
        row.addView(labelView)

        val hexEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(toHex(getColor(key)))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 3f)
        }
        row.addView(hexEdit)

        val swatch = View(this).apply {
            val lp = LinearLayout.LayoutParams(dp(36), dp(36))
            lp.setMargins(dp(8))
            layoutParams = lp
            background = makeSwatch(getColor(key))
        }
        row.addView(swatch)
        swatches[key] = swatch

        hexEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                parseHex(s?.toString().orEmpty())?.let { c ->
                    setColor(key, c)
                    swatch.background = makeSwatch(c)
                    refreshPreview()
                }
            }
        })
        swatch.setOnClickListener { showHsvDialog(getColor(key)) { c ->
            setColor(key, c)
            hexEdit.setText(toHex(c))
            swatch.background = makeSwatch(c)
            refreshPreview()
        } }
        return row
    }

    private fun showHsvDialog(initial: Int, onPick: (Int) -> Unit) {
        // Simple HSV sliders. Three SeekBars for H/S/V plus a preview swatch.
        val hsv = FloatArray(3); Color.colorToHSV(initial, hsv)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        val preview = View(this).apply {
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(48))
            layoutParams = lp
            background = makeSwatch(initial)
        }
        layout.addView(preview)

        fun mkBar(max: Int, value: Float): android.widget.SeekBar {
            return android.widget.SeekBar(this).apply {
                this.max = max
                progress = (value * max).toInt().coerceIn(0, max)
            }
        }
        val hueBar = mkBar(360, hsv[0] / 360f)
        val satBar = mkBar(100, hsv[1])
        val valBar = mkBar(100, hsv[2])
        listOf("Hue" to hueBar, "Saturation" to satBar, "Value" to valBar).forEach { (label, bar) ->
            layout.addView(TextView(this).apply { text = label })
            layout.addView(bar)
        }
        val updatePreview = {
            hsv[0] = hueBar.progress.toFloat()
            hsv[1] = satBar.progress / 100f
            hsv[2] = valBar.progress / 100f
            preview.background = makeSwatch(Color.HSVToColor(hsv))
        }
        listOf(hueBar, satBar, valBar).forEach { bar ->
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: android.widget.SeekBar?, p: Int, fromUser: Boolean) { updatePreview() }
                override fun onStartTrackingTouch(b: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(b: android.widget.SeekBar?) {}
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Pick color")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                hsv[0] = hueBar.progress.toFloat()
                hsv[1] = satBar.progress / 100f
                hsv[2] = valBar.progress / 100f
                onPick(Color.HSVToColor(hsv))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getColor(key: String): Int = when (key) {
        "background" -> background
        "foreground" -> foreground
        "cursor" -> cursor
        "selection" -> selection
        else -> if (key.startsWith("ansi")) ansi[key.removePrefix("ansi").toInt()] else 0
    }

    private fun setColor(key: String, c: Int) {
        when (key) {
            "background" -> background = c
            "foreground" -> foreground = c
            "cursor" -> cursor = c
            "selection" -> selection = c
            else -> if (key.startsWith("ansi")) ansi[key.removePrefix("ansi").toInt()] = c
        }
    }

    private fun refreshAllSwatches() {
        for ((key, view) in swatches) view.background = makeSwatch(getColor(key))
    }

    private fun refreshPreview() {
        val sb = StringBuilder()
        sb.appendLine("user@host:~$ ls -la")
        sb.appendLine("drwxr-xr-x  5 user staff  160 Apr 26 10:00 .")
        sb.appendLine("-rw-r--r--  1 user staff 1.2K Apr 26 09:12 README.md")
        sb.appendLine("-rwxr-xr-x  1 user staff  4.0K Apr 26 09:00 build.sh")
        sb.appendLine("user@host:~$ █")
        previewText.text = sb.toString()
        previewText.setBackgroundColor(background)
        previewText.setTextColor(foreground)
    }

    private fun saveTheme() {
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show()
            return
        }
        val theme = Theme(
            id = "",  // ThemeManager.saveCustomTheme assigns
            name = name,
            author = "user",
            isDark = isDark,
            isBuiltIn = false,
            background = background,
            foreground = foreground,
            cursor = cursor,
            selection = selection,
            highlight = selection,
            ansiColors = ansi.copyOf()
        )
        lifecycleScope.launch {
            when (val r = app.themeManager.saveCustomTheme(theme)) {
                is ImportThemeResult.Success -> {
                    Toast.makeText(this@ThemeEditorActivity, "Saved '${r.theme.name}'", Toast.LENGTH_SHORT).show()
                    Logger.i(TAG, "Saved custom theme ${r.theme.id}")
                    finish()
                }
                is ImportThemeResult.Error -> {
                    AlertDialog.Builder(this@ThemeEditorActivity)
                        .setTitle("Save failed")
                        .setMessage(r.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────
    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, dp(12), 0, dp(4))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun makeSwatch(c: Int): GradientDrawable = GradientDrawable().apply {
        setColor(c)
        setStroke(2, 0xFF888888.toInt())
        cornerRadius = 6f
    }

    private fun toHex(c: Int): String =
        String.format("#%08X", c)

    /** Accepts #AARRGGBB, #RRGGBB, RRGGBB. Returns null on parse fail. */
    private fun parseHex(s: String): Int? {
        val t = s.trim().removePrefix("#")
        return try {
            when (t.length) {
                6 -> (0xFF000000.toInt() or t.toLong(16).toInt())
                8 -> t.toLong(16).toInt()
                else -> null
            }
        } catch (_: NumberFormatException) { null }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
