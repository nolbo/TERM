package dev.terminal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val SettingsItemSpacing = 2.dp
internal val SettingsItemShape = RoundedCornerShape(4.dp)
internal val LocalSettingsGroup = staticCompositionLocalOf { false }

/** Transparent gaps separate row surfaces; clipping preserves the group's outer corners. */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalSettingsGroup provides true) {
        Column(modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge),
            verticalArrangement = Arrangement.spacedBy(SettingsItemSpacing), content = content)
    }
}

@Composable
internal fun settingsItemContainerColor(): Color =
    if (LocalSettingsGroup.current) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent

/** Read-only rows use the same surface without adding click semantics. */
@Composable
internal fun Modifier.settingsItemSurface(): Modifier = if (LocalSettingsGroup.current) {
    clip(SettingsItemShape).background(MaterialTheme.colorScheme.surfaceContainerLow)
} else this
