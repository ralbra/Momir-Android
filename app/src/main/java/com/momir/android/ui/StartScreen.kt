package com.momir.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.momir.android.ble.BleTransport
import com.momir.android.data.SettingsStore
import com.momir.android.model.FormatChoice

private const val NEW_CUSTOM_LABEL = "<-- New Custom Game -->"

/** Shown on launch: choose players and format, connect the printer, then start the game. */
@Composable
fun StartScreen(
    ui: MainViewModel.UiState,
    onPlayerCount: (Int) -> Unit,
    onStartingLife: (Int) -> Unit,
    onFormat: (FormatChoice) -> Unit,
    /** Opens the wizard: a new format for null, otherwise the saved format with this id. */
    onOpenWizard: (String?) -> Unit,
    onPlayWithoutPrinter: (Boolean) -> Unit,
    onConnectPrinter: () -> Unit,
    onStartGame: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        IconButton(
            onClick = onSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Momir",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
            )

            DropdownField(
                label = "Players",
                value = "${ui.playerCount} players",
                options = (2..6).toList(),
                optionLabel = { "$it players" },
                onSelect = onPlayerCount,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Starting Life",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                RepeatButton(
                    onStep = { big -> onStartingLife(lifeStep(ui.startingLife, -1, big)) },
                    enabled = ui.startingLife > SettingsStore.MIN_STARTING_LIFE,
                ) { Text("\u2212") }
                Text(
                    text = ui.startingLife.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
                RepeatButton(
                    onStep = { big -> onStartingLife(lifeStep(ui.startingLife, 1, big)) },
                    enabled = ui.startingLife < SettingsStore.MAX_STARTING_LIFE,
                ) { Text("+") }
            }

            val choices = listOf(FormatChoice.Classic) +
                ui.customFormats.map { FormatChoice.Custom(it.id) } +
                FormatChoice.NewCustom
            val formatName: (FormatChoice) -> String = { choice ->
                when (choice) {
                    FormatChoice.Classic -> "Classic"
                    FormatChoice.NewCustom -> NEW_CUSTOM_LABEL
                    is FormatChoice.Custom ->
                        ui.customFormats.firstOrNull { it.id == choice.id }?.name ?: "Unknown format"
                }
            }
            DropdownField(
                label = "Momir format",
                value = formatName(ui.formatChoice),
                options = choices,
                optionLabel = formatName,
                onSelect = onFormat,
            )

            when (val choice = ui.formatChoice) {
                FormatChoice.Classic -> Unit
                FormatChoice.NewCustom -> {
                    OutlinedButton(
                        onClick = { onOpenWizard(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start wizard") }
                    Hint("Create the format with the wizard first")
                }
                is FormatChoice.Custom -> {
                    OutlinedButton(
                        onClick = { onOpenWizard(choice.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Edit") }
                    if (ui.cardPool.state != MainViewModel.CardPoolState.READY) {
                        Hint("Custom formats need the custom format card data (Settings > Card data).")
                    }
                }
            }

            val printerLabel = when (ui.printerState) {
                BleTransport.State.DISCONNECTED -> "Connect to Printer"
                BleTransport.State.CONNECTING -> "Connecting..."
                BleTransport.State.READY -> "Printer connected: ${ui.printerName ?: "printer"}"
                BleTransport.State.PRINTING -> "Printing..."
            }
            val printerEnabled = !ui.playWithoutPrinter &&
                ui.printerState != BleTransport.State.CONNECTING &&
                ui.printerState != BleTransport.State.PRINTING

            OutlinedButton(
                onClick = onConnectPrinter,
                enabled = printerEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(printerLabel)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayWithoutPrinter(!ui.playWithoutPrinter) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Play without printer", modifier = Modifier.weight(1f))
                Switch(
                    checked = ui.playWithoutPrinter,
                    onCheckedChange = onPlayWithoutPrinter,
                )
            }

            Button(
                onClick = onStartGame,
                enabled = !ui.loading && when (ui.formatChoice) {
                    FormatChoice.Classic -> true
                    FormatChoice.NewCustom -> false
                    is FormatChoice.Custom -> ui.cardPool.state == MainViewModel.CardPoolState.READY
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Start game", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun <T> DropdownField(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(value, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            onSelect(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One step of the starting life: 1, or 5 to the next multiple of 5 while a button is held. */
internal fun lifeStep(life: Int, direction: Int, big: Boolean): Int = when {
    !big -> life + direction
    direction > 0 -> (life / 5 + 1) * 5
    else -> ((life - 1) / 5) * 5
}.coerceIn(SettingsStore.MIN_STARTING_LIFE, SettingsStore.MAX_STARTING_LIFE)

/**
 * A button that steps once per tap and keeps stepping while held: by 1 at first, then in
 * steps of 5 ([onStep] gets true for those).
 */
@Composable
private fun RepeatButton(
    onStep: (big: Boolean) -> Unit,
    enabled: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    // Set once holding has stepped, so the click on release does not add another step.
    var repeated by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        repeated = false
        delay(400)
        var count = 0
        while (true) {
            repeated = true
            step(count >= 6)
            count++
            delay(if (count > 6) 220 else 120)
        }
    }

    OutlinedButton(
        onClick = {
            if (!repeated) step(false)
            repeated = false
        },
        enabled = enabled,
        interactionSource = interaction,
        content = content,
    )
}
