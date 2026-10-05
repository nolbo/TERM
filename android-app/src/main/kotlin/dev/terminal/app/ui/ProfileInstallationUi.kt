package dev.terminal.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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

/** Both entry points show the same status for profiles sharing an Environment. */
@Composable
fun ProfileSupportingContent(
    profile: SessionProfile,
    environmentNames: Map<EnvironmentId, String>,
    installationStatuses: Map<EnvironmentId, ProfileInstallationStatus>,
) {
    val runtimeLabel = stringResource(when (profile.runtime) {
        SessionRuntime.NATIVE -> R.string.runtime_native_title
        SessionRuntime.AVF -> R.string.runtime_avf_title
        else -> R.string.runtime_rootless_title
    })
    val environmentName = profile.environmentId?.let { environmentNames[it] }
        ?: stringResource(runtimeTitleResource(profile.runtime.wireName))
    Column {
        Text(if (environmentName == runtimeLabel) runtimeLabel else "$environmentName · $runtimeLabel")
        if (profile.runtime != SessionRuntime.NATIVE) {
            val state = profile.environmentId?.let { installationStatuses[it]?.state } ?: ProfileInstallationState.CHECKING
            Text(stringResource(when (state) {
                ProfileInstallationState.CHECKING -> R.string.profile_installation_checking
                ProfileInstallationState.INSTALLED -> R.string.profile_installation_installed
                ProfileInstallationState.MISSING -> R.string.profile_installation_missing
                ProfileInstallationState.INSTALLING -> R.string.profile_installation_installing
                ProfileInstallationState.UNKNOWN -> R.string.profile_installation_unknown
            }))
        }
    }
}

@Composable
fun ProfileInstallationDialog(spec: SessionLaunchSpec, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val software = stringResource(if (spec.runtime == SessionRuntime.AVF) R.string.profile_avf_image
        else runtimeTitleResource(spec.runtime.wireName))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_installation_title)) },
        text = { Text(stringResource(R.string.profile_installation_question,
            spec.executionEnvironment?.name ?: spec.profileName, software)) },
        confirmButton = { TextButton(onClick = onInstall) { Text(stringResource(R.string.install)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
