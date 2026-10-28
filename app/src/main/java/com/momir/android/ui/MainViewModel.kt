package com.momir.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.momir.android.ble.BleTransport
import com.momir.android.data.AtomicBootstrapper
import com.momir.android.data.CardRepository
import com.momir.android.model.Creature
import com.momir.android.printer.PrintOptions
import com.momir.android.printer.PrinterDrivers
import com.momir.android.render.CardRenderer
import com.momir.android.printer.PrinterDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request


private const val PREFS_NAME = "momir_settings"
private const val KEY_BLACKENING = "blackening"
private const val DEFAULT_BLACKENING = 3
class MainViewModel(app: Application) : AndroidViewModel(app) {
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
        val blackening: Int = DEFAULT_BLACKENING,
    )
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(
        UiState(
            blackening = prefs.getInt(KEY_BLACKENING, DEFAULT_BLACKENING).coerceIn(1, 5),
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val ble = BleTransport<PrinterDriver>(app.applicationContext, PrinterDrivers::forName)
    private val repo = CardRepository(AtomicBootstrapper(app.filesDir))
    private val http = OkHttpClient()

    private val printInProgress = AtomicBoolean(false)

    private fun say(text: String) {
        _ui.update { it.copy(message = text) }
    }

    init {
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
                viewModelScope.launch {
                    ble.stateFlow.collect { s ->
                        _ui.update { it.copy(printerState = s) }
                    }
                }
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
    @SuppressLint("UseKtx")
    fun setBlackening(level: Int) {
        val v = level.coerceIn(1, 5)
        _ui.value = _ui.value.copy(blackening = v)
        prefs.edit().putInt(KEY_BLACKENING, v).apply()
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

        val image = buildScryfallImageUrl(c.name, "normal")
        _ui.value = state.copy(
            card = c,
            imageUrl = image,
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
    )private fun connectPrinter() {
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
                            fetchArtCrop(card.name),
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
    private fun fetchArtCrop(name: String): Bitmap? {
        val network = try {
            val req = Request.Builder()
                .url(buildScryfallImageUrl(name, "art_crop"))
                .header("User-Agent", "momir-android/1.0")
                .header("Accept", "image/*")
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body.bytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }

        return network ?: loadBundledDummyArt() ?: createGeneratedDummyArt(name)
    }

    private fun loadBundledDummyArt(): Bitmap? {
        val app = getApplication<Application>()

        try {
            app.assets.open("dummy.png").use { input ->
                return BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
        }

        return try {
            val resId = app.resources.getIdentifier(
                "dummy",
                "drawable",
                app.packageName,
            )
            if (resId != 0) {
                BitmapFactory.decodeResource(app.resources, resId)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("UseKtx")
    private fun createGeneratedDummyArt(name: String): Bitmap {
        val width = 1024
        val height = 1024
        val bmp = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.rgb(242, 244, 248))

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 55, 72)
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        canvas.drawRect(
            24f,
            24f,
            (width - 24).toFloat(),
            (height - 24).toFloat(),
            border,
        )

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 55, 72)
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(99, 115, 129)
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }

        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(75, 85, 99)
            textSize = 260f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("NO ART AVAILABLE", width / 2f, 150f, title)
        canvas.drawText("offline fallback", width / 2f, 210f, subtitle)
        canvas.drawText("?", width / 2f, 590f, mark)

        val maxName =
            if (name.length > 40) {
                name.take(37) + "..."
            } else {
                name
            }

        canvas.drawText(maxName, width / 2f, 910f, subtitle)
        return bmp
    }

    private fun generatePreview(card: Creature) {
        viewModelScope.launch(Dispatchers.IO) {
            val art = fetchArtCrop(card.name)
            val preview = CardRenderer.renderCardBitmap(card, art, printWidth = 576)
            _ui.value = _ui.value.copy(previewBitmap = preview)
        }
    }

    private fun buildScryfallImageUrl(name: String, version: String): String {
        return "https://api.scryfall.com/cards/named"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("exact", name)
            .addQueryParameter("format", "image")
            .addQueryParameter("version", version)
            .build()
            .toString()
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
