@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.terminal.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import kotlinx.coroutines.launch

/** Own the handle to avoid Material's hover tooltip while retaining sheet gestures. */
@Composable
internal fun SessionBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val handleLabel = stringResource(R.string.session_sheet_handle)
    fun dismissSheet() {
        scope.launch {
            sheetState.hide()
            if (!sheetState.isVisible) onDismissRequest()
        }
    }
    // A non-null Material handle is always wrapped in a hover-triggered TooltipBox.
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, dragHandle = null) {
        Box(Modifier.fillMaxWidth().height(48.dp)
            .clickable(interactionSource = remember { HandleInteractionSource() },
                indication = ripple(enableHoverIndication = false)) {
                when (sheetState.currentValue) {
                    SheetValue.Expanded -> dismissSheet()
                    SheetValue.PartiallyExpanded -> scope.launch { sheetState.expand() }
                    SheetValue.Hidden -> scope.launch { sheetState.show() }
                }
            }.semantics(mergeDescendants = true) {
                contentDescription = handleLabel
                dismiss { dismissSheet(); true }
                if (sheetState.currentValue == SheetValue.PartiallyExpanded) {
                    expand { scope.launch { sheetState.expand() }; true }
                } else if (sheetState.hasPartiallyExpandedState) {
                    collapse { scope.launch { sheetState.partialExpand() }; true }
                }
            }, contentAlignment = Alignment.Center) {
            BottomSheetDefaults.DragHandle()
        }
        content()
    }
}
