package io.github.tabssh.ui.activities

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityKeyboardCustomizationBinding
import io.github.tabssh.ui.keyboard.KeyboardKey
import io.github.tabssh.ui.keyboard.MultiRowKeyboardView
import io.github.tabssh.utils.logging.Logger

/**
 * Keyboard layout editor.
 *
 * Layout:
 *   1. Number of rows (top toggle)
 *   2. Preview — each row is an interactive horizontal RecyclerView.
 *      Tap a row to select it (highlighted).  Drag ≡ handle to reorder
 *      keys within a row.  Tap a key to remove it.
 *   3. Available Keys — tap to add to the currently selected row.
 */
class KeyboardCustomizationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeyboardCustomizationBinding
    private lateinit var app: TabSSHApplication

    // Full keyboard layout (rows → keys)
    private val keyboardLayout = mutableListOf<MutableList<KeyboardKey>>()
    private var selectedRowIndex = 0
    private var currentCategory: String = "all"

    // Per-row state, rebuilt whenever rows change
    private val rowAdapters = mutableListOf<KeyAdapter>()
    private val rowItemTouchHelpers = mutableListOf<ItemTouchHelper>()
    private val rowContainers = mutableListOf<LinearLayout>()

    private lateinit var availableKeysAdapter: KeyAdapter

    // Resolved once from theme — primary color used for selected-row highlight
    private val selectedStrokeColor: Int by lazy {
        val tv = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
        tv.data
    }
    private val selectedFillColor: Int by lazy {
        val c = selectedStrokeColor
        Color.argb(28, Color.red(c), Color.green(c), Color.blue(c))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardCustomizationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as TabSSHApplication

        setupToolbar()
        initViews()
        loadCurrentLayout()
        setupListeners()
        updatePreview()
        updateAvailableKeys()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        availableKeysAdapter = KeyAdapter(
            keys = mutableListOf(),
            mode = KeyAdapterMode.ADD,
            onClick = { key -> addKeyToCurrentRow(key) }
        )
        binding.recyclerAvailableKeys.layoutManager = GridLayoutManager(this, 5)
        binding.recyclerAvailableKeys.adapter = availableKeysAdapter
        binding.fabSave.setOnClickListener { saveLayout() }
    }

    private fun loadCurrentLayout() {
        val savedLayout = app.preferencesManager.getKeyboardLayout()
        if (savedLayout.isNotEmpty()) {
            keyboardLayout.clear()
            keyboardLayout.addAll(savedLayout.map { it.toMutableList() })
        } else {
            keyboardLayout.clear()
            keyboardLayout.addAll(getDefaultLayout())
        }

        val rowCount = keyboardLayout.size
        when (rowCount) {
            1 -> binding.toggleRowCount.check(R.id.btn_row_1)
            2 -> binding.toggleRowCount.check(R.id.btn_row_2)
            3 -> binding.toggleRowCount.check(R.id.btn_row_3)
            4 -> binding.toggleRowCount.check(R.id.btn_row_4)
            5 -> binding.toggleRowCount.check(R.id.btn_row_5)
        }

        Logger.d("KeyboardCustomization", "Loaded layout: ${keyboardLayout.size} rows")
    }

    private fun getDefaultLayout(): List<MutableList<KeyboardKey>> =
        MultiRowKeyboardView.getDefaultRowLayouts(3).map { it.toMutableList() }

    private fun setupListeners() {
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

    // ─── Preview (interactive row editor) ───────────────────────────────────

    /**
     * Rebuilds the preview section. Each row becomes a horizontal RecyclerView
     * with its own drag-to-reorder ItemTouchHelper. Rows are tappable to select.
     */
    private fun updatePreview() {
        binding.layoutKeyboardPreview.removeAllViews()
        rowAdapters.clear()
        rowItemTouchHelpers.clear()
        rowContainers.clear()

        val density = resources.displayMetrics.density
        val vertMargin = (4 * density).toInt()
        val innerPad = (6 * density).toInt()
        val minRvHeight = (56 * density).toInt()

        keyboardLayout.forEachIndexed { rowIndex, row ->
            // ── outer container (tappable to select this row) ─────────────
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, vertMargin) }
                setPadding(innerPad, innerPad, innerPad, innerPad)
                setOnClickListener { selectRow(rowIndex) }
            }

            // ── "Row N" header label ───────────────────────────────────────
            val header = TextView(this).apply {
                text = "Row ${rowIndex + 1}"
                textSize = 10f
                setTextColor(Color.WHITE)
                alpha = 0.55f
                setPadding(0, 0, 0, (2 * density).toInt())
            }

            // ── per-row adapter ────────────────────────────────────────────
            val rowAdapter = KeyAdapter(
                keys = row.toMutableList(),
                mode = KeyAdapterMode.REMOVE,
                onClick = { key -> removeKeyFromRow(rowIndex, key) },
                onStartDrag = { vh ->
                    // rowItemTouchHelpers[rowIndex] exists by the time this fires
                    rowItemTouchHelpers.getOrNull(rowIndex)?.startDrag(vh)
                }
            )

            // ── RecyclerView for this row ──────────────────────────────────
            val rowRv = RecyclerView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                minimumHeight = minRvHeight
                layoutManager = LinearLayoutManager(
                    this@KeyboardCustomizationActivity,
                    LinearLayoutManager.HORIZONTAL,
                    false
                )
                adapter = rowAdapter
            }

            // ── ItemTouchHelper (LEFT/RIGHT drag, no long-press) ──────────
            val touchHelper = buildRowTouchHelper(rowIndex)
            touchHelper.attachToRecyclerView(rowRv)

            container.addView(header)
            container.addView(rowRv)
            binding.layoutKeyboardPreview.addView(container)

            rowContainers.add(container)
            rowAdapters.add(rowAdapter)
            rowItemTouchHelpers.add(touchHelper)
        }

        // Restore selection highlight (clamp in case row count shrank)
        selectRow(selectedRowIndex.coerceIn(0, (keyboardLayout.size - 1).coerceAtLeast(0)))
    }

    /**
     * Builds an [ItemTouchHelper] for the row at [rowIndex].
     * Long-press drag is disabled; drag is initiated only via the ≡ handle.
     */
    private fun buildRowTouchHelper(rowIndex: Int): ItemTouchHelper {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = from.bindingAdapterPosition
                val toPos = to.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                rowAdapters.getOrNull(rowIndex)?.moveKey(fromPos, toPos)
                if (rowIndex < keyboardLayout.size) {
                    Collections.swap(keyboardLayout[rowIndex], fromPos, toPos)
                }
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        }
        return ItemTouchHelper(callback)
    }

    /**
     * Highlights [index] as the active row and updates [selectedRowIndex].
     */
    private fun selectRow(index: Int) {
        selectedRowIndex = index
        val density = resources.displayMetrics.density
        rowContainers.forEachIndexed { i, container ->
            container.background = if (i == index) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f * density
                    setColor(selectedFillColor)
                    setStroke((2 * density).toInt(), selectedStrokeColor)
                }
            } else {
                null
            }
        }
    }

    // ─── Row count ──────────────────────────────────────────────────────────

    private fun adjustRowCount(newCount: Int) {
        val currentCount = keyboardLayout.size
        when {
            newCount > currentCount -> repeat(newCount - currentCount) {
                keyboardLayout.add(mutableListOf())
            }
            newCount < currentCount -> repeat(currentCount - newCount) {
                keyboardLayout.removeLastOrNull()
            }
        }
        if (selectedRowIndex >= newCount) selectedRowIndex = newCount - 1
        updatePreview()
    }

    // ─── Key operations ─────────────────────────────────────────────────────

    private fun addKeyToCurrentRow(key: KeyboardKey) {
        if (selectedRowIndex >= keyboardLayout.size) return
        if (keyboardLayout[selectedRowIndex].any { it.id == key.id }) {
            Toast.makeText(this, "Key already in row ${selectedRowIndex + 1}", Toast.LENGTH_SHORT).show()
            return
        }
        keyboardLayout[selectedRowIndex].add(key)
        rowAdapters.getOrNull(selectedRowIndex)?.updateKeys(keyboardLayout[selectedRowIndex])
        Logger.d("KeyboardCustomization", "Added ${key.label} to row ${selectedRowIndex + 1}")
    }

    private fun removeKeyFromRow(rowIndex: Int, key: KeyboardKey) {
        if (rowIndex >= keyboardLayout.size) return
        keyboardLayout[rowIndex].removeIf { it.id == key.id }
        rowAdapters.getOrNull(rowIndex)?.updateKeys(keyboardLayout[rowIndex])
        Logger.d("KeyboardCustomization", "Removed ${key.label} from row ${rowIndex + 1}")
    }

    private fun updateAvailableKeys() {
        val allKeys = KeyboardKey.getAllAvailableKeys()
        val filtered = when (currentCategory) {
            "special"    -> allKeys.filter { it.category == KeyboardKey.KeyCategory.SPECIAL }
            "navigation" -> allKeys.filter { it.id in listOf("HOME", "END", "PGUP", "PGDN", "UP", "DOWN", "LEFT", "RIGHT") }
            "function"   -> allKeys.filter { it.category == KeyboardKey.KeyCategory.FUNCTION }
            "symbols"    -> allKeys.filter { it.category == KeyboardKey.KeyCategory.SYMBOL }
            "modifiers"  -> allKeys.filter {
                it.category == KeyboardKey.KeyCategory.MODIFIER ||
                it.category == KeyboardKey.KeyCategory.ACTION
            }
            else -> allKeys
        }
        availableKeysAdapter.updateKeys(filtered.toMutableList())
    }

    // ─── Save ────────────────────────────────────────────────────────────────

    private fun saveLayout() {
        try {
            app.preferencesManager.setKeyboardLayout(keyboardLayout)
            app.preferencesManager.setKeyboardRowCount(keyboardLayout.size)
            Toast.makeText(this, "Keyboard layout saved (${keyboardLayout.size} rows)", Toast.LENGTH_SHORT).show()
            Logger.i("KeyboardCustomization", "Saved layout: ${keyboardLayout.size} rows")
            finish()
        } catch (e: Exception) {
            Logger.e("KeyboardCustomization", "Failed to save layout", e)
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ─── Key adapter ─────────────────────────────────────────────────────────

    private inner class KeyAdapter(
        private var keys: MutableList<KeyboardKey>,
        private val mode: KeyAdapterMode,
        private val onClick: (KeyboardKey) -> Unit,
        private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
    ) : RecyclerView.Adapter<KeyAdapter.KeyViewHolder>() {

        fun updateKeys(newKeys: List<KeyboardKey>) {
            keys = newKeys.toMutableList()
            notifyDataSetChanged()
        }

        fun moveKey(from: Int, to: Int) {
            Collections.swap(keys, from, to)
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeyViewHolder {
            val density = resources.displayMetrics.density
            val minH = (44 * density).toInt()

            return if (mode == KeyAdapterMode.REMOVE) {
                // Preview rows: drag handle + key label
                val container = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = minH
                    setBackgroundResource(R.drawable.keyboard_key_background)
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.WRAP_CONTENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(4, 4, 4, 4) }
                }
                val handle = ImageView(parent.context).apply {
                    setImageResource(R.drawable.ic_sort)
                    val p = (6 * density).toInt()
                    setPadding(p, p, p / 2, p)
                    contentDescription = "Drag to reorder"
                    alpha = 0.55f
                }
                val label = TextView(parent.context).apply {
                    textSize = 14f
                    gravity = Gravity.CENTER
                    minWidth = (44 * density).toInt()
                    val p = (8 * density).toInt()
                    setPadding(p / 2, p, p, p)
                }
                container.addView(handle)
                container.addView(label)
                KeyViewHolder(container, label, handle)
            } else {
                // Available keys grid: plain chip, no handle
                val view = TextView(parent.context).apply {
                    textSize = 14f
                    gravity = Gravity.CENTER
                    minWidth = (56 * density).toInt()
                    minHeight = minH
                    setPadding(12, 8, 12, 8)
                    setBackgroundResource(R.drawable.keyboard_key_background)
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.WRAP_CONTENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(4, 4, 4, 4) }
                }
                KeyViewHolder(view, view, null)
            }
        }

        override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
            holder.bind(keys[position])
        }

        override fun getItemCount() = keys.size

        inner class KeyViewHolder(
            root: View,
            private val label: TextView,
            private val dragHandle: View?
        ) : RecyclerView.ViewHolder(root) {

            @Suppress("ClickableViewAccessibility")
            fun bind(key: KeyboardKey) {
                label.text = key.label
                label.setTextColor(getKeyTextColor(key))
                itemView.setOnClickListener { onClick(key) }

                // Touch the ≡ handle to start drag immediately — no long press.
                // Returning true on ACTION_DOWN consumes the event so the
                // horizontal RecyclerView scroll detector cannot steal the touch
                // stream before ItemTouchHelper.startDrag() takes ownership.
                dragHandle?.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag?.invoke(this)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    private enum class KeyAdapterMode { ADD, REMOVE }

    private fun getKeyTextColor(key: KeyboardKey): Int = when (key.category) {
        KeyboardKey.KeyCategory.MODIFIER -> Color.parseColor("#2196F3")
        KeyboardKey.KeyCategory.ARROW    -> Color.parseColor("#4CAF50")
        KeyboardKey.KeyCategory.FUNCTION -> Color.parseColor("#FF9800")
        KeyboardKey.KeyCategory.SYMBOL   -> Color.parseColor("#9C27B0")
        KeyboardKey.KeyCategory.ACTION   -> Color.parseColor("#E91E63")
        else -> Color.WHITE
    }
}
