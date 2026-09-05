package dev.terminal.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Shared edge-to-edge contract for every visible activity. */
abstract class TerminalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    protected fun compose(content: @androidx.compose.runtime.Composable () -> Unit) = setContent { DroidTheme { content() } }
}
