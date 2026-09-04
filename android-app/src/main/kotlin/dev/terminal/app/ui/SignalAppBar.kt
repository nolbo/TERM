package dev.terminal.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.terminal.app.R

/** Terminal Grid app bar with its own status-bar safe area. */
class SignalAppBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val navigation = iconButton()
    private val titleView = TextView(context).apply {
        setTextAppearance(R.style.Text_TerminalGrid_AppBar)
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
    }
    private val action = iconButton().apply { visibility = View.GONE }
    private val row = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.top_app_bar_height))
        addView(navigation)
        addView(titleView)
        addView(action)
    }

    var title: CharSequence
        get() = titleView.text
        set(value) { titleView.text = value }

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.grid_layer_1))
        addView(row)
        addView(View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.grid_line))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
        })
        context.obtainStyledAttributes(attrs, R.styleable.SignalAppBar).use { values ->
            title = values.getString(R.styleable.SignalAppBar_barTitle).orEmpty()
            val icon = values.getResourceId(R.styleable.SignalAppBar_barNavigationIcon, 0)
            if (icon == 0) {
                navigation.visibility = View.GONE
                row.setPadding(dp(16), 0, dp(4), 0)
            } else {
                navigation.setImageResource(icon)
                navigation.contentDescription = "뒤로"
            }
        }
        val baseTop = paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            view.setPadding(paddingLeft, baseTop + insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, paddingRight, paddingBottom)
            insets
        }
    }

    fun setNavigationOnClickListener(listener: OnClickListener?) = navigation.setOnClickListener(listener)

    fun setAction(@DrawableRes icon: Int, description: CharSequence, listener: OnClickListener) {
        action.setImageResource(icon)
        action.contentDescription = description
        action.visibility = View.VISIBLE
        action.setOnClickListener(listener)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    private fun iconButton() = ImageButton(context).apply {
        background = AppCompatResources.getDrawable(context, R.drawable.bg_grid_icon_button)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        layoutParams = LayoutParams(dp(48), dp(48))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
