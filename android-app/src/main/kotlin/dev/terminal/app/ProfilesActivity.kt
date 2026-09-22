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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.app.ui.terminalListItemShapes
import dev.terminal.runtime.session.SessionProfile

class ProfilesActivity : TerminalActivity() {
    private var revision by mutableStateOf(0)
    override fun onResume() { super.onResume(); revision++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SessionProfiles(this)
        compose {
            val profiles = remember(revision) { store.all() }
            SettingsPageScaffold(stringResource(R.string.profiles), { finish() }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(R.string.profiles_summary), modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProfileSection(R.string.profiles) {
                        ListItem(onClick = { startActivity(Intent(this@ProfilesActivity, ProfileEditActivity::class.java)) },
                            leadingContent = { Icon(painterResource(R.drawable.ic_material_symbol_add_24), null) },
                            trailingContent = { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null) },
                            shapes = terminalListItemShapes(), colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.add_profile)) }
                    }
                    if (profiles.isEmpty()) Text(stringResource(R.string.profiles_empty))
                    if (profiles.isNotEmpty()) ProfileSection(R.string.profile_list) {
                        profiles.forEach { profile ->
                            ListItem(onClick = { edit(profile) },
                                supportingContent = { Text(stringResource(runtimeTitleResource(profile.runtime.wireName)) + " · " + profile.shell) },
                                trailingContent = { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null) },
                                verticalAlignment = Alignment.CenterVertically,
                                shapes = terminalListItemShapes(), colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
