@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.R

private data class SetupRuntime(
    val title: Int,
    val label: Int,
    val description: Int,
    @param:DrawableRes val icon: Int,
)

@Composable
fun SetupScreen(onStart: () -> Unit) {
    val linuxRuntimes = listOf(
        SetupRuntime(R.string.runtime_rootless_title, R.string.runtime_rootless_setup,
            R.string.setup_rootless_description, R.drawable.ic_material_symbol_download_24),
        SetupRuntime(R.string.runtime_avf_title, R.string.runtime_avf_setup,
            R.string.setup_avf_description, R.drawable.ic_material_symbol_memory_24),
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Button(
                        onClick = onStart,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(12.dp))
                        Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null,
                            modifier = Modifier.size(24.dp))
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 20.dp),
                contentPadding = PaddingValues(top = 20.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item {
                    Icon(painterResource(R.drawable.ic_notification_terminal), null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.setup_headline), style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Box(Modifier.padding(20.dp)) {
                            SetupRuntimeSummary(SetupRuntime(R.string.runtime_native_title, R.string.runtime_native_setup,
                                R.string.setup_native_description, R.drawable.ic_material_symbol_terminal_24))
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.setup_linux_title), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.setup_linux_later), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                        SettingsGroup {
                            linuxRuntimes.forEach { runtime ->
                                Box(Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp)) {
                                    SetupRuntimeSummary(runtime)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupRuntimeSummary(runtime: SetupRuntime) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(painterResource(runtime.icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp).size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(runtime.label), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
            Text(stringResource(runtime.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(runtime.description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
