@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.terminalListItemShapes
import dev.terminal.app.ui.terminalSettingsListItemColors

@Composable internal fun EnvironmentSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) { ProfileSection(title, content) }
}

@Composable internal fun EnvironmentActionSetting(title: Int, icon: Int?, enabled: Boolean,
    supporting: Int? = null, destructive: Boolean = false, onClick: () -> Unit) {
    ListItem(onClick = onClick, enabled = enabled,
        supportingContent = supporting?.let { { Text(stringResource(it)) } },
        leadingContent = icon?.let { iconResource -> {
            Icon(painterResource(iconResource), contentDescription = null,
                tint = if (destructive && enabled) MaterialTheme.colorScheme.error else LocalContentColor.current)
        } },
        verticalAlignment = Alignment.CenterVertically,
        shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
        modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(title),
            color = if (destructive && enabled) MaterialTheme.colorScheme.error else LocalContentColor.current)
    }
}
