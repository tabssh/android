package io.github.tabssh.ui.utils

import android.view.View
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * Inherit / On / Off selector over a [MaterialButtonToggleGroup] laid out from
 * view_tri_state_toggle.xml. Maps the three buttons (in child order) to a
 * Boolean? where null means "inherit the next tier up".
 */
object TriStateToggle {

    private const val INDEX_INHERIT = 0
    private const val INDEX_ON = 1
    private const val INDEX_OFF = 2

    /** Give the buttons unique ids (the layout is included more than once) and stop state restore from fighting [set]. */
    fun prepare(group: MaterialButtonToggleGroup, onChange: (Boolean?) -> Unit) {
        group.isSaveEnabled = false
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.id == View.NO_ID) child.id = View.generateViewId()
            child.isSaveEnabled = false
        }
        group.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) onChange(get(group))
        }
    }

    /** Select the button for [value]; fires the change listener. */
    fun set(group: MaterialButtonToggleGroup, value: Boolean?) {
        val index = when (value) {
            null -> INDEX_INHERIT
            true -> INDEX_ON
            false -> INDEX_OFF
        }
        group.check(group.getChildAt(index).id)
    }

    /** The current selection: null for Inherit. */
    fun get(group: MaterialButtonToggleGroup): Boolean? {
        val checked = group.checkedButtonId
        return when (checked) {
            group.getChildAt(INDEX_ON).id -> true
            group.getChildAt(INDEX_OFF).id -> false
            else -> null
        }
    }
}
