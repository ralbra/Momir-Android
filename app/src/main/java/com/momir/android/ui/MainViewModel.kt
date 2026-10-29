package com.momir.android.ui

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.momir.android.ble.BleTransport
import com.momir.android.data.ArtRepository
import com.momir.android.data.AtomicBootstrapper
import com.momir.android.data.CardRepository
import com.momir.android.data.SettingsStore
import com.momir.android.model.Creature
import com.momir.android.printer.PrintOptions
import com.momir.android.printer.PrinterDriver
import com.momir.android.printer.PrinterDrivers
import com.momir.android.render.CardRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

// @JvmOverloads keeps a constructor that takes only the Application, which is what the
// default ViewModel factory looks for; the other parameters can be replaced in tests.
class MainViewModel @JvmOverloads constructor(
    app: Application,
    private val artRepository: ArtRepository = ArtRepository(app),
    private val settings: SettingsStore = SettingsStore(app),
) : AndroidViewModel(app) {
    enum class BootstrapMode {
        INITIAL,
        REFRESH,
    }

    data class UiState(
        val loading: Boolean = true,
        val message: String = "Preparing card database...",
        val bootstrapping: Boolean = true,
        val bootstrapMode: BootstrapMode = BootstrapMode.INITIAL,
        val bootstrapStage: AtomicBootstrapper.Stage = AtomicBootstrapper.Stage.DOWNLOADING,
        val totalCreatures: Int = 0,
        val selectedMv: Int = 0,
        val includeFunny: Boolean = false,
        val showPreviewImage: Boolean = true,
        val card: Creature? = null,
        val imageUrl: String? = null,
        val previewBitmap: Bitmap? = null,
        val printerState: BleTransport.State = BleTransport.State.DISCONNECTED,
        val printerName: String? = null,
        val printWidth: Int = 576,
        val blackening: Int = SettingsStore.DEFAULT_BLACKENING,
    )

    private val _ui = MutableStateFlow(UiState(blackening = settings.blackening))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val ble = BleTransport<PrinterDriver>(app.applicationContext, PrinterDrivers::forName)
    private val repo = CardRepository(AtomicBootstrapper(app.filesDir))

    private val printInProgress = AtomicBoolean(false)

    private fun say(text: String) {
        _ui.update { it.copy(message = text) }
    }

    init {
        // Mirror the transport state into the UI from the start, independent of bootstrap.
        viewModelScope.launch {
            ble.stateFlow.collect { s ->
                _ui.update { it.copy(printerState = s) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val total = repo.ensureLoaded { stage ->
                    _ui.value = _ui.value.copy(
                        bootstrapping = true,
                        bootstrapMode = BootstrapMode.INITIAL,
                        bootstrapStage = stage,
                        message = stageMessage(stage),
                    )
                }
                _ui.value = _ui.value.copy(
                    loading = false,
                    bootstrapping = false,
                    message = "Ready",
                    totalCreatures = total,
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    loading = false,
                    bootstrapping = false,
                    message = "Bootstrap failed: ${e.message}",
                )
            }
        }
    }

    fun refreshAtomicJson() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _ui.value = _ui.value.copy(
                    loading = true,
                    bootstrapping = true,
                    bootstrapMode = BootstrapMode.REFRESH,
                    bootstrapStage = AtomicBootstrapper.Stage.DOWNLOADING,
                    message = stageMessage(AtomicBootstrapper.Stage.DOWNLOADING),
                )
                val total = repo.refreshData { stage ->
                    _ui.value = _ui.value.copy(
                        bootstrapping = true,
                        bootstrapMode = BootstrapMode.REFRESH,
                        bootstrapStage = stage,
                        message = stageMessage(stage),
                    )
                }
                _ui.value = _ui.value.copy(
                    loading = false,
                    bootstrapping = false,
                    totalCreatures = total,
                    message = "Atomic JSON updated",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    loading = false,
                    bootstrapping = false,
                    message = "Atomic update failed: ${e.message}",
                )
            }
        }
    }

    fun setMv(mv: Int) {
        _ui.value = _ui.value.copy(selectedMv = mv)
    }

    fun setIncludeFunny(v: Boolean) {
        _ui.value = _ui.value.copy(includeFunny = v)
    }

    fun setBlackening(level: Int) {
        settings.blackening = level
        _ui.update { it.copy(blackening = settings.blackening) }
    }

    fun setShowPreviewImage(v: Boolean) {
        val current = _ui.value
        _ui.value = current.copy(
            showPreviewImage = v,
            previewBitmap = if (v) current.previewBitmap else null,
        )
        if (v && current.card != null && current.previewBitmap == null) {
            generatePreview(current.card)
        }
    }

    fun roll() {
        val state = _ui.value
        val c = repo.getRandomCreature(state.selectedMv, state.includeFunny)

        if (c == null) {
            _ui.value = state.copy(
                card = null,
                imageUrl = null,
                previewBitmap = null,
                message = "No creatures at MV ${state.selectedMv}",
            )
            return
        }

        _ui.value = state.copy(
            card = c,
            imageUrl = artRepository.imageUrl(c.name),
            previewBitmap = null,
            message = "Rolled ${c.name}",
        )

        if (state.showPreviewImage) {
            generatePreview(c)
        }
    }

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ],
    )
    private fun connectPrinter() {
        _ui.update { it.copy(message = "Scanning for printer...") }

        ble.connect { ok ->
            val failure = ble.lastError ?: "Printer connect failed"
            _ui.update {
                it.copy(
                    printerName = ble.deviceName,
                    printWidth = ble.target?.printWidth ?: 576,
                    message = if (ok) "Connected to ${ble.deviceName}" else failure,
                )
            }
        }
    }

    fun syncPrinterState() {
        _ui.update {
            it.copy(
                printerState = ble.state,
                printerName = ble.deviceName,
                printWidth = ble.target?.printWidth ?: 576,
            )
        }
    }

    fun hasBluetoothPermissions(): Boolean = ble.hasBluetoothPermissions()

    /** The one action button: connects when disconnected, prints when ready. */
    fun onPrimaryAction() {
        when (ble.state) {
            BleTransport.State.DISCONNECTED -> connectSafely()
            BleTransport.State.READY -> printCurrentCard()
            BleTransport.State.CONNECTING,
            BleTransport.State.PRINTING -> Unit
        }
    }

    private fun connectSafely() {
        try {
            connectPrinter()
        } catch (_: SecurityException) {
            say("Bluetooth permission missing")
        }
    }

    fun printCurrentCard() {
        val state = _ui.value
        val card = state.card ?: return
        val driver = ble.target

        when (ble.state) {
            BleTransport.State.PRINTING -> {
                say("Already printing, please wait")
                return
            }

            BleTransport.State.CONNECTING -> {
                say("Printer is still connecting")
                return
            }

            BleTransport.State.DISCONNECTED -> {
                say("Printer not connected")
                return
            }

            BleTransport.State.READY -> Unit
        }
        if (driver == null) {
            say("Printer not connected")
            return
        }

        // Covers the time before the transport switches to PRINTING (card rendering).
        if (!printInProgress.compareAndSet(false, true)) {
            say("Already printing, please wait")
            return
        }

        viewModelScope.launch(Dispatchers.IO) @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT) {
            try {
                say("Preparing ${card.name}...")

                // The preview is 576 dots wide. Reuse it only for printers that
                // render natively at 576; the others (e.g. 384 or 624) get their own render.
                val rendered =
                    if (state.previewBitmap != null && driver.renderWidth == 576) {
                        state.previewBitmap
                    } else {
                        CardRenderer.renderCardBitmap(
                            card,
                            artRepository.fetchArtCrop(card.name),
                            printWidth = driver.renderWidth,
                        )
                    }

                val commands = driver.encode(
                    driver.prepare(rendered),
                    PrintOptions(darkness = _ui.value.blackening),
                )

                say("Printing ${card.name}...")

                var ok = false
                try {
                    ok = ble.sendCommands(commands)
                } catch (_: SecurityException) {
                    Log.w("Momir Main", "No Bluetooth permissions given")
                }

                say(
                    if (ok) {
                        "Printed ${card.name}"
                    } else {
                        ble.lastError ?: "Print failed"
                    },
                )
            } finally {
                printInProgress.set(false)
            }
        }
    }

    private fun generatePreview(card: Creature) {
        viewModelScope.launch(Dispatchers.IO) {
            val art = artRepository.fetchArtCrop(card.name)
            val preview = CardRenderer.renderCardBitmap(card, art, printWidth = 576)
            _ui.value = _ui.value.copy(previewBitmap = preview)
        }
    }

    private fun stageMessage(stage: AtomicBootstrapper.Stage): String {
        return when (stage) {
            AtomicBootstrapper.Stage.DOWNLOADING ->
                "Downloading AtomicCards JSON..."

            AtomicBootstrapper.Stage.PROCESSING ->
                "Processing creatures index..."

            AtomicBootstrapper.Stage.LOADING ->
                "Loading local creature database..."
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCleared() {
        super.onCleared()
        ble.disconnect()
    }
}