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

    protected fun compose(content: @androidx.compose.runtime.Composable () -> Unit) = setContent {
        DroidTheme { TerminalHapticFeedbackProvider(content) }
    }
}
