@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
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
            var profiles by remember(revision) { mutableStateOf(store.all()) }
            var editing by rememberSaveable { mutableStateOf(false) }
            val scroll = rememberScrollState()
            var viewport by remember { mutableStateOf<Rect?>(null) }
            var installedImages by remember { mutableStateOf(emptyMap<String, Boolean>()) }
            LaunchedEffect(profiles, revision, imageInstallations) {
                installedImages = withContext(Dispatchers.IO) {
                    val distributions = store.environments().associate { it.storage.id to it.distribution?.avfDistribution }
                    profiles.filter { it.runtime == SessionRuntime.AVF }.map { it.storageId }.distinct()
                        .associateWith { storageId -> dev.terminal.image.AvfImageVerifier.hasInstalledImage(
                            dev.terminal.runtime.session.SessionStorage.root(filesDir, SessionRuntime.AVF, storageId).toPath(),
                            distributions[storageId]) }
                }
            }
            val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            val editLabel = stringResource(if (editing) R.string.toolbar_done else R.string.toolbar_edit)
            SettingsPageScaffold(stringResource(R.string.profiles), { finish() }, floatingActionButton = {
                if (profiles.isNotEmpty()) ExtendedFloatingActionButton(onClick = { editing = !editing },
                    modifier = Modifier.semantics { contentDescription = editLabel },
                    icon = {
                        AnimatedContent(editing, transitionSpec = {
                            fadeIn(effects) togetherWith fadeOut(effects)
                        }, label = "profileEditIcon") { edit ->
                            Icon(painterResource(if (edit) R.drawable.ic_material_symbol_check_circle_24 else R.drawable.ic_toolbar_edit), null)
                        }
                    }, text = { Text(editLabel) })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).onGloballyPositioned { viewport = it.boundsInRoot() }
                    .verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(if (editing) R.string.profiles_edit_summary else R.string.profiles_summary), modifier = Modifier.padding(horizontal = 4.dp),
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
                    if (profiles.isNotEmpty()) Column(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.profile_list), Modifier.padding(start = 16.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        ProfileReorderList(profiles, editing, installedImages, scroll, { viewport }, onReorder = { ids ->
                            store.reorder(ids)
                            profiles = store.all()
                        }, onEdit = ::edit)
                    }
                }
            }
        }
    }
    private fun edit(profile: SessionProfile) {
        startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("profileId", profile.id))
    }
}
