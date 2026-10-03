package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.R

/** Renders a service-owned creation request, without a terminal renderer or input connection. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AvfSessionLaunchPane(session: SessionUi, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.widthIn(max = 360.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (session.launchError == null) LoadingIndicator(Modifier.size(64.dp))
                Text(session.title, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(if (session.launchError == null) R.string.avf_session_loading else R.string.avf_session_start_failed),
                    style = MaterialTheme.typography.bodyLarge)
                session.launchError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
