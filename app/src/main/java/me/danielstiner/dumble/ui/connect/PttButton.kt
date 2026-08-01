package me.danielstiner.dumble.ui.connect

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
fun PttButton(
    enabled: Boolean,
    disabledReason: String?,
    onTransmitting: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(interactions) {
        // Press/Release/Cancel only — hover and focus interactions share this flow, so an `else`
        // here would close the gate when the button takes focus mid-press.
        interactions.interactions.collect {
            if (it is PressInteraction) onTransmitting(it is PressInteraction.Press)
        }
    }
    Button(
        onClick = {},
        enabled = enabled,
        interactionSource = interactions,
        modifier = modifier,
    ) {
        Text(if (enabled) "Hold to talk" else disabledReason ?: "Microphone unavailable")
    }
}
