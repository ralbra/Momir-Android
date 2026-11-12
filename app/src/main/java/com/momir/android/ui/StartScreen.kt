package com.momir.android.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.momir.android.ble.BleTransport

enum class MomirFormat(val label: String) {
    CLASSIC("Classic"),
    NEW_CUSTOM("New Custom"),
}

/** Shown on launch: choose players and format, connect the printer, then start the game. */
@Composable
fun StartScreen(
    ui: MainViewModel.UiState,
    onPlayerCount: (Int) -> Unit,
    onFormat: (MomirFormat) -> Unit,
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

            DropdownField(
                label = "Momir format",
                value = ui.momirFormat.label,
                options = MomirFormat.values().toList(),
                optionLabel = { it.label },
                onSelect = onFormat,
            )

            if (ui.momirFormat == MomirFormat.NEW_CUSTOM) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    ) { Text("Start wizard") }
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    ) { Text("Edit") }
                }
                Text(
                    text = "Custom formats are not available yet. A standard game is played.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                enabled = !ui.loading,
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
