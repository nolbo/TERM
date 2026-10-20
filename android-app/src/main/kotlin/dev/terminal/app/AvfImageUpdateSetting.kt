package dev.terminal.app

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import dev.terminal.image.AvfImageRelease
import dev.terminal.image.AvfImageUpdateChecker
import dev.terminal.image.AvfImageUpdateKind
import dev.terminal.image.AvfImageUpdateResult
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable internal fun AvfImageUpdateSetting(
    root: Path,
    release: AvfImageRelease?,
    revision: Int,
    working: Boolean,
    canManage: Boolean,
    onUpdate: (String) -> Unit,
    checkUpdate: (Path, AvfImageRelease, Path, () -> Boolean) -> AvfImageUpdateResult = { image, candidate, cache, cancelled ->
        AvfImageUpdateChecker(cancelled).check(image, candidate, cache)
    },
) {
    val context = LocalContext.current
    val approved = avfImageUpdatesApproved(release, BuildConfig.DEBUG)
    var request by rememberSaveable(root.toString()) { mutableIntStateOf(0) }
    var result by remember(root, release, revision, working) { mutableStateOf<AvfImageUpdateResult?>(null) }
    var checking by remember(root, release, revision, working) { mutableStateOf(false) }
    var failed by remember(root, release, revision, working) { mutableStateOf(false) }
    LaunchedEffect(root, release, revision, working, request) {
        result = null
        failed = false
        if (request == 0 || !approved || working) return@LaunchedEffect
        checking = true
        try {
            result = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                checkUpdate(root, requireNotNull(release), context.cacheDir.toPath()) { !job.isActive }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed = true
        } finally {
            checking = false
        }
    }
    EnvironmentActionSetting(R.string.avf_check_updates, R.drawable.ic_material_symbol_download_24,
        enabled = approved && !working && !checking,
        supporting = when {
            release == null -> R.string.avf_image_catalog_missing
            !approved -> R.string.avf_update_approval_required
            checking -> R.string.avf_update_checking
            failed -> R.string.avf_update_check_failed
            result == AvfImageUpdateResult.UpToDate -> R.string.avf_update_current
            result == AvfImageUpdateResult.Unsupported -> R.string.avf_update_unsupported
            result is AvfImageUpdateResult.Available -> R.string.avf_update_available
            else -> R.string.avf_update_check_help
        }, onClick = { request++ })
    (result as? AvfImageUpdateResult.Available)?.let { update ->
        val components = update.kind == AvfImageUpdateKind.COMPONENTS
        EnvironmentActionSetting(if (components) R.string.avf_update_components else R.string.avf_update_image,
            R.drawable.ic_material_symbol_download_24, enabled = approved && canManage && !working && !checking,
            supporting = if (components) R.string.avf_update_components_help else R.string.avf_update_image_help,
            onClick = { onUpdate(if (components) "UPDATE_COMPONENTS" else "UPDATE_IMAGE") })
    }
}
