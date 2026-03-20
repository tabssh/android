package io.github.tabssh.ui.activities

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityKeyboardCustomizationBinding
import io.github.tabssh.ui.keyboard.KeyboardKey
import io.github.tabssh.utils.logging.Logger

/**
 * Visual keyboard layout editor with multi-row support
 * Allows customizing keys in each row with live preview
 */
class KeyboardCustomizationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeyboardCustomizationBinding
    private lateinit var app: TabSSHApplication

    // Current keyboard layout (rows of keys)
    private val keyboardLayout = mutableListOf<MutableList<KeyboardKey>>()
    private var selectedRowIndex = 0
    private var currentCategory: String = "all"

    // Adapters
    private lateinit var currentRowAdapter: KeyAdapter
    private lateinit var availableKeysAdapter: KeyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.d("KeyboardCustomization", "onCreate started")

        try {
            binding = ActivityKeyboardCustomizationBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.d("KeyboardCustomization", "View binding complete")

            app = application as TabSSHApplication
            Logger.d("KeyboardCustomization", "Got application instance")

            setupToolbar()
            Logger.d("KeyboardCustomization", "Toolbar setup complete")

            initViews()
            Logger.d("KeyboardCustomization", "Views initialized")

            loadCurrentLayout()
            Logger.d("KeyboardCustomization", "Layout loaded: ${keyboardLayout.size} rows")

            setupListeners()
            Logger.d("KeyboardCustomization", "Listeners setup complete")

            updatePreview()
            Logger.d("KeyboardCustomization", "Preview updated")

            updateCurrentRowDisplay()
            Logger.d("KeyboardCustomization", "Current row display updated")

            updateAvailableKeys()
            Logger.d("KeyboardCustomization", "Available keys updated")

            Logger.i("KeyboardCustomization", "Activity created successfully with ${keyboardLayout.size} rows")
        } catch (e: Exception) {
            Logger.e("KeyboardCustomization", "Failed to create activity", e)
            throw e
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        // Setup current row adapter
        currentRowAdapter = KeyAdapter(mutableListOf(), KeyAdapterMode.REMOVE) { key ->
            removeKeyFromCurrentRow(key)
        }
        binding.recyclerCurrentRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerCurrentRow.adapter = currentRowAdapter

        // Setup available keys adapter
        availableKeysAdapter = KeyAdapter(mutableListOf(), KeyAdapterMode.ADD) { key ->
            addKeyToCurrentRow(key)
        }
        binding.recyclerAvailableKeys.layoutManager = GridLayoutManager(this, 5)
        binding.recyclerAvailableKeys.adapter = availableKeysAdapter

        // FAB save
        binding.fabSave.setOnClickListener { saveLayout() }
    }

    private fun loadCurrentLayout() {
        // Load from preferences or use default
        val savedLayout = app.preferencesManager.getKeyboardLayout()
        if (savedLayout.isNotEmpty()) {
            keyboardLayout.clear()
            keyboardLayout.addAll(savedLayout.map { it.toMutableList() })
        } else {
            // Use default layout
            keyboardLayout.clear()
            keyboardLayout.addAll(getDefaultLayout())
        }

        // Set row count toggle
        val rowCount = keyboardLayout.size
        when (rowCount) {
            1 -> binding.toggleRowCount.check(R.id.btn_row_1)
            2 -> binding.toggleRowCount.check(R.id.btn_row_2)
            3 -> binding.toggleRowCount.check(R.id.btn_row_3)
            4 -> binding.toggleRowCount.check(R.id.btn_row_4)
            5 -> binding.toggleRowCount.check(R.id.btn_row_5)
        }

        // Setup row tabs
        updateRowTabs()

        Logger.d("KeyboardCustomization", "Loaded layout: ${keyboardLayout.size} rows")
    }

    private fun getDefaultLayout(): List<MutableList<KeyboardKey>> {
        return listOf(
            mutableListOf(
                KeyboardKey("ESC", "ESC", "\u001B"),
                KeyboardKey("TAB", "TAB", "\t"),
                KeyboardKey("CTL", "CTL", "", KeyboardKey.KeyCategory.MODIFIER),
                KeyboardKey("ALT", "ALT", "", KeyboardKey.KeyCategory.MODIFIER),
                KeyboardKey("FN", "FN", "", KeyboardKey.KeyCategory.MODIFIER),
                KeyboardKey("ENTER", "ENT", "\n"),
                KeyboardKey("TOGGLE", "⌨", "", KeyboardKey.KeyCategory.ACTION)
            ),
            mutableListOf(
                KeyboardKey("HOME", "HOME", "\u001B[H"),
                KeyboardKey("END", "END", "\u001B[F"),
                KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
                KeyboardKey("PGDN", "PGDN", "\u001B[6~"),
                KeyboardKey("UP", "↑", "\u001B[A", KeyboardKey.KeyCategory.ARROW),
                KeyboardKey("DOWN", "↓", "\u001B[B", KeyboardKey.KeyCategory.ARROW),
                KeyboardKey("LEFT", "←", "\u001B[D", KeyboardKey.KeyCategory.ARROW),
                KeyboardKey("RIGHT", "→", "\u001B[C", KeyboardKey.KeyCategory.ARROW)
            ),
            mutableListOf(
                KeyboardKey("SLASH", "/", "/", KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("BACKSLASH", "\\", "\\", KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("PIPE", "|", "|", KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("MINUS", "-", "-", KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("UNDERSCORE", "_", "_", KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("TILDE", "~", "~", KeyboardKey.KeyCategory.SYMBOL),
                KeyboardKey("PASTE", "📋", "", KeyboardKey.KeyCategory.ACTION)
            )
        )
    }

    private fun setupListeners() {
        // Row count toggle
        binding.toggleRowCount.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newRowCount = when (checkedId) {
                    R.id.btn_row_1 -> 1
                    R.id.btn_row_2 -> 2
                    R.id.btn_row_3 -> 3
                    R.id.btn_row_4 -> 4
                    R.id.btn_row_5 -> 5
                    else -> 3
                }
                adjustRowCount(newRowCount)
            }
        }

        // Row tab selection
        binding.tabLayoutRows.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedRowIndex = tab?.position ?: 0
                updateCurrentRowDisplay()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Category chips
        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, checkedIds ->
            currentCategory = when {
                checkedIds.contains(R.id.chip_all) -> "all"
                checkedIds.contains(R.id.chip_special) -> "special"
                checkedIds.contains(R.id.chip_navigation) -> "navigation"
                checkedIds.contains(R.id.chip_function) -> "function"
                checkedIds.contains(R.id.chip_symbols) -> "symbols"
                checkedIds.contains(R.id.chip_modifiers) -> "modifiers"
                else -> "all"
            }
            updateAvailableKeys()
        }
    }

    private fun adjustRowCount(newCount: Int) {
        val currentCount = keyboardLayout.size

        if (newCount > currentCount) {
            // Add empty rows
            repeat(newCount - currentCount) {
                keyboardLayout.add(mutableListOf())
            }
        } else if (newCount < currentCount) {
            // Remove excess rows
            repeat(currentCount - newCount) {
                keyboardLayout.removeLastOrNull()
            }
        }

        // Ensure selected row is valid
        if (selectedRowIndex >= newCount) {
            selectedRowIndex = newCount - 1
        }

        updateRowTabs()
        updatePreview()
        updateCurrentRowDisplay()
    }

    private fun updateRowTabs() {
        binding.tabLayoutRows.removeAllTabs()
        keyboardLayout.forEachIndexed { index, _ ->
            binding.tabLayoutRows.addTab(binding.tabLayoutRows.newTab().setText("Row ${index + 1}"))
        }
        if (binding.tabLayoutRows.tabCount > selectedRowIndex) {
            binding.tabLayoutRows.getTabAt(selectedRowIndex)?.select()
        }
    }

    private fun updatePreview() {
        binding.layoutKeyboardPreview.removeAllViews()

        keyboardLayout.forEach { row ->
            val rowView = HorizontalScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4, 4, 4, 4)
            }

            row.forEach { key ->
                val keyView = createKeyPreview(key)
                rowLayout.addView(keyView)
            }

            rowView.addView(rowLayout)
            binding.layoutKeyboardPreview.addView(rowView)
        }
    }

    private fun createKeyPreview(key: KeyboardKey): View {
        return TextView(this).apply {
            text = key.label
            textSize = 12f
            setTextColor(getKeyTextColor(key))
            setBackgroundResource(R.drawable.keyboard_key_background)
            gravity = Gravity.CENTER
            minWidth = (48 * resources.displayMetrics.density).toInt()
            minHeight = (36 * resources.displayMetrics.density).toInt()
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(2, 2, 2, 2)
            }
        }
    }

    private fun getKeyTextColor(key: KeyboardKey): Int {
        return when (key.category) {
            KeyboardKey.KeyCategory.MODIFIER -> Color.parseColor("#2196F3") // Blue
            KeyboardKey.KeyCategory.ARROW -> Color.parseColor("#4CAF50") // Green
            KeyboardKey.KeyCategory.FUNCTION -> Color.parseColor("#FF9800") // Orange
            KeyboardKey.KeyCategory.SYMBOL -> Color.parseColor("#9C27B0") // Purple
            KeyboardKey.KeyCategory.ACTION -> Color.parseColor("#E91E63") // Pink
            else -> Color.WHITE
        }
    }

    private fun updateCurrentRowDisplay() {
        if (selectedRowIndex < keyboardLayout.size) {
            binding.textCurrentRowLabel.text = "Row ${selectedRowIndex + 1} Keys"
            currentRowAdapter.updateKeys(keyboardLayout[selectedRowIndex])
        }
    }

    private fun updateAvailableKeys() {
        val allKeys = KeyboardKey.getAllAvailableKeys()
        val filteredKeys = when (currentCategory) {
            "special" -> allKeys.filter { it.category == KeyboardKey.KeyCategory.SPECIAL }
            "navigation" -> allKeys.filter { it.id in listOf("HOME", "END", "PGUP", "PGDN", "UP", "DOWN", "LEFT", "RIGHT") }
            "function" -> allKeys.filter { it.category == KeyboardKey.KeyCategory.FUNCTION }
            "symbols" -> allKeys.filter { it.category == KeyboardKey.KeyCategory.SYMBOL }
            "modifiers" -> allKeys.filter { it.category == KeyboardKey.KeyCategory.MODIFIER || it.category == KeyboardKey.KeyCategory.ACTION }
            else -> allKeys
        }
        availableKeysAdapter.updateKeys(filteredKeys.toMutableList())
    }

    private fun addKeyToCurrentRow(key: KeyboardKey) {
        if (selectedRowIndex < keyboardLayout.size) {
            // Check if already in row
            if (keyboardLayout[selectedRowIndex].any { it.id == key.id }) {
                Toast.makeText(this, "Key already in this row", Toast.LENGTH_SHORT).show()
                return
            }

            keyboardLayout[selectedRowIndex].add(key)
            updateCurrentRowDisplay()
            updatePreview()
            Logger.d("KeyboardCustomization", "Added key ${key.label} to row ${selectedRowIndex + 1}")
        }
    }

    private fun removeKeyFromCurrentRow(key: KeyboardKey) {
        if (selectedRowIndex < keyboardLayout.size) {
            keyboardLayout[selectedRowIndex].removeIf { it.id == key.id }
            updateCurrentRowDisplay()
            updatePreview()
            Logger.d("KeyboardCustomization", "Removed key ${key.label} from row ${selectedRowIndex + 1}")
        }
    }

    private fun saveLayout() {
        try {
            Logger.d("KeyboardCustomization", "Saving layout with ${keyboardLayout.size} rows")
            keyboardLayout.forEachIndexed { i, row ->
                Logger.d("KeyboardCustomization", "Row $i: ${row.size} keys - ${row.map { it.label }}")
            }

            app.preferencesManager.setKeyboardLayout(keyboardLayout)
            Logger.d("KeyboardCustomization", "Layout saved to preferences")

            app.preferencesManager.setKeyboardRowCount(keyboardLayout.size)
            Logger.d("KeyboardCustomization", "Row count saved: ${keyboardLayout.size}")

            Toast.makeText(this, "Keyboard layout saved (${keyboardLayout.size} rows)", Toast.LENGTH_SHORT).show()
            Logger.i("KeyboardCustomization", "Layout saved successfully")
            finish()
        } catch (e: Exception) {
            Logger.e("KeyboardCustomization", "Failed to save layout", e)
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Adapter for displaying keys
     */
    private inner class KeyAdapter(
        private var keys: MutableList<KeyboardKey>,
        private val mode: KeyAdapterMode,
        private val onClick: (KeyboardKey) -> Unit
    ) : RecyclerView.Adapter<KeyAdapter.KeyViewHolder>() {

        fun updateKeys(newKeys: List<KeyboardKey>) {
            keys = newKeys.toMutableList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): KeyViewHolder {
            val view = TextView(parent.context).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = (56 * resources.displayMetrics.density).toInt()
                minHeight = (44 * resources.displayMetrics.density).toInt()
                setPadding(12, 8, 12, 8)
                setBackgroundResource(R.drawable.keyboard_key_background)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(4, 4, 4, 4)
                }
            }
            return KeyViewHolder(view)
        }

        override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
            val key = keys[position]
            holder.bind(key)
        }

        override fun getItemCount() = keys.size

        inner class KeyViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
            fun bind(key: KeyboardKey) {
                textView.text = key.label
                textView.setTextColor(getKeyTextColor(key))
                textView.setOnClickListener { onClick(key) }
            }
        }
    }

    private enum class KeyAdapterMode { ADD, REMOVE }
}
