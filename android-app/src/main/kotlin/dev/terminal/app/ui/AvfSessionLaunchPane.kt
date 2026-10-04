package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.terminal.app.R

/** Renders a service-owned creation request, without a terminal renderer or input connection. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AvfSessionLaunchPane(session: SessionUi, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val error = session.launchError
        if (error == null) {
            val loadingDescription = stringResource(R.string.avf_session_loading)
            LoadingIndicator(Modifier.size(64.dp).semantics { contentDescription = loadingDescription })
        } else {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.widthIn(max = 360.dp).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(session.title, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.avf_session_start_failed), style = MaterialTheme.typography.bodyLarge)
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
