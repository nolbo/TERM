package dev.terminal.app.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import dev.terminal.app.R

/** Labeled free-form field owned by Terminal Grid, including validation state. */
class GridFieldView(
    context: Context,
    title: String,
    private val helper: String,
    suffix: String = "",
) : LinearLayout(context) {
    val input = AppCompatEditText(context).apply {
        background = null
        setTextColor(ContextCompat.getColor(context, R.color.grid_text))
        setHintTextColor(ContextCompat.getColor(context, R.color.grid_text_disabled))
        textSize = 15f
        typeface = android.graphics.Typeface.DEFAULT
        isSingleLine = true
        setPadding(0, 0, 0, 0)
        layoutParams = LayoutParams(0, dp(44), 1f)
    }
    private val inputShell = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = AppCompatResources.getDrawable(context, R.drawable.bg_grid_field)
        setPadding(dp(12), 0, dp(12), 0)
        addView(input)
        if (suffix.isNotEmpty()) addView(TextView(context).apply {
            text = suffix
            setTextAppearance(R.style.Text_TerminalGrid_Caption)
            setPadding(dp(8), 0, 0, 0)
        })
    }
    private val message = TextView(context).apply {
        text = helper
        setTextAppearance(R.style.Text_TerminalGrid_Caption)
        setPadding(dp(2), dp(4), dp(2), 0)
    }

    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = title
            setTextAppearance(R.style.Text_TerminalGrid_Label)
            setPadding(dp(2), 0, 0, dp(6))
        })
        addView(inputShell)
        addView(message)
        input.setOnFocusChangeListener { _, focused -> inputShell.isSelected = focused }
    }

    fun useNumberKeyboard() { input.inputType = InputType.TYPE_CLASS_NUMBER }
    fun useTextKeyboard() { input.inputType = InputType.TYPE_CLASS_TEXT }

    fun setError(error: String?) {
        message.text = error ?: helper
        message.setTextColor(ContextCompat.getColor(context, if (error == null) R.color.grid_text_secondary else R.color.grid_error))
        inputShell.background = AppCompatResources.getDrawable(context, if (error == null) R.drawable.bg_grid_field else R.drawable.bg_grid_field_error)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
