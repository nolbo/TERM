package dev.terminal.app.ui

import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource

/** Handles retain press/focus/drag feedback but never publish hover interactions. */
internal class HandleInteractionSource(
    private val delegate: MutableInteractionSource = MutableInteractionSource(),
) : MutableInteractionSource by delegate {
    override suspend fun emit(interaction: Interaction) {
        if (interaction !is HoverInteraction) delegate.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean =
        interaction is HoverInteraction || delegate.tryEmit(interaction)
}
