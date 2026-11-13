package io.github.ralbra.randomize_the_gathering.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ralbra.randomize_the_gathering.ble.BleTransport
import io.github.ralbra.randomize_the_gathering.data.SettingsStore
import io.github.ralbra.randomize_the_gathering.model.Creature
import kotlin.math.roundToInt

private enum class SettingsSection(val title: String) {
    CARD_DATA("Card data"),
    PRINTER("Printer"),
    UI("UI"),
    DEBUG("Debug"),
}

/** Full-screen settings with accordion sections; one section is open at a time. */
@Composable
fun SettingsDialog(
    ui: MainViewModel.UiState,
    vm: MainViewModel,
    onClose: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf<SettingsSection?>(SettingsSection.CARD_DATA) }
    val sections = listOf(
        SettingsSection.CARD_DATA,
        SettingsSection.PRINTER,
        SettingsSection.UI,
        SettingsSection.DEBUG,
    )

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
                                SettingsSection.CARD_DATA -> CardDataSettings(ui, vm, onClose)
                                SettingsSection.PRINTER -> PrinterSettings(ui, vm)
                                SettingsSection.UI -> UiSettings(ui, vm)
                                SettingsSection.DEBUG -> DebugSettings(ui, vm, onClose)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AccordionSection(
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
private fun CardDataSettings(
    ui: MainViewModel.UiState,
    vm: MainViewModel,
    onClose: () -> Unit,
) {
    Text("Classic card database", fontWeight = FontWeight.Bold)
    Text(
        "${ui.totalCreatures} creatures (MTGJSON).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = {
            vm.refreshAtomicJson()
            onClose()
        },
        enabled = !ui.loading,
    ) {
        Text("Update card database")
    }

    HorizontalDivider()
    CardPoolSettings(ui.cardPool, onDownload = vm::downloadCardPool)

    HorizontalDivider()
    SettingCheckbox("Cache card art on this device", ui.cacheArt, vm::setCacheArt)
    Text(
        "Off by default. When on, card art is saved to and read from the app's storage folder on this device, so cards keep their art offline. A fully filled cache uses a lot of storage.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

    OutlinedButton(
        onClick = vm::printTestTicket,
        enabled = ui.printerState == BleTransport.State.READY,
    ) {
        Text("Print test ticket")
    }

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

    SettingCheckbox("Print automatically", ui.autoPrint, vm::setAutoPrint)
    Text(
        "Every rolled card and the emblem are printed right away while a printer is connected. The popup still shows the card.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        if (ui.extraFeedMm == 0) "Extra paper after each ticket: none" else "Extra paper after each ticket: ${ui.extraFeedMm} mm",
    )
    Slider(
        value = ui.extraFeedMm.toFloat(),
        onValueChange = { vm.setExtraFeedMm(it.roundToInt()) },
        valueRange = 0f..SettingsStore.MAX_EXTRA_FEED_MM.toFloat(),
        steps = SettingsStore.MAX_EXTRA_FEED_MM - 1,
    )
    Text(
        "Blank space below the ticket, so it is easier to tear off.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SettingCheckbox("Don't print art", !ui.printArt) { vm.setPrintArt(!it) }
    Text(
        "Tickets are printed without the card art: shorter, faster and with less paper. The preview follows.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UiSettings(
    ui: MainViewModel.UiState,
    vm: MainViewModel,
) {
    Text("Theme")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            FilterChip(
                selected = ui.themeMode == mode,
                onClick = { vm.setThemeMode(mode) },
                label = { Text(mode.label) },
            )
        }
    }
    SettingCheckbox("Rotate the top row's counters", ui.rotatePlayerOne, vm::setRotatePlayerOne)
    SettingCheckbox("Keep screen on", ui.keepScreenOn, vm::setKeepScreenOn)
    SettingCheckbox("Show card preview in the roll popup", ui.showPreviewImage, vm::setShowPreviewImage)
    SettingCheckbox("Preview matches printer output", ui.previewMatchesPrinter, vm::setPreviewMatchesPrinter)
    Text(
        "On: drawn and dithered like the printout. Off: classic 576 px grayscale preview.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text("Controls bar height: ${ui.barHeightPercent}% of the screen")
    Slider(
        value = ui.barHeightPercent.toFloat(),
        onValueChange = { vm.setBarHeightPercent(it.roundToInt()) },
        valueRange = 20f..50f,
        steps = 29,
    )
}

@Composable
private fun DebugSettings(
    ui: MainViewModel.UiState,
    vm: MainViewModel,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val classic = remember(query, ui.totalCreatures) { vm.searchCards(query) }
    // Cards only in the custom format card data (non-creatures, newer cards), after the Classic ones.
    val extra = remember(query, ui.cardPool.state, classic) {
        vm.searchPoolCards(query, classic.map { it.name }.toSet())
    }
    val matches = classic + extra

    fun show(card: Creature) {
        vm.rollCard(card)
        onClose()
    }

    Text("Roll a specific card", fontWeight = FontWeight.Bold)
    Text(
        "Type part of a card name (at least two letters) and pick a result. The card opens in the roll popup like a normal roll, with Print.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // The results are above the input field so the keyboard does not cover them.
    when {
        query.trim().length < 2 -> Unit

        matches.isEmpty() ->
            Text(
                "No card with that name in the card data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        else -> matches.forEach { card ->
            val faces =
                if (card.extraFaces.isEmpty()) "" else ", ${1 + card.extraFaces.size} faces"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { show(card) }
                    .padding(vertical = 6.dp),
            ) {
                Text(card.name, fontWeight = FontWeight.SemiBold)
                val source = if (card in extra) ", custom card data" else ""
                Text(
                    "MV ${card.manaValue}, ${card.type}$faces$source",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Card name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { matches.firstOrNull()?.let { show(it) } }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Status of the custom format card data, with a download / update button behind a warning. */
@Composable
internal fun CardPoolSettings(
    status: MainViewModel.CardPoolStatus,
    onDownload: () -> Unit,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val busy = status.state == MainViewModel.CardPoolState.DOWNLOADING ||
        status.state == MainViewModel.CardPoolState.LOADING

    Text("Custom format card data", fontWeight = FontWeight.Bold)
    Text(
        when (status.state) {
            MainViewModel.CardPoolState.MISSING -> "Not downloaded"
            MainViewModel.CardPoolState.LOADING -> "Loading..."
            MainViewModel.CardPoolState.DOWNLOADING -> "Downloading..."
            MainViewModel.CardPoolState.READY ->
                "${status.cards} cards in ${status.sets} sets (Scryfall, ${status.updatedAt})"
            MainViewModel.CardPoolState.FAILED -> "Not available"
        },
    )
    if (status.detail.isNotBlank()) {
        Text(
            status.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedButton(onClick = { confirm = true }, enabled = !busy) {
        Text(if (status.state == MainViewModel.CardPoolState.READY) "Update card data" else "Download card data")
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Download card data?") },
            text = {
                Text(
                    "Custom formats need every card with its printings, rarities and sets. " +
                        "This downloads about 80 MB from Scryfall (Wi-Fi recommended) and stores " +
                        "about 10 MB on this device. Classic Momir does not need it.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onDownload()
                    },
                ) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
internal fun SettingCheckbox(
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