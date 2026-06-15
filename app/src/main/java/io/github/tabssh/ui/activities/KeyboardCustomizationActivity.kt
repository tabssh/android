package io.github.tabssh.ui.activities

import android.content.ClipData
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.sqrt
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityKeyboardCustomizationBinding
import io.github.tabssh.ui.keyboard.KeyboardKey
import io.github.tabssh.ui.keyboard.MultiRowKeyboardView
import io.github.tabssh.utils.logging.Logger

/**
 * Keyboard layout editor.
 *
 * The entire keyboard is one unified drag surface — no row labels, no
 * separate sections.  The user sees their keyboard exactly as it will
 * appear and interacts with it directly:
 *
 *   • Tap a key to remove it.
 *   • Drag a key in any direction (left/right within a row, up/down
 *     between rows) to reposition it.  Drag starts after the finger
 *     moves past the system touch-slop threshold — no long press.
 *
 * Layout (top → bottom):
 *   1. Number of rows (toggle)
 *   2. Keyboard surface (the full interactive layout)
 *   3. Available keys (tap to add to the currently active row)
 */
class KeyboardCustomizationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeyboardCustomizationBinding
    private lateinit var app: TabSSHApplication

    private val keyboardLayout = mutableListOf<MutableList<KeyboardKey>>()

    /** Row that "Available Keys" adds into — updated automatically on any interaction. */
    private var activeRow = 0
    private var currentCategory = "all"

    private lateinit var availableKeysAdapter: AvailableKeysAdapter
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }

    /** Subtle row-selection tint resolved from the active theme's primary color. */
    private val rowActiveTint: Int by lazy {
        val tv = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
        Color.argb(35, Color.red(tv.data), Color.green(tv.data), Color.blue(tv.data))
    }

    /** Payload carried in the drag localState. */
    private data class DragPayload(val fromRow: Int, val fromCol: Int, val key: KeyboardKey)

    /** Per-key drag gesture tracking — stored in each key view's tag. */
    private class KeyTouchState(var x0: Float = 0f, var y0: Float = 0f, var dragging: Boolean = false)

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardCustomizationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as TabSSHApplication
        setupToolbar()
        initViews()
        loadLayout()
        setupListeners()
        rebuildSurface()
        refreshAvailableKeys()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        availableKeysAdapter = AvailableKeysAdapter { key -> addKey(key) }
        binding.recyclerAvailableKeys.layoutManager = GridLayoutManager(this, 5)
        binding.recyclerAvailableKeys.adapter = availableKeysAdapter
        binding.fabSave.setOnClickListener { saveLayout() }

        // The entire keyboard surface is the drop target.
        binding.layoutKeyboardPreview.setOnDragListener(::handleDragEvent)
    }

    private fun loadLayout() {
        val saved = app.preferencesManager.getKeyboardLayout()
        keyboardLayout.clear()
        keyboardLayout.addAll(
            if (saved.isNotEmpty()) saved.map { it.toMutableList() }
            else MultiRowKeyboardView.getDefaultRowLayouts(3).map { it.toMutableList() }
        )
        when (keyboardLayout.size) {
            1 -> binding.toggleRowCount.check(R.id.btn_row_1)
            2 -> binding.toggleRowCount.check(R.id.btn_row_2)
            3 -> binding.toggleRowCount.check(R.id.btn_row_3)
            4 -> binding.toggleRowCount.check(R.id.btn_row_4)
            5 -> binding.toggleRowCount.check(R.id.btn_row_5)
        }
    }

    private fun setupListeners() {
        binding.toggleRowCount.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val n = when (id) {
                R.id.btn_row_1 -> 1; R.id.btn_row_2 -> 2; R.id.btn_row_3 -> 3
                R.id.btn_row_4 -> 4; R.id.btn_row_5 -> 5; else -> 3
            }
            adjustRowCount(n)
        }
        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, ids ->
            currentCategory = when {
                ids.contains(R.id.chip_all)        -> "all"
                ids.contains(R.id.chip_special)    -> "special"
                ids.contains(R.id.chip_navigation) -> "navigation"
                ids.contains(R.id.chip_function)   -> "function"
                ids.contains(R.id.chip_symbols)    -> "symbols"
                ids.contains(R.id.chip_modifiers)  -> "modifiers"
                else -> "all"
            }
            refreshAvailableKeys()
        }
    }

    // ─── Keyboard surface ─────────────────────────────────────────────────────

    /**
     * Tears down and rebuilds the entire interactive keyboard surface.
     * Called on any structural change (row count, key add/remove, drag drop).
     * Also refreshes the available-keys palette so it stays in sync with
     * whatever is currently placed on the surface.
     */
    private fun rebuildSurface() {
        val surface = binding.layoutKeyboardPreview
        surface.removeAllViews()
        val dp = resources.displayMetrics.density

        keyboardLayout.forEachIndexed { rowIdx, row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (1 * dp).toInt() }
                // Minimal vertical padding so rows read as one continuous keyboard.
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
                setBackgroundColor(if (rowIdx == activeRow) rowActiveTint else Color.TRANSPARENT)
                // Tapping any empty space in a row selects it.
                setOnClickListener { activateRow(rowIdx) }
            }

            if (row.isEmpty()) {
                rowView.addView(makeEmptyRowHint(dp))
            } else {
                row.forEachIndexed { colIdx, key ->
                    rowView.addView(makeKeyChip(key, rowIdx, colIdx, dp))
                }
            }

            surface.addView(rowView)
        }

        // Keep the palette in sync — remove any key that is now on the surface.
        refreshAvailableKeys()
    }

    private fun makeEmptyRowHint(dp: Float) = TextView(this).apply {
        text = "Empty row — tap a key below to add"
        textSize = 11f
        setTextColor(Color.WHITE)
        alpha = 0.3f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (44 * dp).toInt()
        )
    }

    /**
     * Creates one key chip.
     *
     * Tap (finger down + up without significant move) → remove key.
     * Drag (finger moves past touch-slop in any direction) → startDragAndDrop,
     *   which allows the key to be repositioned anywhere on the surface.
     */
    private fun makeKeyChip(key: KeyboardKey, rowIdx: Int, colIdx: Int, dp: Float): View {
        val state = KeyTouchState()
        return TextView(this).apply {
            text = key.label
            textSize = 13f
            setTextColor(keyColor(key))
            setBackgroundResource(R.drawable.keyboard_key_background)
            gravity = Gravity.CENTER
            minWidth = (44 * dp).toInt()
            minHeight = (38 * dp).toInt()
            setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
            // width=0 + weight=1 mirrors how KeyboardRowView sizes keys so the
            // preview shows exactly the proportions the user will see at runtime.
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { setMargins((3 * dp).toInt(), (2 * dp).toInt(), (3 * dp).toInt(), (2 * dp).toInt()) }

            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        state.x0 = ev.rawX; state.y0 = ev.rawY; state.dragging = false
                        activateRow(rowIdx)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!state.dragging) {
                            val dx = ev.rawX - state.x0
                            val dy = ev.rawY - state.y0
                            if (sqrt(dx * dx + dy * dy) > touchSlop) {
                                state.dragging = true
                                v.alpha = 0.25f   // dim the original slot during drag
                                v.startDragAndDrop(
                                    ClipData.newPlainText("key", key.id),
                                    View.DragShadowBuilder(v),
                                    DragPayload(rowIdx, colIdx, key),
                                    0
                                )
                            }
                        }
                        state.dragging   // consume only while dragging
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!state.dragging) removeKey(rowIdx, key)   // tap = remove
                        state.dragging = false
                        v.alpha = 1f
                        false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // Don't restore alpha here if drag is in progress —
                        // the ghost slot should remain dim until the drop lands.
                        if (!state.dragging) v.alpha = 1f
                        state.dragging = false
                        false
                    }
                    else -> false
                }
            }
        }
    }

    // ─── Drag-and-drop ────────────────────────────────────────────────────────

    private fun handleDragEvent(view: View, ev: DragEvent): Boolean {
        return when (ev.action) {
            DragEvent.ACTION_DRAG_STARTED,
            DragEvent.ACTION_DRAG_ENTERED,
            DragEvent.ACTION_DRAG_LOCATION,
            DragEvent.ACTION_DRAG_EXITED -> true

            DragEvent.ACTION_DROP -> {
                val payload = ev.localState as? DragPayload ?: return false
                val (toRow, toCol) = hitTest(ev.x, ev.y)
                if (toRow >= 0) commitDrop(payload, toRow, toCol)
                true
            }

            DragEvent.ACTION_DRAG_ENDED -> {
                // Drop failed (lifted outside surface) — rebuild to restore alpha.
                if (!ev.result) rebuildSurface()
                true
            }

            else -> false
        }
    }

    /**
     * Maps a point in [binding.layoutKeyboardPreview]'s coordinate space to
     * a (rowIndex, colIndex) insertion target.
     *
     * The insertion point is before key[colIdx] when the finger is in the left
     * half of that key, or after the last key when past the row's right edge.
     * If [y] falls between rows it snaps to the nearest.
     */
    private fun hitTest(x: Float, y: Float): Pair<Int, Int> {
        val surface = binding.layoutKeyboardPreview
        var bestRow = -1
        var bestDist = Float.MAX_VALUE

        for (ri in 0 until surface.childCount) {
            val row = surface.getChildAt(ri) ?: continue
            if (ri >= keyboardLayout.size) break

            // Use centre-distance so drags between rows snap cleanly.
            val mid = (row.top + row.bottom) / 2f
            val dist = kotlin.math.abs(y - mid)
            if (dist < bestDist) { bestDist = dist; bestRow = ri }

            // If y is inside this row's bounds prefer it immediately.
            if (y >= row.top && y <= row.bottom) { bestRow = ri; break }
        }

        if (bestRow < 0) return Pair(-1, -1)
        val rowData = keyboardLayout[bestRow]
        if (rowData.isEmpty()) return Pair(bestRow, 0)

        val rowView = surface.getChildAt(bestRow) as? LinearLayout ?: return Pair(bestRow, rowData.size)
        val localX = x - rowView.left

        for (ci in 0 until rowView.childCount) {
            val kv = rowView.getChildAt(ci)
            if (localX <= (kv.left + kv.right) / 2f) return Pair(bestRow, ci)
        }
        return Pair(bestRow, rowData.size)   // past last key → append
    }

    private fun commitDrop(p: DragPayload, toRow: Int, toCol: Int) {
        if (p.fromRow >= keyboardLayout.size) return
        if (p.fromCol >= keyboardLayout[p.fromRow].size) return
        if (toRow >= keyboardLayout.size) return

        val key = keyboardLayout[p.fromRow].removeAt(p.fromCol)
        val insertAt = when {
            p.fromRow == toRow && p.fromCol < toCol ->
                (toCol - 1).coerceAtLeast(0)
            else ->
                toCol.coerceAtMost(keyboardLayout[toRow].size)
        }
        keyboardLayout[toRow].add(insertAt, key)
        activeRow = toRow
        rebuildSurface()
    }

    // ─── Row selection ────────────────────────────────────────────────────────

    private fun activateRow(index: Int) {
        if (activeRow == index) return
        activeRow = index
        val surface = binding.layoutKeyboardPreview
        for (i in 0 until surface.childCount) {
            surface.getChildAt(i)?.setBackgroundColor(
                if (i == index) rowActiveTint else Color.TRANSPARENT
            )
        }
    }

    // ─── Row count ────────────────────────────────────────────────────────────

    private fun adjustRowCount(n: Int) {
        val cur = keyboardLayout.size
        when {
            n > cur -> repeat(n - cur) { keyboardLayout.add(mutableListOf()) }
            n < cur -> repeat(cur - n) { keyboardLayout.removeLastOrNull() }
        }
        activeRow = activeRow.coerceAtMost((keyboardLayout.size - 1).coerceAtLeast(0))
        rebuildSurface()
    }

    // ─── Key operations ──────────────────────────────────────────────────────

    private fun addKey(key: KeyboardKey) {
        if (activeRow >= keyboardLayout.size) return
        // Guard against the key being placed anywhere on the surface already
        // (palette filtering prevents this in normal use, but be defensive).
        if (keyboardLayout.any { row -> row.any { it.id == key.id } }) return
        keyboardLayout[activeRow].add(key)
        rebuildSurface()
    }

    private fun removeKey(rowIdx: Int, key: KeyboardKey) {
        if (rowIdx >= keyboardLayout.size) return
        keyboardLayout[rowIdx].removeIf { it.id == key.id }
        rebuildSurface()
    }

    // ─── Available keys ───────────────────────────────────────────────────────

    private fun refreshAvailableKeys() {
        // Build the set of key IDs already placed anywhere on the surface.
        // Each key can only exist once — once placed it is no longer "available".
        val usedIds = keyboardLayout.flatMapTo(mutableSetOf()) { row -> row.map { it.id } }

        val all = KeyboardKey.getAllAvailableKeys()
        val filtered = when (currentCategory) {
            "special"    -> all.filter { it.category == KeyboardKey.KeyCategory.SPECIAL }
            "navigation" -> all.filter { it.id in listOf("HOME","END","PGUP","PGDN","UP","DOWN","LEFT","RIGHT") }
            "function"   -> all.filter { it.category == KeyboardKey.KeyCategory.FUNCTION }
            "symbols"    -> all.filter { it.category == KeyboardKey.KeyCategory.SYMBOL }
            "modifiers"  -> all.filter {
                it.category == KeyboardKey.KeyCategory.MODIFIER ||
                it.category == KeyboardKey.KeyCategory.ACTION
            }
            else -> all
        }
        // Exclude keys already on the surface from the available pool.
        availableKeysAdapter.setKeys(filtered.filterNot { it.id in usedIds })
    }

    // ─── Save ─────────────────────────────────────────────────────────────────

    private fun saveLayout() {
        try {
            app.preferencesManager.setKeyboardLayout(keyboardLayout)
            app.preferencesManager.setKeyboardRowCount(keyboardLayout.size)
            // Mark as user-customised so future default-layout updates don't
            // overwrite this layout, and record the version it was saved against.
            app.preferencesManager.setKeyboardLayoutCustomized(true)
            app.preferencesManager.setKeyboardLayoutVersion(
                io.github.tabssh.ui.keyboard.MultiRowKeyboardView.CURRENT_DEFAULT_LAYOUT_VERSION
            )
            Toast.makeText(this, "Layout saved (${keyboardLayout.size} rows)", Toast.LENGTH_SHORT).show()
            Logger.i("KeyboardCustomization", "Saved ${keyboardLayout.size} rows")
            finish()
        } catch (e: Exception) {
            Logger.e("KeyboardCustomization", "Save failed", e)
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_keyboard_customization, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem) = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.menu_reset_default -> { confirmResetToDefault(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Confirm and reset the working keyboard layout to the built-in default
     * rows. The change is applied to the editor surface immediately; the user
     * must still tap Save (the FAB) to persist it.
     */
    private fun confirmResetToDefault() {
        AlertDialog.Builder(this)
            .setTitle("Reset to default?")
            .setMessage("Discard the current edits and restore the default keyboard layout. You will still need to tap Save to keep the change.")
            .setPositiveButton("Reset") { _, _ ->
                val rowCount = keyboardLayout.size.coerceIn(1, 5).takeIf { it > 0 } ?: 3
                keyboardLayout.clear()
                keyboardLayout.addAll(
                    MultiRowKeyboardView.getDefaultRowLayouts(rowCount).map { it.toMutableList() }
                )
                when (keyboardLayout.size) {
                    1 -> binding.toggleRowCount.check(R.id.btn_row_1)
                    2 -> binding.toggleRowCount.check(R.id.btn_row_2)
                    3 -> binding.toggleRowCount.check(R.id.btn_row_3)
                    4 -> binding.toggleRowCount.check(R.id.btn_row_4)
                    5 -> binding.toggleRowCount.check(R.id.btn_row_5)
                }
                activeRow = 0
                rebuildSurface()
                refreshAvailableKeys()
                Toast.makeText(this, "Reset to default layout", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Available-keys RecyclerView adapter ──────────────────────────────────

    private inner class AvailableKeysAdapter(
        private val onAdd: (KeyboardKey) -> Unit
    ) : RecyclerView.Adapter<AvailableKeysAdapter.VH>() {

        private var keys = listOf<KeyboardKey>()

        fun setKeys(list: List<KeyboardKey>) {
            val old = keys
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = list.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    old[oldItemPosition].label == list[newItemPosition].label
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    old[oldItemPosition] == list[newItemPosition]
            })
            keys = list
            diff.dispatchUpdatesTo(this)
        }

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val dp = resources.displayMetrics.density
            val tv = TextView(parent.context).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = (52 * dp).toInt()
                minHeight = (40 * dp).toInt()
                setPadding((10 * dp).toInt(), 8, (10 * dp).toInt(), 8)
                setBackgroundResource(R.drawable.keyboard_key_background)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 4, 4, 4) }
            }
            return VH(tv)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val key = keys[pos]
            h.tv.text = key.label
            h.tv.setTextColor(keyColor(key))
            h.tv.setOnClickListener { onAdd(key) }
        }

        override fun getItemCount() = keys.size
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun keyColor(key: KeyboardKey) = when (key.category) {
        KeyboardKey.KeyCategory.MODIFIER -> Color.parseColor("#2196F3")
        KeyboardKey.KeyCategory.ARROW    -> Color.parseColor("#4CAF50")
        KeyboardKey.KeyCategory.FUNCTION -> Color.parseColor("#FF9800")
        KeyboardKey.KeyCategory.SYMBOL   -> Color.parseColor("#9C27B0")
        KeyboardKey.KeyCategory.ACTION   -> Color.parseColor("#E91E63")
        else -> Color.WHITE
    }
}
