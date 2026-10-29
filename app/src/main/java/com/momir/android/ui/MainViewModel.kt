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
import com.momir.android.render.Monochrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Width of the grayscale preview shown while no printer is connected (or in classic mode). */
private const val FALLBACK_PREVIEW_WIDTH = 576

private const val MAX_MANA_VALUE = 16

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
        val previewMatchesPrinter: Boolean = SettingsStore.DEFAULT_PREVIEW_MATCHES_PRINTER,
        // Life counter and layout
        val life1: Int = SettingsStore.DEFAULT_STARTING_LIFE,
        val life2: Int = SettingsStore.DEFAULT_STARTING_LIFE,
        val startingLife: Int = SettingsStore.DEFAULT_STARTING_LIFE,
        val rotatePlayerOne: Boolean = true,
        val barHeightPercent: Int = SettingsStore.DEFAULT_BAR_HEIGHT_PERCENT,
        val keepScreenOn: Boolean = true,
        val showRollDialog: Boolean = false,
    )

    /** A rendered card, tagged with what it was rendered for. [driverName] is null for the fallback. */
    private class Rendered(
        val cardName: String,
        val driverName: String?,
        val bitmap: Bitmap,
    )

    private val _ui = MutableStateFlow(
        UiState(
            blackening = settings.blackening,
            previewMatchesPrinter = settings.previewMatchesPrinter,
            life1 = settings.startingLife,
            life2 = settings.startingLife,
            startingLife = settings.startingLife,
            rotatePlayerOne = settings.rotatePlayerOne,
            barHeightPercent = settings.barHeightPercent,
            keepScreenOn = settings.keepScreenOn,
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val ble = BleTransport<PrinterDriver>(app.applicationContext, PrinterDrivers::forName)
    private val repo = CardRepository(AtomicBootstrapper(app.filesDir))

    private val printInProgress = AtomicBoolean(false)

    // The art of the current card, so a printer change does not download it again.
    @Volatile
    private var artCache: Pair<String, Bitmap?>? = null

    // The last preview, reused for printing where that gives the same result.
    @Volatile
    private var lastPreview: Rendered? = null

    private fun say(text: String) {
        _ui.update { it.copy(message = text) }
    }

    init {
        // Mirror the transport state into the UI from the start, independent of bootstrap.
        viewModelScope.launch {
            ble.stateFlow.collect { s ->
                _ui.update { it.copy(printerState = s) }
                refreshPreviewIfPrinterChanged()
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
                    message = "",
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

    // ---- Life counter -------------------------------------------------------------

    fun changeLife(player: Int, delta: Int) {
        _ui.update {
            if (player == 1) {
                it.copy(life1 = it.life1 + delta)
            } else {
                it.copy(life2 = it.life2 + delta)
            }
        }
    }

    fun resetLife() {
        _ui.update { it.copy(life1 = it.startingLife, life2 = it.startingLife) }
    }

    fun setStartingLife(v: Int) {
        settings.startingLife = v
        _ui.update { it.copy(startingLife = settings.startingLife) }
    }

    // ---- Layout and screen settings -----------------------------------------------

    fun setRotatePlayerOne(v: Boolean) {
        settings.rotatePlayerOne = v
        _ui.update { it.copy(rotatePlayerOne = v) }
    }

    fun setBarHeightPercent(v: Int) {
        settings.barHeightPercent = v
        _ui.update { it.copy(barHeightPercent = settings.barHeightPercent) }
    }

    fun setKeepScreenOn(v: Boolean) {
        settings.keepScreenOn = v
        _ui.update { it.copy(keepScreenOn = v) }
    }

    // ---- Rolling ------------------------------------------------------------------

    fun changeMv(delta: Int) {
        _ui.update { it.copy(selectedMv = (it.selectedMv + delta).coerceIn(0, MAX_MANA_VALUE)) }
    }

    fun setIncludeFunny(v: Boolean) {
        _ui.value = _ui.value.copy(includeFunny = v)
    }

    fun setBlackening(level: Int) {
        settings.blackening = level
        _ui.update { it.copy(blackening = settings.blackening) }
    }

    fun setPreviewMatchesPrinter(v: Boolean) {
        settings.previewMatchesPrinter = v
        _ui.update { it.copy(previewMatchesPrinter = v) }
        // Re-render the visible preview in the newly chosen style.
        refreshPreviewIfPrinterChanged()
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
            _ui.update {
                it.copy(
                    card = null,
                    imageUrl = null,
                    previewBitmap = null,
                    showRollDialog = true,
                    message = "No creatures at MV ${it.selectedMv}",
                )
            }
            return
        }

        _ui.update {
            it.copy(
                card = c,
                imageUrl = artRepository.imageUrl(c.name),
                previewBitmap = null,
                showRollDialog = true,
                message = "Rolled ${c.name}",
            )
        }

        if (state.showPreviewImage) {
            generatePreview(c)
        }
    }

    fun dismissRollDialog() {
        _ui.update { it.copy(showRollDialog = false) }
    }

    // ---- Printer ------------------------------------------------------------------

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

    /** Tapping the printer icon while it is not disconnected: say what the state is. */
    fun showPrinterInfo() {
        val name = ble.deviceName ?: "Printer"
        say(
            when (ble.state) {
                BleTransport.State.DISCONNECTED -> "Printer not connected"
                BleTransport.State.CONNECTING -> "Connecting to printer..."
                BleTransport.State.READY -> "Connected to $name"
                BleTransport.State.PRINTING -> "Printing on $name"
            },
        )
    }

    /** The popup's action button: connects when disconnected, prints when ready. */
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
        val card = _ui.value.card ?: return
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

                val image =
                    if (settings.previewMatchesPrinter) {
                        // Reuse the preview if it was rendered for this card and this printer,
                        // so the printout is exactly what was shown.
                        lastPreview
                            ?.takeIf { it.cardName == card.name && it.driverName == driver.name }
                            ?.bitmap
                            ?: renderFor(card, driver)
                    } else {
                        classicImage(card, driver)
                    }

                val commands = driver.encode(
                    image,
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

    // ---- Rendering ----------------------------------------------------------------

    /** The driver whose geometry the preview should use, or null while no printer is connected. */
    private fun activeDriver(): PrinterDriver? =
        when (ble.state) {
            BleTransport.State.READY,
            BleTransport.State.PRINTING -> ble.target

            else -> null
        }

    /** The driver the preview is rendered for: none in classic mode. */
    private fun previewDriver(): PrinterDriver? =
        if (settings.previewMatchesPrinter) activeDriver() else null

    /** Art for [card], cached for the most recent card. Blocking: call from a background thread. */
    private fun artFor(card: Creature): Bitmap? {
        artCache?.let { if (it.first == card.name) return it.second }
        val art = artRepository.fetchArtCrop(card.name)
        artCache = card.name to art
        return art
    }

    /**
     * Same pipeline as printing: draw at the printer's width, bring it to the printer's
     * geometry and dither it. Without a printer, a plain 576 px render is used.
     */
    private fun renderFor(card: Creature, driver: PrinterDriver?): Bitmap {
        val art = artFor(card)
        if (driver == null) {
            return CardRenderer.renderCardBitmap(card, art, printWidth = FALLBACK_PREVIEW_WIDTH)
        }
        val drawn = CardRenderer.renderCardBitmap(card, art, printWidth = driver.renderWidth)
        return Monochrome.toMonochrome(driver.prepare(drawn))
    }

    /**
     * Classic behaviour: reuse the 576 px preview only for printers that render natively at
     * 576; the others (e.g. 384 or 624) get their own render.
     */
    private fun classicImage(card: Creature, driver: PrinterDriver): Bitmap {
        val preview = lastPreview
            ?.takeIf { it.cardName == card.name && it.driverName == null }
            ?.bitmap
        val drawn =
            if (preview != null && driver.renderWidth == FALLBACK_PREVIEW_WIDTH) {
                preview
            } else {
                CardRenderer.renderCardBitmap(card, artFor(card), printWidth = driver.renderWidth)
            }
        return driver.prepare(drawn)
    }

    private fun generatePreview(card: Creature) {
        viewModelScope.launch(Dispatchers.IO) {
            val driver = previewDriver()
            val bitmap = renderFor(card, driver)
            lastPreview = Rendered(card.name, driver?.name, bitmap)
            // Ignore the result if another card was rolled in the meantime.
            _ui.update {
                if (it.card?.name == card.name && it.showPreviewImage) {
                    it.copy(previewBitmap = bitmap)
                } else {
                    it
                }
            }
        }
    }

    /** Re-renders the preview when the printer, or the preview style, changed. */
    private fun refreshPreviewIfPrinterChanged() {
        val current = _ui.value
        val card = current.card ?: return
        if (!current.showPreviewImage) return

        val last = lastPreview
        if (last != null && last.cardName == card.name && last.driverName == previewDriver()?.name) {
            return
        }
        generatePreview(card)
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