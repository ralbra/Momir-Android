package com.momir.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.momir.android.ble.BleTransport

/** Shows the rolled card with Close and Print (or Connect, while no printer is connected). */
@Composable
fun RollDialog(
    ui: MainViewModel.UiState,
    onClose: () -> Unit,
    onAction: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    val actionLabel = when (ui.printerState) {
        BleTransport.State.DISCONNECTED -> "Connect"
        BleTransport.State.CONNECTING -> "Connecting..."
        BleTransport.State.READY -> "Print"
        BleTransport.State.PRINTING -> "Printing..."
    }
    val actionEnabled = when (ui.printerState) {
        BleTransport.State.DISCONNECTED -> true
        BleTransport.State.READY -> ui.card != null
        BleTransport.State.CONNECTING,
        BleTransport.State.PRINTING -> false
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = maxHeight),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val preview = ui.previewBitmap
                    val url = ui.imageUrl
                    when {
                        ui.card == null -> Unit

                        !ui.showPreviewImage ->
                            Text(
                                text = "Preview hidden for fun",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 28.dp),
                            )

                        preview != null ->
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "Print preview",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth(),
                            )

                        url != null ->
                            AsyncImage(
                                model = url,
                                contentDescription = "Card image",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth(),
                            )

                        else -> CircularProgressIndicator()
                    }
                }

                Text(
                    text = ui.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Close")
                    }
                    Button(
                        onClick = onAction,
                        enabled = actionEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}