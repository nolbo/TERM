@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import dev.terminal.app.R
import dev.terminal.runtime.image.AvfImageInstallPhase
import dev.terminal.runtime.image.AvfImageInstallSnapshot
import dev.terminal.runtime.image.AvfImageInstallState
import dev.terminal.runtime.image.RuntimeSetupCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun phaseTitle(phase: AvfImageInstallPhase) = when (phase) {
    AvfImageInstallPhase.PREPARING -> R.string.avf_install_preparing
    AvfImageInstallPhase.MANIFEST -> R.string.avf_install_manifest
    AvfImageInstallPhase.DOWNLOAD -> R.string.avf_install_downloading
    AvfImageInstallPhase.VERIFY -> R.string.avf_install_verifying
    AvfImageInstallPhase.INSTALL -> R.string.avf_install_installing
    AvfImageInstallPhase.BOOT -> R.string.avf_install_booting
    AvfImageInstallPhase.APT_REFRESH -> R.string.avf_install_apt_refresh
    AvfImageInstallPhase.UPDATE -> R.string.avf_install_updating
    AvfImageInstallPhase.COMPLETE -> R.string.avf_install_complete
}

/** The dialog and settings render the same persisted snapshot, without owning its work. */
@Composable
internal fun AvfImageInstallProgress(snapshot: AvfImageInstallSnapshot, modifier: Modifier = Modifier,
    onCancel: (suspend (String) -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var cancelling by remember(snapshot.workId) { mutableStateOf(false) }
    var cancelError by remember(snapshot.workId) { mutableStateOf<String?>(null) }
    val stateTitle = when (snapshot.state) {
        AvfImageInstallState.WAITING -> R.string.avf_install_waiting
        AvfImageInstallState.RETRYING -> R.string.avf_install_retrying
        AvfImageInstallState.FAILED -> R.string.avf_install_failed
        AvfImageInstallState.CANCELLED -> R.string.avf_install_cancelled
        else -> phaseTitle(snapshot.phase)
    }
    val downloading = snapshot.phase == AvfImageInstallPhase.DOWNLOAD
    val artifact = if (downloading && snapshot.artifactIndex in 1..5) {
        val label = listOf(R.string.avf_install_kernel, R.string.avf_install_initrd,
            R.string.avf_install_system, R.string.avf_install_home, R.string.avf_install_config)[snapshot.artifactIndex - 1]
        stringResource(R.string.avf_install_artifact, stringResource(label), snapshot.artifactIndex)
    } else null
    val started = snapshot.startedAtElapsedMs
    val updated = snapshot.updatedAtElapsedMs
    val progress = if (started != null && updated != null) RootlessProgressUi(snapshot.phase.name, "AVF", "",
        snapshot.fraction, snapshot.downloadedBytes.takeIf { downloading }, snapshot.totalBytes.takeIf { downloading },
        started, updated, if (snapshot.active) null else updated) else null
    val phases = AvfImageInstallPhase.entries.filter {
        if (snapshot.operation == "install") it != AvfImageInstallPhase.UPDATE else it != AvfImageInstallPhase.APT_REFRESH
    }
    InstallationProgress(
        title = stringResource(R.string.avf_install_title),
        status = stringResource(if (snapshot.active && cancelling) R.string.install_cancelling else stateTitle),
        detail = artifact,
        active = snapshot.active,
        progress = progress,
        steps = installationSteps(phases.map(::phaseTitle), phases.indexOf(snapshot.phase).coerceAtLeast(0),
            active = snapshot.state == AvfImageInstallState.RUNNING,
            succeeded = snapshot.state == AvfImageInstallState.SUCCEEDED,
            failed = snapshot.state == AvfImageInstallState.FAILED),
        note = stringResource(if (snapshot.active) R.string.install_background_note
            else if (snapshot.state == AvfImageInstallState.SUCCEEDED) R.string.avf_install_complete_note
            else if (snapshot.state == AvfImageInstallState.CANCELLED) R.string.avf_install_cancelled_note
            else R.string.avf_install_retry_note),
        progressNote = if (downloading) stringResource(R.string.avf_install_download_note) else null,
        error = snapshot.error ?: stringResource(R.string.avf_install_failed).takeIf { snapshot.state == AvfImageInstallState.FAILED },
        cancelling = cancelling,
        cancelError = cancelError,
        onCancel = {
            cancelling = true
            cancelError = null
            scope.launch {
                try {
                    if (onCancel != null) onCancel(snapshot.workId)
                    else RuntimeSetupCoordinator(context).cancelImageInstallation(snapshot.workId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    cancelling = false
                    cancelError = error.message
                }
            }
        },
        modifier = modifier,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun AvfImageInstallationUi(snapshots: List<AvfImageInstallSnapshot>) {
    var visibleId by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(snapshots) {
        if (visibleId == null) visibleId = snapshots.firstOrNull { it.active && it.workId !in dismissedIds }?.workId
    }
    snapshots.firstOrNull { it.workId == visibleId }?.let { snapshot ->
        fun dismiss() {
            dismissedIds = (dismissedIds + snapshot.workId).distinct()
            visibleId = null
        }
        AvfImageInstallProgress(snapshot, onDismiss = ::dismiss)
    }
}
