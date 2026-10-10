package dev.terminal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import dev.terminal.runtime.session.SessionProfileIcon

/** Shared icon-only choice; Foundation semantics also survive lazy-row removal. */
@Composable
internal fun IconChoiceTile(icon: String, selected: Boolean, modifier: Modifier = Modifier,
    enabled: Boolean = true, runtime: String = "NATIVE", onSelect: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val label = iconChoiceLabel(icon)
    Box(modifier.heightIn(min = 64.dp).clip(shape)
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        if (SessionProfileIcon.isImage(icon)) ProfileIcon(icon, runtime, size = 28.dp)
        else Icon(painterResource(profileIconDrawable(icon)), contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

@Composable
internal fun iconChoiceLabel(id: String): String = stringResource(when (id) {
    "auto" -> R.string.icon_choice_auto
    "team_dashboard" -> R.string.workspace_icon_dashboard
    "terminal" -> R.string.profile_icon_terminal
    "folder_shared" -> R.string.workspace_icon_folder
    "layers" -> R.string.workspace_icon_layers
    "deployed_code" -> R.string.workspace_icon_code
    "lan" -> R.string.workspace_icon_network
    "memory" -> R.string.profile_icon_chip
    "keyboard" -> R.string.profile_icon_keyboard
    "settings" -> R.string.settings
    "search" -> R.string.profile_icon_search
    "download" -> R.string.profile_icon_download
    "check" -> R.string.profile_icon_check
    "native" -> runtimeTitleResource("NATIVE")
    "rootless" -> R.string.runtime_rootless_title
    "debian" -> runtimeTitleResource("ROOTLESS")
    "ubuntu" -> runtimeTitleResource("ROOTLESS_UBUNTU")
    "arch" -> runtimeTitleResource("ROOTLESS_ARCH")
    "avf" -> runtimeTitleResource("AVF")
    "cloud" -> R.string.icon_choice_cloud
    "code" -> R.string.icon_choice_code
    "rocket_launch" -> R.string.icon_choice_rocket
    "science" -> R.string.icon_choice_science
    "palette" -> R.string.icon_choice_palette
    "construction" -> R.string.icon_choice_tools
    "folder" -> R.string.icon_choice_folder
    "sports_esports" -> R.string.icon_choice_game
    "menu_book" -> R.string.icon_choice_book
    "pets" -> R.string.icon_choice_paw
    "public" -> R.string.icon_choice_globe
    "star" -> R.string.icon_choice_star
    "bolt" -> R.string.icon_choice_lightning
    "lightbulb" -> R.string.icon_choice_lightbulb
    else -> if (id.endsWith(":mask")) R.string.profile_icon_tintable else R.string.profile_icon_original
})
