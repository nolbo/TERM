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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.runtimeTitleResource
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
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.profiles_summary))
                    Button(onClick = { startActivity(Intent(this@ProfilesActivity, ProfileEditActivity::class.java)) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.add_profile)) }
                    if (profiles.isEmpty()) Text(stringResource(R.string.profiles_empty))
                    profiles.forEach { profile ->
                        Card(onClick = { edit(profile) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                            Column(Modifier.padding(16.dp)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(runtimeTitleResource(profile.runtime.wireName)))
                                Text(profile.shell, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
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
