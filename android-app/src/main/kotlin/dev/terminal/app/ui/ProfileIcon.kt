package dev.terminal.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.terminal.app.ProfileIconStore
import dev.terminal.app.R
import dev.terminal.runtime.session.SessionProfileIcon
import dev.terminal.runtime.session.SessionRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val iconCache = LruCache<String, ImageBitmap>(64)

internal fun profileIconDrawable(id: String): Int = when (id) {
    "native" -> R.drawable.ic_runtime_native
    "rootless" -> R.drawable.ic_runtime_rootless_group
    "debian" -> R.drawable.ic_runtime_debian
    "ubuntu" -> R.drawable.ic_runtime_ubuntu
    "arch" -> R.drawable.ic_runtime_arch
    "avf", "memory" -> R.drawable.ic_material_symbol_memory_24
    "keyboard" -> R.drawable.ic_material_symbol_keyboard_24
    "settings" -> R.drawable.ic_material_symbol_settings_24
    "search" -> R.drawable.ic_material_symbol_search_24
    "download" -> R.drawable.ic_material_symbol_download_24
    "check" -> R.drawable.ic_material_symbol_check_circle_24
    else -> R.drawable.ic_material_symbol_terminal_24
}

/** All surfaces share sizing and tint policy; full-color imports never get a tint filter. */
@Composable
internal fun ProfileIcon(icon: String, runtime: String, modifier: Modifier = Modifier,
    size: Dp = 24.dp, tint: Color = LocalContentColor.current, contentDescription: String? = null) {
    val selectedRuntime = SessionRuntime.fromWireName(runtime) ?: SessionRuntime.NATIVE
    val resolved = SessionProfileIcon.resolve(icon, selectedRuntime)
    val context = LocalContext.current.applicationContext
    var bitmap by remember(resolved) { mutableStateOf(iconCache.get(resolved)) }
    LaunchedEffect(resolved) {
        if (bitmap == null && SessionProfileIcon.isImage(resolved)) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching { ProfileIconStore(context).file(resolved)?.takeIf { it.isFile }?.let {
                    BitmapFactory.decodeFile(it.path)?.asImageBitmap()
                } }.getOrNull()?.also { iconCache.put(resolved, it) }
            }
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) Image(loaded, contentDescription, Modifier.size(size), contentScale = ContentScale.Fit,
            colorFilter = if (resolved.endsWith(":mask")) ColorFilter.tint(tint) else null)
        else Icon(painterResource(profileIconDrawable(if (SessionProfileIcon.isImage(resolved))
            SessionProfileIcon.resolve("auto", selectedRuntime) else resolved)), contentDescription,
            Modifier.size(size), tint = tint)
    }
}
