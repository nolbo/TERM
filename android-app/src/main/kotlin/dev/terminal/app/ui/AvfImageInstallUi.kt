@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.runtime.image.AvfImageInstallPhase
import dev.terminal.runtime.image.AvfImageInstallSnapshot
import dev.terminal.runtime.image.AvfImageInstallState

private fun phaseTitle(phase: AvfImageInstallPhase) = when (phase) {
    AvfImageInstallPhase.PREPARING -> R.string.avf_install_preparing
    AvfImageInstallPhase.MANIFEST -> R.string.avf_install_manifest
    AvfImageInstallPhase.DOWNLOAD -> R.string.avf_install_downloading
    AvfImageInstallPhase.VERIFY -> R.string.avf_install_verifying
    AvfImageInstallPhase.INSTALL -> R.string.avf_install_installing
    AvfImageInstallPhase.COMPLETE -> R.string.avf_install_complete
}

/** The dialog and settings render the same persisted snapshot, without owning its work. */
@Composable
internal fun AvfImageInstallCard(snapshot: AvfImageInstallSnapshot, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.avf_install_title), style = MaterialTheme.typography.titleMedium)
            val stateTitle = when (snapshot.state) {
                AvfImageInstallState.WAITING -> R.string.avf_install_waiting
                AvfImageInstallState.RETRYING -> R.string.avf_install_retrying
                AvfImageInstallState.FAILED -> R.string.avf_install_failed
                AvfImageInstallState.CANCELLED -> R.string.avf_install_cancelled
                else -> phaseTitle(snapshot.phase)
            }
            Text(stringResource(stateTitle), color = if (snapshot.state == AvfImageInstallState.FAILED)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (snapshot.phase == AvfImageInstallPhase.DOWNLOAD && snapshot.artifactIndex in 1..5) {
                val artifact = listOf(R.string.avf_install_kernel, R.string.avf_install_initrd,
                    R.string.avf_install_system, R.string.avf_install_home, R.string.avf_install_config)[snapshot.artifactIndex - 1]
                Text(stringResource(R.string.avf_install_artifact, stringResource(artifact), snapshot.artifactIndex),
                    style = MaterialTheme.typography.bodySmall)
            }
            val started = snapshot.startedAtElapsedMs
            val updated = snapshot.updatedAtElapsedMs
            if (started != null && updated != null) {
                RootlessProgressIndicator(RootlessProgressUi(snapshot.phase.name, "AVF", "",
                    snapshot.fraction, snapshot.downloadedBytes, snapshot.totalBytes, started, updated,
                    if (snapshot.active) null else updated), running = snapshot.active)
            } else if (snapshot.active) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            for (phase in AvfImageInstallPhase.entries) {
                val complete = snapshot.state == AvfImageInstallState.SUCCEEDED || phase.ordinal < snapshot.phase.ordinal
                val current = phase == snapshot.phase && snapshot.state == AvfImageInstallState.RUNNING
                val failed = phase == snapshot.phase && snapshot.state == AvfImageInstallState.FAILED
                val color = when {
                    failed -> MaterialTheme.colorScheme.error
                    complete || current -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = .15f)),
                        contentAlignment = Alignment.Center) {
                        Text(if (complete) "✓" else if (failed) "!" else (phase.ordinal + 1).toString(), color = color,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    Text(stringResource(phaseTitle(phase)), color = color,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(stringResource(when {
                        complete -> R.string.avf_install_step_done
                        failed -> R.string.avf_install_step_stopped
                        current -> R.string.avf_install_step_running
                        else -> R.string.avf_install_step_pending
                    }), color = color, style = MaterialTheme.typography.labelSmall)
                }
            }
            snapshot.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(if (snapshot.active) R.string.avf_install_background_note
                else if (snapshot.state == AvfImageInstallState.SUCCEEDED) R.string.avf_install_complete_note
                else R.string.avf_install_retry_note), style = MaterialTheme.typography.bodySmall)
            if (snapshot.phase == AvfImageInstallPhase.DOWNLOAD) {
                Text(stringResource(R.string.avf_install_download_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun AvfImageInstallationUi(snapshots: List<AvfImageInstallSnapshot>, modifier: Modifier = Modifier) {
    var visibleId by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val active = snapshots.firstOrNull { it.active }
    LaunchedEffect(snapshots) {
        if (visibleId == null) visibleId = snapshots.firstOrNull { it.active && it.workId !in dismissedIds }?.workId
    }
    if (active != null) {
        TextButton(onClick = { visibleId = active.workId }, modifier = modifier.fillMaxWidth()) {
            Text(stringResource(R.string.avf_install_open_progress))
        }
    }
    snapshots.firstOrNull { it.workId == visibleId }?.let { snapshot ->
        fun dismiss() {
            dismissedIds = (dismissedIds + snapshot.workId).distinct()
            visibleId = null
        }
        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(stringResource(R.string.avf_install_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { AvfImageInstallCard(snapshot) } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = ::dismiss) { Text(stringResource(R.string.close)) } },
        )
    }
}
