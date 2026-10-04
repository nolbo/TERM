@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.app.ui.terminalSettingsListItemColors
import dev.terminal.app.ui.terminalListItemShapes
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.image.AvfImageInstallSnapshot
import dev.terminal.runtime.image.AvfImageInstallMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfilesActivity : TerminalActivity() {
    private var revision by mutableStateOf(0)
    private var imageInstallations by mutableStateOf(emptyList<AvfImageInstallSnapshot>())
    override fun onResume() { super.onResume(); revision++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AvfImageInstallMonitor(this).observe(this) { imageInstallations = it }
        val store = SessionProfiles(this)
        compose {
            val profiles = remember(revision) { store.all() }
            var installedImages by remember { mutableStateOf(emptyMap<String, Boolean>()) }
            LaunchedEffect(profiles, revision, imageInstallations) {
                installedImages = withContext(Dispatchers.IO) {
                    profiles.filter { it.runtime == SessionRuntime.AVF }.map { it.storageId }.distinct()
                        .associateWith { hasAvfEnvironmentImage(this@ProfilesActivity, it) }
                }
            }
            SettingsPageScaffold(stringResource(R.string.profiles), { finish() }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(R.string.profiles_summary), modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProfileSection(R.string.profiles) {
                        ListItem(onClick = { startActivity(Intent(this@ProfilesActivity, ProfileEditActivity::class.java)) },
                            leadingContent = { Icon(painterResource(R.drawable.ic_material_symbol_add_24), null) },
                            trailingContent = { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null) },
                            shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.add_profile)) }
                    }
                    if (profiles.isEmpty()) Text(stringResource(R.string.profiles_empty))
                    if (profiles.isNotEmpty()) ProfileSection(R.string.profile_list) {
                        profiles.forEach { profile ->
                            ListItem(onClick = { edit(profile) },
                                leadingContent = { dev.terminal.app.ui.ProfileIcon(profile.icon, profile.runtime.wireName) },
                                supportingContent = {
                                    val detail = stringResource(runtimeTitleResource(profile.runtime.wireName)) + " · " + profile.shell
                                    Text(detail + if (profile.runtime == SessionRuntime.AVF) "\n" + stringResource(R.string.avf_profile_image_status,
                                        stringResource(when (installedImages[profile.storageId]) {
                                            true -> R.string.avf_image_installed
                                            false -> R.string.avf_image_not_installed
                                            null -> R.string.avf_image_checking
                                        })) else "")
                                },
                                trailingContent = { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null) },
                                verticalAlignment = Alignment.CenterVertically,
                                shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(profile.name) }
                        }
                    }
                }
            }
        }
    }
    private fun edit(profile: SessionProfile) {
        startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("profileId", profile.id))
    }
}
