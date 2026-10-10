@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.terminal.app.ProfileIconStore
import dev.terminal.app.R
import dev.terminal.runtime.session.SessionProfileIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileIconSetting(value: String, runtime: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { ProfileIconStore(context) }
    var visible by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(value) }
    var imported by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val removeError = stringResource(R.string.profile_icon_delete_error)
    LaunchedEffect(visible) { if (visible) imported = withContext(Dispatchers.IO) { store.all() } }
    ListItem(onClick = { draft = value; error = null; visible = true },
        trailingContent = { ProfileIcon(value, runtime, tint = TerminalStatusBlue, contentDescription = iconChoiceLabel(value)) },
        verticalAlignment = Alignment.CenterVertically,
        shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.profile_icon)) }
    if (visible) AlertDialog(onDismissRequest = { if (!busy) visible = false },
        title = { Text(stringResource(R.string.profile_icon)) },
        text = {
            val options = SessionProfileIcon.builtIns + imported
            LazyColumn(Modifier.fillMaxWidth()) {
                item { Text(stringResource(R.string.profile_icon_help), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)) }
                items((options.size + 2) / 3) { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.drop(row * 3).take(3).forEach { icon ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                IconChoiceTile(icon, selected = draft == icon, modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy, runtime = runtime, onSelect = { draft = icon })
                                if (SessionProfileIcon.isImage(icon)) {
                                    val deleteLabel = stringResource(R.string.profile_icon_delete)
                                    // Avoid Material 3's child-semantics node when lazy rows detach.
                                    Box(Modifier.size(48.dp).clip(CircleShape)
                                        .clickable(enabled = !busy, role = Role.Button) { pendingRemoval = icon }
                                        .semantics { contentDescription = deleteLabel },
                                        contentAlignment = Alignment.Center) {
                                        Icon(painterResource(R.drawable.ic_material_symbol_delete_24),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        repeat(3 - options.drop(row * 3).take(3).size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { Button(onClick = { onChange(draft); visible = false }, enabled = !busy,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { visible = false }, enabled = !busy,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
    )
    pendingRemoval?.let { icon ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRemoval = null },
            title = { Text(stringResource(R.string.profile_icon_delete)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ProfileIcon(icon, runtime, size = 48.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(stringResource(R.string.profile_icon_delete_message))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true; error = null
                        try {
                            withContext(Dispatchers.IO) { store.removeFromLibrary(icon) }
                            imported = imported.filterNot { it == icon }
                            if (draft == icon) draft = "auto"
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            error = removeError
                        } finally {
                            busy = false
                            pendingRemoval = null
                        }
                    }
                }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.profile_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
