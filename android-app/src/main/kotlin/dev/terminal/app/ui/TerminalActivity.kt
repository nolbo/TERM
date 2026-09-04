package dev.terminal.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Shared edge-to-edge contract for every visible activity. */
abstract class TerminalActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TerminalMotion.configureActivity(window)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    protected fun installRootInsets(root: View, includeIme: Boolean = false) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeBottom = if (includeIme) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            view.setPadding(left + bars.left, top, right + bars.right, bottom + maxOf(bars.bottom, imeBottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    protected fun installTopInset(target: View) {
        val left = target.paddingLeft
        val top = target.paddingTop
        val right = target.paddingRight
        val bottom = target.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(left, top + bars.top, right, bottom)
            insets
        }
        ViewCompat.requestApplyInsets(target)
    }

    protected fun startActivityWithMotion(intent: android.content.Intent) = TerminalMotion.startActivity(this, intent)

    protected fun finishWithMotion() = finishAfterTransition()
}
