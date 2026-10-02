@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.terminal.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Keep Material's handle gestures and semantics, suppressing only its hover indication. */
@Composable
internal fun SessionBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit,
) {
    val contentIndication = LocalIndication.current
    CompositionLocalProvider(LocalIndication provides ripple(enableHoverIndication = false)) {
        ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
            CompositionLocalProvider(LocalIndication provides contentIndication) { content() }
        }
    }
}
