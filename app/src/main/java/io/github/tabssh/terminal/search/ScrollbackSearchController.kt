package io.github.tabssh.terminal.search

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import io.github.tabssh.R
import io.github.tabssh.ui.tabs.SSHTab
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the find-in-scrollback overlay bar that floats at the top of the
 * terminal view. Wire it up in [TabTerminalActivity] once the content view
 * is set.
 *
 * Responsibilities:
 *  - Show / dismiss the card overlay.
 *  - Perform case-insensitive (or case-sensitive) text search across the
 *    combined Termux scrollback + visible screen content.
 *  - Drive [TerminalView] to render match highlights and scroll to each hit.
 *  - Provide prev / next navigation and a live match counter.
 *
 * The search is debounced 200 ms so fast typists do not block the UI thread.
 */
class ScrollbackSearchController(
    private val context: Context,
    private val overlayRoot: View,
    private val terminalView: TerminalView,
    private val scope: CoroutineScope,
    private val activeTabProvider: () -> SSHTab?
) {

    // ── Views ────────────────────────────────────────────────────────────────
    private val searchInput   = overlayRoot.findViewById<EditText>(R.id.search_input)
    private val matchCount    = overlayRoot.findViewById<TextView>(R.id.search_match_count)
    private val prevButton    = overlayRoot.findViewById<ImageButton>(R.id.search_prev)
    private val nextButton    = overlayRoot.findViewById<ImageButton>(R.id.search_next)
    private val caseButton    = overlayRoot.findViewById<MaterialButton>(R.id.search_case_toggle)
    private val closeButton   = overlayRoot.findViewById<ImageButton>(R.id.search_close)

    // ── State ────────────────────────────────────────────────────────────────
    private var matches: List<TerminalView.SearchMatch> = emptyList()
    private var currentIndex = -1
    private var caseSensitive = false
    private var debounceJob: Job? = null

    val isVisible: Boolean get() = overlayRoot.visibility == View.VISIBLE

    init {
        closeButton.setOnClickListener { dismiss() }

        prevButton.setOnClickListener { stepMatch(-1) }
        nextButton.setOnClickListener { stepMatch(+1) }

        caseButton.setOnClickListener {
            caseSensitive = !caseSensitive
            updateCaseButtonAppearance()
            runSearch(searchInput.text?.toString().orEmpty())
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(200)
                    runSearch(s?.toString().orEmpty())
                }
            }
        })

        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                stepMatch(+1)
                true
            } else false
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun show() {
        overlayRoot.visibility = View.VISIBLE
        searchInput.requestFocus()
        // Re-run search with any previously typed query so highlights appear
        // immediately if the user dismisses and re-opens.
        val q = searchInput.text?.toString().orEmpty()
        if (q.isNotEmpty()) runSearch(q) else clearResults()
    }

    fun dismiss() {
        overlayRoot.visibility = View.GONE
        terminalView.clearSearchHighlights()
        matches = emptyList()
        currentIndex = -1
        // Return focus to the terminal so keystrokes go to the shell.
        terminalView.requestFocus()
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun stepMatch(delta: Int) {
        if (matches.isEmpty()) return
        currentIndex = (currentIndex + delta).let {
            when {
                it < 0          -> matches.lastIndex
                it > matches.lastIndex -> 0
                else            -> it
            }
        }
        terminalView.scrollToSearchMatch(currentIndex)
        terminalView.setSearchHighlights(matches, currentIndex)
        updateCounter()
    }

    private fun runSearch(query: String) {
        scope.launch {
            if (query.isEmpty()) {
                withContext(Dispatchers.Main) { clearResults() }
                return@launch
            }
            val tab = activeTabProvider()
            val found = withContext(Dispatchers.Default) {
                buildAndSearch(tab, query)
            }
            withContext(Dispatchers.Main) {
                matches = found
                currentIndex = if (found.isEmpty()) -1 else 0
                terminalView.setSearchHighlights(found, currentIndex)
                if (currentIndex >= 0) terminalView.scrollToSearchMatch(currentIndex)
                updateCounter()
            }
        }
    }

    private fun clearResults() {
        matches = emptyList()
        currentIndex = -1
        terminalView.clearSearchHighlights()
        matchCount.visibility = View.GONE
    }

    private fun updateCounter() {
        if (matches.isEmpty()) {
            matchCount.text = "0"
        } else {
            matchCount.text = "${currentIndex + 1} / ${matches.size}"
        }
        matchCount.visibility = View.VISIBLE
    }

    private fun updateCaseButtonAppearance() {
        val color = if (caseSensitive) {
            context.getColor(android.R.color.holo_blue_bright)  // active tint
        } else {
            // Resolved from theme attribute colorOnSurfaceVariant
            val attrs = context.theme.obtainStyledAttributes(
                intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            val c = attrs.getColor(0, android.graphics.Color.GRAY)
            attrs.recycle()
            c
        }
        caseButton.setTextColor(color)
    }

    /**
     * Build the search corpus from the Termux scrollback + visible screen and
     * return all matches as a list of [TerminalView.SearchMatch].
     *
     * Called on a background thread — no UI access.
     *
     * Coordinate system:
     *   scrollbackLines[i] → external row `i - transcriptRows` (negative, oldest at
     *   most negative). screenLines[j] → external row `j` (0 = topmost visible row).
     */
    private fun buildAndSearch(
        tab: SSHTab?,
        query: String
    ): List<TerminalView.SearchMatch> {
        val result = mutableListOf<TerminalView.SearchMatch>()
        if (tab == null) return result

        val bridge = tab.termuxBridge
        val buffer = bridge.getBuffer()

        return try {
            val transcriptRows = buffer?.activeTranscriptRows ?: 0

            // Scrollback lines (oldest → newest = most negative → -1)
            if (transcriptRows > 0) {
                val scrollbackText = bridge.getScrollbackContent()
                val lines = scrollbackText.split('\n')
                lines.forEachIndexed { i, line ->
                    val extRow = i - transcriptRows
                    findInLine(line, query, extRow, result)
                }
            }

            // Visible screen lines (external rows 0 .. rows-1)
            val screenText = bridge.getScreenContent()
            screenText.split('\n').forEachIndexed { i, line ->
                findInLine(line, query, i, result)
            }

            result
        } catch (e: Exception) {
            Logger.w("ScrollbackSearch", "Search failed: ${e.message}")
            result
        }
    }

    private fun findInLine(
        line: String,
        query: String,
        externalRow: Int,
        out: MutableList<TerminalView.SearchMatch>
    ) {
        val haystack = if (caseSensitive) line else line.lowercase()
        val needle   = if (caseSensitive) query else query.lowercase()
        var start = 0
        while (true) {
            val idx = haystack.indexOf(needle, start)
            if (idx < 0) break
            out.add(TerminalView.SearchMatch(externalRow, idx, idx + needle.length))
            start = idx + 1
        }
    }
}
