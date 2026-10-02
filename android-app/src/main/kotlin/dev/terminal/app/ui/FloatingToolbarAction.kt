package dev.terminal.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FloatingToolbarAction(
    label: String,
    @DrawableRes icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    // Material3 1.5.0-alpha27 can retain the anchor's merged disabled semantics after
    // its IconButton becomes enabled. Recreate the anchor when availability changes.
    key(enabled) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
                PlainTooltip(modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Assertive
                    paneTitle = label
                }) { Text(label) }
            },
            state = rememberTooltipState(),
        ) {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = if (destructive) IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    else IconButtonDefaults.iconButtonColors(),
            ) {
                Icon(painterResource(icon), contentDescription = label)
            }
        }
    }
}
