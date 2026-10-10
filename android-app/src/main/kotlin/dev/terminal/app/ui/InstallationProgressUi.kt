@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.R

internal enum class InstallationStepState { COMPLETE, RUNNING, FAILED, PENDING }

internal data class InstallationStepUi(@param:StringRes val title: Int, val state: InstallationStepState)

/** Only a known current stage or a successful result can establish completed stages. */
internal fun installationSteps(titles: List<Int>, current: Int?, active: Boolean,
    succeeded: Boolean = false, failed: Boolean = false): List<InstallationStepUi> {
    val index = current?.takeIf { it in titles.indices }
    if (index == null && !succeeded) return emptyList()
    return titles.mapIndexed { position, title ->
        InstallationStepUi(title, when {
            succeeded || index != null && position < index -> InstallationStepState.COMPLETE
            position == index && failed -> InstallationStepState.FAILED
            position == index && active -> InstallationStepState.RUNNING
            else -> InstallationStepState.PENDING
        })
    }
}

/** Runtime adapters supply observed state and commands; this card never owns installation work. */
@Composable
internal fun InstallationProgressCard(
    title: String?,
    status: String,
    active: Boolean,
    progress: RootlessProgressUi?,
    steps: List<InstallationStepUi>,
    note: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    progressNote: String? = null,
    error: String? = null,
    onCancel: (() -> Unit)? = null,
    cancelling: Boolean = false,
    cancelError: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Card(modifier.fillMaxWidth(),
        shape = if (LocalSettingsGroup.current) SettingsItemShape else MaterialTheme.shapes.extraLarge,
        colors = if (LocalSettingsGroup.current)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        else CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(status, style = MaterialTheme.typography.bodyLarge,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            (error ?: detail)?.takeIf { it.isNotBlank() && it != status }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (progress != null) RootlessProgressIndicator(progress, running = active)
            else if (active) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            progressNote?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // Keep installation controls visible before a long list of stages.
            if (active && onCancel != null) {
                OutlinedButton(onClick = onCancel, enabled = !cancelling,
                    modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(if (cancelling) R.string.install_cancelling else R.string.install_cancel))
                }
            } else if (!active && onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.retry))
                }
            }
            cancelError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            steps.forEachIndexed { index, step ->
                val color = when (step.state) {
                    InstallationStepState.RUNNING -> TerminalStatusBlue
                    InstallationStepState.COMPLETE -> MaterialTheme.colorScheme.primary
                    InstallationStepState.FAILED -> MaterialTheme.colorScheme.error
                    InstallationStepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = .15f)),
                        contentAlignment = Alignment.Center) {
                        Text(when (step.state) {
                            InstallationStepState.COMPLETE -> "✓"
                            InstallationStepState.FAILED -> "!"
                            else -> (index + 1).toString()
                        }, color = color, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(stringResource(step.title), color = color, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Text(stringResource(when (step.state) {
                        InstallationStepState.COMPLETE -> R.string.install_step_done
                        InstallationStepState.RUNNING -> R.string.install_step_running
                        InstallationStepState.FAILED -> R.string.install_step_stopped
                        InstallationStepState.PENDING -> R.string.install_step_pending
                    }), color = color, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun InstallationProgressDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            CompositionLocalProvider(LocalSettingsGroup provides false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { content() }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
internal fun InstallationConfirmationDialog(
    title: String,
    message: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    installLabel: String = stringResource(R.string.install),
    dismissLabel: String = stringResource(R.string.cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        } },
        confirmButton = {
            TextButton(onClick = onInstall, shapes = ButtonDefaults.shapes()) { Text(installLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(dismissLabel) }
        },
    )
}
