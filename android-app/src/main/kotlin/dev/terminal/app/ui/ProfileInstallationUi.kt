package dev.terminal.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
    Box(Modifier.size(32.dp).semantics {
        if (statusDescription != null) stateDescription = statusDescription
    }, contentAlignment = Alignment.Center) {
        val mask = if (state == ProfileInstallationState.MISSING) Modifier.drawWithCache {
            val radius = 8.dp.toPx()
            val center = Offset(if (layoutDirection == LayoutDirection.Rtl) radius else size.width - radius,
                size.height - radius)
            val cutout = Path().apply { addOval(Rect(center, radius)) }
            onDrawWithContent {
                clipPath(cutout, clipOp = ClipOp.Difference) { this@onDrawWithContent.drawContent() }
            }
        } else Modifier
        Box(Modifier.matchParentSize().then(mask), contentAlignment = Alignment.Center) {
            ProfileIcon(profile.icon, profile.runtime.wireName, tint = tint)
        }
        if (state == ProfileInstallationState.MISSING) {
            Box(Modifier.align(Alignment.BottomEnd).size(16.dp),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_material_symbol_download_24), null,
                    Modifier.size(12.dp), tint = tint)
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
