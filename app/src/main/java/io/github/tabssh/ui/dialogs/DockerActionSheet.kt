package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDivider
import io.github.tabssh.R

/**
 * Shared bottom-sheet action menu for the Docker screens — replaces the old
 * long-press AlertDialog lists. Actions render in the order given; the first
 * destructive action is preceded by a divider and destructive rows use the
 * theme error color so dangerous options are visually separated and last.
 */
object DockerActionSheet {

    /** One tappable row: icon, label, destructive styling flag, handler. */
    data class Action(
        @DrawableRes val iconRes: Int,
        val label: CharSequence,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    /** Show the sheet; every row dismisses the sheet before running its action. */
    fun show(
        context: Context,
        title: CharSequence,
        subtitle: CharSequence?,
        actions: List<Action>
    ) {
        val dialog = BottomSheetDialog(context)
        val root = LayoutInflater.from(context)
            .inflate(R.layout.sheet_docker_actions, null, false)
        root.findViewById<TextView>(R.id.text_sheet_title).text = title
        val subtitleView = root.findViewById<TextView>(R.id.text_sheet_subtitle)
        if (subtitle.isNullOrBlank()) {
            subtitleView.visibility = android.view.View.GONE
        } else {
            subtitleView.visibility = android.view.View.VISIBLE
            subtitleView.text = subtitle
        }

        val container = root.findViewById<LinearLayout>(R.id.container_actions)
        val errorColor = MaterialColors.getColor(
            root, com.google.android.material.R.attr.colorError
        )
        var dividerAdded = false
        actions.forEach { action ->
            if (action.destructive && !dividerAdded) {
                container.addView(MaterialDivider(context))
                dividerAdded = true
            }
            val row = LayoutInflater.from(context)
                .inflate(R.layout.item_sheet_action, container, false)
            val icon = row.findViewById<ImageView>(R.id.image_action_icon)
            val label = row.findViewById<TextView>(R.id.text_action_label)
            icon.setImageResource(action.iconRes)
            label.text = action.label
            if (action.destructive) {
                icon.imageTintList = android.content.res.ColorStateList.valueOf(errorColor)
                label.setTextColor(errorColor)
            }
            row.setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
            container.addView(row)
        }

        dialog.setContentView(root)
        dialog.show()
    }
}
