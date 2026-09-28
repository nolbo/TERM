@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer

/** Keep the overlay mounted so both scroll boundaries fade without intercepting input. */
@Composable
internal fun ScrollEdgeFade(visible: Boolean, brush: Brush, modifier: Modifier = Modifier) {
    val opacity by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "scrollEdgeOpacity",
    )
    Spacer(modifier.graphicsLayer { alpha = opacity }.background(brush))
}
