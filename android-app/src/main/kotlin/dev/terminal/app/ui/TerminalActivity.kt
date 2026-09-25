package dev.terminal.app.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/** Shared edge-to-edge contract for every visible activity. */
abstract class TerminalActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    protected fun compose(
        systemBarBackground: @androidx.compose.runtime.Composable () -> androidx.compose.ui.graphics.Color? = { null },
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) = setContent {
        DroidTheme(systemBarBackground = systemBarBackground()) { TerminalHapticFeedbackProvider(content) }
    }
}
