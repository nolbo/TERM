@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
    var imported by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val importError = stringResource(R.string.profile_icon_import_error)
    LaunchedEffect(visible) { if (visible) imported = withContext(Dispatchers.IO) { store.all() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            try {
                val icon = withContext(Dispatchers.IO) { store.import(uri) }
                imported = (imported + icon).distinct(); draft = icon; visible = true
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                error = importError
            } finally { busy = false }
        }
    }
    ListItem(onClick = { draft = value; error = null; visible = true },
        leadingContent = { ProfileIcon(value, runtime, tint = TerminalStatusBlue) },
        supportingContent = { Text(iconLabel(value)) },
        shapes = terminalListItemShapes(), colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.profile_icon)) }
    if (visible) AlertDialog(onDismissRequest = { if (!busy) visible = false },
        title = { Text(stringResource(R.string.profile_icon)) },
        text = {
            val options = listOf("auto") + SessionProfileIcon.builtIns + imported
            LazyColumn(Modifier.fillMaxWidth()) {
                item { Text(stringResource(R.string.profile_icon_help), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)) }
                items((options.size + 2) / 3) { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.drop(row * 3).take(3).forEach { icon ->
                            Surface(onClick = { draft = icon }, enabled = !busy,
                                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton; selected = draft == icon },
                                shape = MaterialTheme.shapes.medium,
                                border = if (draft == icon) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                color = if (draft == icon) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
                                Column(Modifier.padding(8.dp).heightIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ProfileIcon(icon, runtime, size = 28.dp)
                                    Text(iconLabel(icon), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        repeat(3 - options.drop(row * 3).take(3).size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item {
                    OutlinedButton(onClick = { picker.launch(arrayOf("image/png", "image/webp", "image/jpeg")) },
                        enabled = !busy, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.profile_icon_import))
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { Button(onClick = { onChange(draft); visible = false }, enabled = !busy,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { visible = false }, enabled = !busy,
            shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable private fun iconLabel(id: String): String = stringResource(when (id) {
    "auto" -> R.string.profile_icon_auto
    "native" -> runtimeTitleResource("NATIVE")
    "rootless" -> R.string.runtime_rootless_title
    "debian" -> runtimeTitleResource("ROOTLESS")
    "ubuntu" -> runtimeTitleResource("ROOTLESS_UBUNTU")
    "arch" -> runtimeTitleResource("ROOTLESS_ARCH")
    "avf" -> runtimeTitleResource("AVF")
    "terminal" -> R.string.profile_icon_terminal
    "keyboard" -> R.string.profile_icon_keyboard
    "settings" -> R.string.settings
    "memory" -> R.string.profile_icon_chip
    "search" -> R.string.profile_icon_search
    "download" -> R.string.profile_icon_download
    "check" -> R.string.profile_icon_check
    else -> if (id.endsWith(":mask")) R.string.profile_icon_tintable else R.string.profile_icon_original
})
