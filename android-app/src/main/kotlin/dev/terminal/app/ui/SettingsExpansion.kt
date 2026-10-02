@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.R

/** Keep the reveal anchored to its header, including when a toggle interrupts the spring. */
@Composable
internal fun SettingsExpansionContent(expanded: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            expandFrom = Alignment.Top,
        ) + fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = shrinkVertically(
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
    ) { content() }
}

@Composable
internal fun SettingsExpansionIcon(expanded: Boolean) {
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "settingsExpansionRotation",
    )
    Icon(
        painterResource(R.drawable.ic_material_symbol_expand_more_24),
        contentDescription = null,
        modifier = Modifier.size(24.dp).rotate(rotation.value),
    )
}
