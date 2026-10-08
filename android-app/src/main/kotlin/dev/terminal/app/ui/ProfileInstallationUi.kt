package dev.terminal.app.ui

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.runtime.session.EnvironmentId
import dev.terminal.runtime.session.SessionLaunchSpec
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import org.json.JSONObject

enum class ProfileInstallationState { CHECKING, INSTALLED, MISSING, INSTALLING, UNKNOWN }

data class ProfileInstallationStatus(
    val state: ProfileInstallationState,
    val available: Boolean = false,
    val detail: String = "",
) {
    companion object {
        fun fromJson(encoded: String): ProfileInstallationStatus = runCatching {
            val json = JSONObject(encoded)
            require(json.has("installed"))
            val installed = json.getBoolean("installed")
            val installing = json.optString("state") == "CHECKING" || (!installed && json.optBoolean("busy"))
            ProfileInstallationStatus(
                when {
                    installing -> ProfileInstallationState.INSTALLING
                    installed -> ProfileInstallationState.INSTALLED
                    else -> ProfileInstallationState.MISSING
                }, json.optBoolean("available", installed), json.optString("detail"),
            )
        }.getOrElse { ProfileInstallationStatus(ProfileInstallationState.UNKNOWN) }
    }
}

/** Both entry points badge only confirmed missing installations. */
@Composable
internal fun ProfilePickerIcon(
    profile: SessionProfile,
    installationStatuses: Map<EnvironmentId, ProfileInstallationStatus>,
    tint: Color,
) {
    val state = if (profile.runtime == SessionRuntime.NATIVE) null
        else profile.environmentId?.let { installationStatuses[it]?.state } ?: ProfileInstallationState.CHECKING
    val statusDescription = state?.let {
        stringResource(when (it) {
            ProfileInstallationState.CHECKING -> R.string.profile_installation_checking
            ProfileInstallationState.INSTALLED -> R.string.profile_installation_installed
            ProfileInstallationState.MISSING -> R.string.profile_installation_missing
            ProfileInstallationState.INSTALLING -> R.string.profile_installation_installing
            ProfileInstallationState.UNKNOWN -> R.string.profile_installation_unknown
        })
    }
    val badgeSize = 16.dp
    val symbolSize = 12.dp
    val downloadIcon = if (state == ProfileInstallationState.MISSING)
        ImageVector.vectorResource(R.drawable.ic_material_symbol_download_24) else null
    val downloadPath = downloadIcon?.let { icon ->
        remember(icon) { PathParser().addPathNodes((icon.root[0] as VectorPath).pathData).toPath() }
    }
    Box(Modifier.size(32.dp).semantics {
        if (statusDescription != null) stateDescription = statusDescription
    }, contentAlignment = Alignment.Center) {
        val mask = if (downloadIcon != null && downloadPath != null) Modifier.drawWithCache {
            val symbolPixels = symbolSize.toPx()
            val inset = (badgeSize.toPx() - symbolPixels) / 2
            val left = if (layoutDirection == LayoutDirection.Rtl) inset else size.width - symbolPixels - inset
            val matrix = Matrix().apply {
                setScale(symbolPixels / downloadIcon.viewportWidth, symbolPixels / downloadIcon.viewportHeight)
                postTranslate(left, size.height - symbolPixels - inset)
            }
            val symbolPath = AndroidPath(downloadPath.asAndroidPath()).apply { transform(matrix) }
            val outline = AndroidPath()
            Paint().apply {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = 4.dp.toPx()
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }.getFillPath(symbolPath, outline)
            val cutout = outline.asComposePath()
            onDrawWithContent {
                clipPath(cutout, clipOp = ClipOp.Difference) { this@onDrawWithContent.drawContent() }
            }
        } else Modifier
        Box(Modifier.matchParentSize().then(mask), contentAlignment = Alignment.Center) {
            ProfileIcon(profile.icon, profile.runtime.wireName, tint = tint)
        }
        if (downloadIcon != null) {
            Box(Modifier.align(Alignment.BottomEnd).size(badgeSize),
                contentAlignment = Alignment.Center) {
                Icon(downloadIcon, null, Modifier.size(symbolSize), tint = tint)
            }
        }
    }
}

@Composable
fun ProfileSupportingContent(
    profile: SessionProfile,
    environmentNames: Map<EnvironmentId, String>,
) {
    val runtimeLabel = stringResource(when (profile.runtime) {
        SessionRuntime.NATIVE -> R.string.runtime_native_title
        SessionRuntime.AVF -> R.string.runtime_avf_title
        else -> R.string.runtime_rootless_title
    })
    val environmentName = profile.environmentId?.let { environmentNames[it] }
        ?: stringResource(runtimeTitleResource(profile.runtime.wireName))
    Text(if (environmentName == runtimeLabel) runtimeLabel else "$environmentName · $runtimeLabel")
}

@Composable
fun ProfileInstallationDialog(spec: SessionLaunchSpec, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val software = stringResource(if (spec.runtime == SessionRuntime.AVF) R.string.profile_avf_image
        else runtimeTitleResource(spec.runtime.wireName))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_installation_title)) },
        text = { Text(stringResource(R.string.profile_installation_question,
            spec.executionEnvironment?.name ?: spec.profileName, software) +
            if (spec.runtime == SessionRuntime.AVF) "\n\n" + stringResource(R.string.avf_install_initialization_consent) else "") },
        confirmButton = { TextButton(onClick = onInstall) { Text(stringResource(R.string.install)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
