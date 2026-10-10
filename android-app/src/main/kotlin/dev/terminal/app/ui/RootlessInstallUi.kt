package dev.terminal.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.terminal.app.R

private val rootlessInstallationPhases = listOf(
    R.string.install_stage_prepare to setOf("WAITING", "EXECUTABLE", "STORAGE", "MIGRATE"),
    R.string.install_stage_distribution_download to setOf("DOWNLOAD"),
    R.string.install_stage_distribution_verify to setOf("CHECKSUM"),
    R.string.install_stage_distribution_activate to setOf("EXTRACT", "ACTIVATE"),
    R.string.install_stage_packages to setOf("PACKAGES", "PACKAGE_INDEX", "PACKAGE_RECOVERY", "PACKAGE_PLAN",
        "PACKAGE_DOWNLOAD", "PACKAGE_INSTALL", "PACKAGE_UPGRADE", "PACKAGE_CHECK", "PACKAGE_HOOKS"),
    R.string.install_stage_configure to setOf("CONFIGURE", "LOCALE", "TIMEZONE", "HOME"),
    R.string.install_stage_environment_verify to setOf("VERIFY", "VERIFY_PACKAGES", "VERIFY_COMMANDS", "VERIFY_LOCALE"),
    R.string.install_stage_distribution_complete to setOf("READY"),
)

internal fun rootlessInstallationSteps(phase: String?, active: Boolean,
    failed: Boolean = false, cancelled: Boolean = false): List<InstallationStepUi> {
    // Cancellation does not retain the interrupted phase; never guess where it stopped.
    if (cancelled || phase == "CANCELLED") return emptyList()
    val index = rootlessInstallationPhases.indexOfFirst { phase in it.second }.takeIf { it >= 0 }
    return installationSteps(rootlessInstallationPhases.map { it.first }, index, active,
        succeeded = phase == "READY" && !active && !failed, failed = failed)
}

@Composable
internal fun RootlessInstallCard(
    title: String?,
    status: String,
    detail: String,
    running: Boolean,
    progress: RootlessProgressUi?,
    error: String? = null,
    onCancelInstall: (() -> Unit)? = null,
    cancelling: Boolean = false,
    cancelled: Boolean = false,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val wasCancelled = cancelled || progress?.phase == "CANCELLED"
    val active = running && error == null && !wasCancelled
    InstallationProgressCard(
        title = title,
        status = when {
            active && cancelling -> stringResource(R.string.install_cancelling)
            wasCancelled -> stringResource(R.string.install_cancelled)
            error != null -> stringResource(R.string.rootless_install_failed)
            else -> status
        },
        detail = detail,
        active = active,
        progress = progress,
        steps = rootlessInstallationSteps(progress?.phase, active, failed = error != null, cancelled = wasCancelled),
        note = when {
            active -> stringResource(R.string.rootless_setup_description) + "\n\n" + stringResource(R.string.rootless_install_note)
            wasCancelled -> stringResource(R.string.rootless_install_cancelled_note)
            error != null -> stringResource(R.string.rootless_install_note)
            progress?.phase == "READY" -> stringResource(R.string.rootless_install_complete_note)
            else -> stringResource(R.string.rootless_install_note)
        },
        error = error,
        onCancel = onCancelInstall,
        cancelling = cancelling,
        onRetry = onRetry?.takeIf { error != null || wasCancelled },
        modifier = modifier,
    )
}
