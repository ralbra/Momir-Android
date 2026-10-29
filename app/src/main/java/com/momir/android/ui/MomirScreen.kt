package com.momir.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.momir.android.ble.BleTransport

/**
 * Layout: player 1 (rotated) / control bar / player 2.
 * The control bar height is configurable; the two counters share the rest equally.
 */
@Composable
fun MomirScreen(
    vm: MainViewModel,
    onRequestPermissions: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val darkTheme = isSystemInDarkTheme()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    val pageColor = if (darkTheme) Color(0xFF0E1625) else Color(0xFFF4F8FF)
    val playerOneColor = if (darkTheme) Color(0xFF16233A) else Color(0xFFDCE9FF)
    val playerTwoColor = if (darkTheme) Color(0xFF123027) else Color(0xFFD9F2EA)
    val barColor = if (darkTheme) Color(0xFF182334) else Color.White
    val lifeTextColor = if (darkTheme) Color(0xFFE7EDF8) else Color(0xFF142033)
    val accent = if (darkTheme) Color(0xFF0A8E71) else Color(0xFF006D77)
    val accentText = if (darkTheme) Color(0xFFEAFFF8) else Color.White
    val outline = if (darkTheme) Color(0xFF9FC3FF) else Color(0xFF1D3557)

    val view = LocalView.current
    DisposableEffect(view, ui.keepScreenOn) {
        view.keepScreenOn = ui.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Messages that have no dialog to live in are shown as a snackbar.
    LaunchedEffect(ui.message) {
        if (ui.message.isNotBlank() && !ui.bootstrapping && !ui.showRollDialog && !showSettings) {
            snackbarHost.showSnackbar(ui.message)
        }
    }

    // Shared by the printer icon and the popup's Print/Connect button.
    val connectOrPrint: () -> Unit = {
        if (ui.printerState == BleTransport.State.DISCONNECTED && !vm.hasBluetoothPermissions()) {
            onRequestPermissions()
        } else {
            vm.onPrimaryAction()
        }
    }

    Scaffold(
        containerColor = pageColor,
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        val bar = ui.barHeightPercent / 100f
        val side = (1f - bar) / 2f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LifeCounter(
                life = ui.life1,
                onDelta = { vm.changeLife(1, it) },
                background = playerOneColor,
                contentColor = lifeTextColor,
                rotated = ui.rotatePlayerOne,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(side),
            )

            ControlBar(
                ui = ui,
                barColor = barColor,
                accent = accent,
                accentText = accentText,
                outline = outline,
                onPrinterClick = {
                    if (ui.printerState == BleTransport.State.DISCONNECTED) {
                        connectOrPrint()
                    } else {
                        vm.showPrinterInfo()
                    }
                },
                onMinus = { vm.changeMv(-1) },
                onRoll = vm::roll,
                onPlus = { vm.changeMv(1) },
                onSettings = { showSettings = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(bar),
            )

            LifeCounter(
                life = ui.life2,
                onDelta = { vm.changeLife(2, it) },
                background = playerTwoColor,
                contentColor = lifeTextColor,
                rotated = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(side),
            )
        }
    }

    if (ui.showRollDialog) {
        RollDialog(
            ui = ui,
            onClose = vm::dismissRollDialog,
            onAction = connectOrPrint,
        )
    }

    if (showSettings) {
        SettingsDialog(
            ui = ui,
            vm = vm,
            onClose = { showSettings = false },
        )
    }

    if (ui.bootstrapping) {
        BootstrapDialog(
            title = if (ui.bootstrapMode == MainViewModel.BootstrapMode.INITIAL) {
                "Preparing Database"
            } else {
                "Updating Database"
            },
            message = ui.message,
        )
    }
}

@Composable
private fun ControlBar(
    ui: MainViewModel.UiState,
    barColor: Color,
    accent: Color,
    accentText: Color,
    outline: Color,
    onPrinterClick: () -> Unit,
    onMinus: () -> Unit,
    onRoll: () -> Unit,
    onPlus: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(barColor)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PrinterStatusButton(state = ui.printerState, onClick = onPrinterClick)

        OutlinedButton(
            onClick = onMinus,
            enabled = ui.selectedMv > 0,
            border = BorderStroke(1.dp, outline),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(0.55f),
        ) {
            Text("−", fontSize = 36.sp, color = outline)
        }

        Button(
            onClick = onRoll,
            enabled = !ui.loading,
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight(0.7f),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Roll", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = accentText)
                Text("MV ${ui.selectedMv}", fontSize = 16.sp, color = accentText)
            }
        }

        OutlinedButton(
            onClick = onPlus,
            enabled = ui.selectedMv < 16,
            border = BorderStroke(1.dp, outline),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(0.55f),
        ) {
            Text("+", fontSize = 36.sp, color = outline)
        }

        IconButton(onClick = onSettings, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = outline,
            )
        }
    }
}

@Composable
private fun PrinterStatusButton(
    state: BleTransport.State,
    onClick: () -> Unit,
) {
    val tint = when (state) {
        BleTransport.State.DISCONNECTED -> Color(0xFFE57373)
        BleTransport.State.CONNECTING -> Color(0xFFFFC857)
        BleTransport.State.READY,
        BleTransport.State.PRINTING -> Color(0xFF4CAF50)
    }
    val description = when (state) {
        BleTransport.State.DISCONNECTED -> "Printer disconnected. Tap to connect"
        BleTransport.State.CONNECTING -> "Connecting to printer"
        BleTransport.State.READY -> "Printer connected"
        BleTransport.State.PRINTING -> "Printing"
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .border(2.dp, tint, CircleShape)
            .semantics { contentDescription = description },
    ) {
        when (state) {
            BleTransport.State.DISCONNECTED ->
                Icon(Icons.Filled.Close, contentDescription = null, tint = tint)

            BleTransport.State.READY ->
                Icon(Icons.Filled.Check, contentDescription = null, tint = tint)

            BleTransport.State.CONNECTING,
            BleTransport.State.PRINTING ->
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = tint,
                )
        }
    }
}

@Composable
private fun BootstrapDialog(title: String, message: String) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}