package com.momir.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.momir.android.ble.BleTransport
import kotlin.math.roundToInt

private enum class SettingsSection(val title: String) {
    GAME("Game"),
    PRINTER("Printer"),
    UI("UI"),
}

/** Full-screen settings with accordion sections; one section is open at a time. */
@Composable
fun SettingsDialog(
    ui: MainViewModel.UiState,
    vm: MainViewModel,
    onClose: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf<SettingsSection?>(SettingsSection.GAME) }
    val sections = listOf(SettingsSection.GAME, SettingsSection.PRINTER, SettingsSection.UI)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close settings")
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(sections) { section ->
                        AccordionSection(
                            title = section.title,
                            expanded = expanded == section,
                            onToggle = { expanded = if (expanded == section) null else section },
                        ) {
                            when (section) {
                                SettingsSection.GAME -> GameSettings(ui, vm, onClose)
                                SettingsSection.PRINTER -> PrinterSettings(ui, vm)
                                SettingsSection.UI -> UiSettings(ui, vm)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccordionSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun GameSettings(
    ui: MainViewModel.UiState,
    vm: MainViewModel,
    onClose: () -> Unit,
) {
    Text("Starting life", fontWeight = FontWeight.Bold)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = { vm.setStartingLife(ui.startingLife - 1) }) { Text("−") }
        Text(ui.startingLife.toString(), style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = { vm.setStartingLife(ui.startingLife + 1) }) { Text("+") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(20, 25, 30, 40).forEach { value ->
            FilterChip(
                selected = ui.startingLife == value,
                onClick = { vm.setStartingLife(value) },
                label = { Text(value.toString()) },
            )
        }
    }
    Text(
        "Applies when the life totals are reset.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = vm::resetLife) { Text("Reset life totals") }

    SettingCheckbox("Include Un-sets", ui.includeFunny, vm::setIncludeFunny)
    OutlinedButton(
        onClick = {
            vm.refreshAtomicJson()
            onClose()
        },
        enabled = !ui.loading,
    ) {
        Text("Update card database")
    }
}

@Composable
private fun PrinterSettings(
    ui: MainViewModel.UiState,
    vm: MainViewModel,
) {
    val status = when (ui.printerState) {
        BleTransport.State.DISCONNECTED -> "Not connected"
        BleTransport.State.CONNECTING -> "Connecting..."
        BleTransport.State.READY -> "Connected: ${ui.printerName ?: "printer"}"
        BleTransport.State.PRINTING -> "Printing on ${ui.printerName ?: "printer"}"
    }
    Text(status, fontWeight = FontWeight.Bold)

    Text("Print darkness: ${ui.blackening} (1 = light, 5 = dark)")
    Slider(
        value = ui.blackening.toFloat(),
        onValueChange = { vm.setBlackening(it.roundToInt()) },
        valueRange = 1f..5f,
        steps = 3,
    )
    Text(
        "Applies to the X6H printer.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SettingCheckbox("Preview matches printer output", ui.previewMatchesPrinter, vm::setPreviewMatchesPrinter)
    Text(
        "On: drawn and dithered like the printout. Off: classic 576 px grayscale preview.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UiSettings(
    ui: MainViewModel.UiState,
    vm: MainViewModel,
) {
    SettingCheckbox("Rotate the top player's counter", ui.rotatePlayerOne, vm::setRotatePlayerOne)
    SettingCheckbox("Keep screen on", ui.keepScreenOn, vm::setKeepScreenOn)
    SettingCheckbox("Show card preview in the roll popup", ui.showPreviewImage, vm::setShowPreviewImage)

    Text("Controls bar height: ${ui.barHeightPercent}% of the screen")
    Slider(
        value = ui.barHeightPercent.toFloat(),
        onValueChange = { vm.setBarHeightPercent(it.roundToInt()) },
        valueRange = 20f..50f,
        steps = 29,
    )
}

@Composable
private fun SettingCheckbox(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}