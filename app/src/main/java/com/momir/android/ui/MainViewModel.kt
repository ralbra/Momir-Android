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
import com.momir.android.data.CardPoolBootstrapper
import com.momir.android.data.CardPoolRepository
import com.momir.android.data.CardRepository
import com.momir.android.data.CustomFormatStore
import com.momir.android.data.CustomRoller
import com.momir.android.data.EmblemText
import com.momir.android.data.footer
import com.momir.android.data.searchPool
import com.momir.android.data.SettingsStore
import com.momir.android.model.CardPool
import com.momir.android.model.Creature
import com.momir.android.model.CustomFormat
import com.momir.android.model.FormatChoice
import com.momir.android.printer.PrintOptions
import com.momir.android.printer.PrinterDriver
import com.momir.android.printer.PrinterDrivers
import com.momir.android.render.CardRenderer
import com.momir.android.render.ImageOps
import com.momir.android.render.Monochrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Width of the grayscale preview shown while no printer is connected (or in classic mode). */
private const val FALLBACK_PREVIEW_WIDTH = 576

private const val MAX_MANA_VALUE = 16

/** The emblem card printed at the start of a game, and the card whose art it uses. */
private const val EMBLEM_NAME = "Factory of Momir Vig"
private const val EMBLEM_TYPE = "Emblem"
private const val EMBLEM_ART_NAME = "Momir Vig, Simic Visionary"

/** The sample ticket printed from the printer settings; it borrows the emblem's art. */
private const val TEST_TICKET_NAME = "Momir test ticket"

/** Thermal printers print about 8 dots per millimetre (203 dpi). */
private const val DOTS_PER_MM = 8


// @JvmOverloads keeps a constructor that takes only the Application, which is what the
// default ViewModel factory looks for; the other parameters can be replaced in tests.
class MainViewModel @JvmOverloads constructor(
    app: Application,
    private val artRepository: ArtRepository = ArtRepository(app),
    private val settings: SettingsStore = SettingsStore(app),
    private val formatStore: CustomFormatStore = CustomFormatStore(app),
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
        val showPreviewImage: Boolean = true,
        val card: Creature? = null,
        val imageUrl: String? = null,
        val previewBitmap: Bitmap? = null,
        val printerState: BleTransport.State = BleTransport.State.DISCONNECTED,
        val printerName: String? = null,
        val printWidth: Int = 576,
        val blackening: Int = SettingsStore.DEFAULT_BLACKENING,
        val previewMatchesPrinter: Boolean = SettingsStore.DEFAULT_PREVIEW_MATCHES_PRINTER,
        val printArt: Boolean = SettingsStore.DEFAULT_PRINT_ART,
        val autoPrint: Boolean = SettingsStore.DEFAULT_AUTO_PRINT,
        val extraFeedMm: Int = 0,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        // Life counters and layout: one entry in [lives] per player
        val lives: List<Int> = List(SettingsStore.DEFAULT_PLAYER_COUNT) {
            SettingsStore.DEFAULT_STARTING_LIFE
        },
        val startingLife: Int = SettingsStore.DEFAULT_STARTING_LIFE,
        val rotatePlayerOne: Boolean = true,
        val barHeightPercent: Int = SettingsStore.DEFAULT_BAR_HEIGHT_PERCENT,
        val keepScreenOn: Boolean = true,
        val showRollDialog: Boolean = false,
        val cacheArt: Boolean = SettingsStore.DEFAULT_CACHE_ART,
        // Start screen
        val showStartScreen: Boolean = true,
        val playerCount: Int = SettingsStore.DEFAULT_PLAYER_COUNT,
        val formatChoice: FormatChoice = FormatChoice.Classic,
        /** Saved custom formats, in the order they were made. */
        val customFormats: List<CustomFormat> = emptyList(),
        /** The format being edited in the wizard; null while the wizard is closed. */
        val wizardDraft: CustomFormat? = null,
        /** The saved version of [wizardDraft], or null for a new format. */
        val wizardOriginal: CustomFormat? = null,
        val playWithoutPrinter: Boolean = SettingsStore.DEFAULT_PLAY_WITHOUT_PRINTER,
        // Card data for custom formats (Scryfall), separate from the Classic creature list
        val cardPool: CardPoolStatus = CardPoolStatus(),
    )

    enum class CardPoolState {
        MISSING,
        LOADING,
        DOWNLOADING,
        READY,
        FAILED,
    }

    data class CardPoolStatus(
        val state: CardPoolState = CardPoolState.MISSING,
        val cards: Int = 0,
        val sets: Int = 0,
        /** "updated_at" of the Scryfall file, e.g. 2026-11-12T22:06:24. */
        val updatedAt: String = "",
        /** What is happening right now, or the last error. */
        val detail: String = "",
    )

    /** A rendered card, tagged with what it was rendered for. [driverName] is null for the fallback. */
    private class Rendered(
        val cardName: String,
        val driverName: String?,
        val bitmap: Bitmap,
    )

    /** The art of a card: the front face and, for double-faced cards, the back face. */
    private class CardArt(
        val front: Bitmap?,
        val back: Bitmap?,
    )

    private val _ui = MutableStateFlow(
        UiState(
            blackening = settings.blackening,
            previewMatchesPrinter = settings.previewMatchesPrinter,
            printArt = settings.printArt,
            autoPrint = settings.autoPrint,
            extraFeedMm = settings.extraFeedMm,
            themeMode = ThemeMode.entries.firstOrNull { it.name == settings.theme } ?: ThemeMode.SYSTEM,
            lives = List(settings.playerCount) { settings.startingLife },
            startingLife = settings.startingLife,
            rotatePlayerOne = settings.rotatePlayerOne,
            barHeightPercent = settings.barHeightPercent,
            keepScreenOn = settings.keepScreenOn,
            cacheArt = settings.cacheArt,
            playerCount = settings.playerCount,
            customFormats = formatStore.load(),
            formatChoice = validChoice(FormatChoice.fromKey(settings.momirFormat), formatStore.load()),
            playWithoutPrinter = settings.playWithoutPrinter,
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val ble = BleTransport<PrinterDriver>(app.applicationContext, PrinterDrivers::forName)
    private val repo = CardRepository(
        AtomicBootstrapper(
            app.filesDir,
            seed = {
                try {
                    app.assets.open("creatures_min.json")
                } catch (_: IOException) {
                    null
                }
            },
        ),
    )

    private val cardPoolRepo = CardPoolRepository(CardPoolBootstrapper(app.filesDir))

    private val printInProgress = AtomicBoolean(false)

    /** Rolls for the selected custom format; rebuilt when the format or the card data changes. */
    @Volatile
    private var customRoller: CustomRoller? = null

    // The art of the current card, so a printer change does not download it again.
    @Volatile
    private var artCache: Pair<String, CardArt>? = null

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
            loadStoredCardPool()
        }
    }

    // ---- Card data for custom formats ---------------------------------------------

    private fun updatePool(transform: (CardPoolStatus) -> CardPoolStatus) {
        _ui.update { it.copy(cardPool = transform(it.cardPool)) }
    }

    private fun readyStatus(pool: CardPool) = CardPoolStatus(
        state = CardPoolState.READY,
        cards = pool.cards.size,
        sets = pool.sets.size,
        updatedAt = pool.sourceUpdatedAt.substringBefore('.').replace('T', ' '),
    )

    /** Loads the stored custom format data, if it was downloaded before. Blocking. */
    private fun loadStoredCardPool() {
        if (!cardPoolRepo.hasStoredPool()) return
        updatePool { it.copy(state = CardPoolState.LOADING, detail = "Loading card data...") }
        val pool = cardPoolRepo.loadStored()
        updatePool {
            if (pool != null) {
                readyStatus(pool)
            } else {
                CardPoolStatus(
                    state = CardPoolState.FAILED,
                    detail = "Stored card data could not be read. Download it again.",
                )
            }
        }
    }

    /** Downloads (or updates) the custom format card data from Scryfall, about 80 MB. */
    fun downloadCardPool() {
        val current = _ui.value.cardPool.state
        if (current == CardPoolState.DOWNLOADING || current == CardPoolState.LOADING) return
        updatePool { it.copy(state = CardPoolState.DOWNLOADING, detail = "Looking up the card file...") }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pool = cardPoolRepo.download(
                    onStage = { stage ->
                        val text = when (stage) {
                            CardPoolBootstrapper.Stage.LOOKING_UP -> "Looking up the card file..."
                            CardPoolBootstrapper.Stage.DOWNLOADING -> "Downloading..."
                            CardPoolBootstrapper.Stage.PROCESSING -> "Processing cards..."
                            CardPoolBootstrapper.Stage.LOADING -> "Loading card data..."
                        }
                        updatePool { it.copy(detail = text) }
                    },
                    onProgress = { done, total ->
                        val mb = done / 1_000_000
                        val text =
                            if (total > 0) "Downloading $mb / ${total / 1_000_000} MB" else "Downloading $mb MB"
                        updatePool { it.copy(detail = text) }
                    },
                )
                _ui.update { it.copy(cardPool = readyStatus(pool)) }
            } catch (e: Exception) {
                // Keep whatever was loaded before; only report the error.
                val previous = cardPoolRepo.pool
                updatePool {
                    (if (previous != null) readyStatus(previous) else CardPoolStatus(state = CardPoolState.FAILED))
                        .copy(detail = "Download failed: ${e.message}")
                }
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

    // ---- Life counters ------------------------------------------------------------

    /** [player] is a 0-based index into the life totals. */
    fun changeLife(player: Int, delta: Int) {
        _ui.update { state ->
            if (player !in state.lives.indices) {
                state
            } else {
                state.copy(
                    lives = state.lives.mapIndexed { index, life ->
                        if (index == player) life + delta else life
                    },
                )
            }
        }
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

    fun setCacheArt(v: Boolean) {
        settings.cacheArt = v
        _ui.update { it.copy(cacheArt = v) }
    }

    // ---- Start screen -------------------------------------------------------------

    fun setPlayerCount(n: Int) {
        settings.playerCount = n
        _ui.update { it.copy(playerCount = settings.playerCount) }
    }

    fun setFormatChoice(choice: FormatChoice) {
        val valid = validChoice(choice, _ui.value.customFormats)
        settings.momirFormat = valid.key
        _ui.update { it.copy(formatChoice = valid) }
    }

    /** The selected custom format, or null for Classic and New Custom Game. */
    fun selectedCustomFormat(): CustomFormat? = selectedCustomFormat(_ui.value)

    /** The custom format card data, or null while it is not downloaded or still loading. */
    fun cardPool(): CardPool? = cardPoolRepo.pool

    /** Opens the wizard: for a new format with [id] null, otherwise to edit the saved one. */
    fun openWizard(id: String? = null) {
        val saved = id?.let { wanted -> _ui.value.customFormats.firstOrNull { it.id == wanted } }
        val draft = saved ?: CustomFormat(id = UUID.randomUUID().toString())
        _ui.update { it.copy(wizardDraft = draft, wizardOriginal = saved) }
    }

    fun updateDraft(transform: (CustomFormat) -> CustomFormat) {
        _ui.update { state -> state.copy(wizardDraft = state.wizardDraft?.let(transform)) }
    }

    /** Saves the wizard's format and selects it. Does nothing if the name is missing or taken. */
    fun saveDraft() {
        val draft = _ui.value.wizardDraft ?: return
        val format = draft.copy(name = draft.name.trim())
        if (formatNameProblem(format, _ui.value.customFormats) != null || !format.hasCardType) return
        val list = formatStore.save(format)
        settings.momirFormat = FormatChoice.Custom(format.id).key
        _ui.update {
            it.copy(
                customFormats = list,
                formatChoice = FormatChoice.Custom(format.id),
                wizardDraft = null,
                wizardOriginal = null,
                message = "Saved format ${format.name}",
            )
        }
    }

    /** Deletes the format being edited and goes back to Classic. */
    fun deleteDraftFormat() {
        val original = _ui.value.wizardOriginal ?: return
        val list = formatStore.delete(original.id)
        settings.momirFormat = FormatChoice.Classic.key
        _ui.update {
            it.copy(
                customFormats = list,
                formatChoice = FormatChoice.Classic,
                wizardDraft = null,
                wizardOriginal = null,
                message = "Deleted format ${original.name}",
            )
        }
    }

    fun closeWizard() {
        _ui.update { it.copy(wizardDraft = null, wizardOriginal = null) }
    }

    fun setPlayWithoutPrinter(v: Boolean) {
        settings.playWithoutPrinter = v
        _ui.update { it.copy(playWithoutPrinter = v) }
    }

    /**
     * Starts a standard game: one fresh life counter per player. The Momir emblem is shown
     * first, in the same popup as a rolled card, so it can be printed.
     */
    fun startGame() {
        val format = selectedCustomFormat()
        val emblem = if (format != null) customEmblemCard(format) else emblemCard()
        if (format != null) {
            // Sort the format's cards by mana value now, so the first roll is instant.
            rollerFor(format)?.let { roller -> viewModelScope.launch(Dispatchers.Default) { roller.warmUp() } }
        }
        val showPreview = _ui.value.showPreviewImage

        _ui.update { state ->
            state.copy(
                showStartScreen = false,
                lives = List(state.playerCount) { state.startingLife },
                card = emblem,
                imageUrl = null,
                previewBitmap = null,
                showRollDialog = true,
                message = "Momir emblem",
            )
        }

        if (showPreview) {
            generatePreview(emblem)
        }
        autoPrint(emblem)
    }

    fun openStartScreen() {
        _ui.update { it.copy(showStartScreen = true, showRollDialog = false) }
    }

    /** The emblem card; its rules text comes from the settings so it can be edited later. */
    private fun emblemCard(): Creature = Creature(
        name = EMBLEM_NAME,
        manaValue = 0,
        type = EMBLEM_TYPE,
        power = "",
        toughness = "",
        text = settings.emblemText,
        manaCost = "",
        isFunny = false,
    )

    /** The emblem of a custom format: the text follows the format's options. */
    private fun customEmblemCard(format: CustomFormat): Creature = Creature(
        name = EMBLEM_NAME,
        manaValue = 0,
        type = "$EMBLEM_TYPE \u2014 ${format.name}",
        power = "",
        toughness = "",
        text = EmblemText.forFormat(format),
        manaCost = "",
        isFunny = false,
    )

    private fun rollerFor(format: CustomFormat): CustomRoller? {
        val pool = cardPoolRepo.pool ?: return null
        customRoller?.takeIf { it.isFor(pool, format) }?.let { return it }
        return CustomRoller(pool, format).also { customRoller = it }
    }

    /** For the future editing screen: an empty text brings back the default. */
    fun setEmblemText(text: String) {
        settings.emblemText = text
    }

    // ---- Rolling ------------------------------------------------------------------

    fun changeMv(delta: Int) {
        _ui.update { it.copy(selectedMv = (it.selectedMv + delta).coerceIn(0, MAX_MANA_VALUE)) }
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

    fun setAutoPrint(v: Boolean) {
        settings.autoPrint = v
        _ui.update { it.copy(autoPrint = v) }
    }

    fun setExtraFeedMm(mm: Int) {
        settings.extraFeedMm = mm
        _ui.update { it.copy(extraFeedMm = settings.extraFeedMm) }
    }

    fun setThemeMode(mode: ThemeMode) {
        settings.theme = mode.name
        _ui.update { it.copy(themeMode = mode) }
    }

    fun setPrintArt(v: Boolean) {
        settings.printArt = v
        _ui.update { it.copy(printArt = v) }
        // The preview shows the printout: draw the visible card again, with or without art.
        lastPreview = null
        val current = _ui.value
        if (current.showPreviewImage && current.card != null) {
            _ui.update { it.copy(previewBitmap = null) }
            generatePreview(current.card)
        }
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
        selectedCustomFormat(state)?.let { format ->
            rollCustom(format, state.selectedMv)
            return
        }
        val c = repo.getRandomCreature(state.selectedMv, includeFunny = false)

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

        presentCard(c, "Rolled ${c.name}")
    }

    private fun rollCustom(format: CustomFormat, manaValue: Int) {
        val roller = rollerFor(format)
        val rolled = roller?.roll(manaValue)
        if (rolled == null) {
            _ui.update {
                it.copy(
                    card = null,
                    imageUrl = null,
                    previewBitmap = null,
                    showRollDialog = true,
                    message = if (roller == null) {
                        "The custom format card data is not loaded"
                    } else {
                        "No cards at MV $manaValue in ${format.name}"
                    },
                )
            }
            return
        }

        val card = rolled.card.card.copy(footer = rolled.footer())
        val rarity = rolled.printing.rarity.name.lowercase()
        val message = if (format.drawOnCommon && rolled.isCommon) {
            "Rolled ${card.name} (common): draw a card"
        } else {
            "Rolled ${card.name} ($rarity)"
        }
        presentCard(card, message)
    }

    /** Debug: cards whose name matches [query], best matches first. */
    fun searchCards(query: String): List<Creature> = repo.searchByName(query)

    /** Debug: shows a chosen card in the roll popup, as if it had been rolled. */
    fun rollCard(card: Creature) {
        presentCard(card, "Showing ${card.name}", print = false)
    }

    /** Debug: cards from the custom format card data that are not in [exclude], best matches first. */
    fun searchPoolCards(query: String, exclude: Set<String>): List<Creature> =
        cardPoolRepo.pool?.let { pool -> searchPool(pool, query, exclude) }.orEmpty()

    /** Prints [card] right away when auto print is on and a printer is ready. */
    private fun autoPrint(card: Creature) {
        if (settings.autoPrint && !_ui.value.playWithoutPrinter && ble.state == BleTransport.State.READY) {
            printCard(card)
        }
    }

    /** Prints a sample ticket to check the darkness, the paper feed and the connection. */
    fun printTestTicket() {
        val state = _ui.value
        val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
        printCard(
            Creature(
                name = TEST_TICKET_NAME,
                manaValue = 5,
                type = "Printer test \u2014 ${state.printerName ?: "printer"}",
                power = "5",
                toughness = "5",
                text = "Darkness ${state.blackening} of 5. Extra paper ${state.extraFeedMm} mm. " +
                    "Art ${if (state.printArt) "on" else "off"}. Printed $time.",
                manaCost = "{W}{U}{B}{R}{G}",
                isFunny = false,
                footer = "TEST - Common",
            ),
        )
    }

    private fun presentCard(card: Creature, message: String, print: Boolean = true) {
        val showPreview = _ui.value.showPreviewImage

        _ui.update {
            it.copy(
                card = card,
                imageUrl = artRepository.imageUrl(card.name),
                previewBitmap = null,
                showRollDialog = true,
                message = message,
            )
        }

        if (showPreview) {
            generatePreview(card)
        }
        if (print) autoPrint(card)
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
        printCard(card)
    }

    private fun printCard(card: Creature) {
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

                val ticket =
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

                val image = ImageOps.padBottom(ticket, settings.extraFeedMm * DOTS_PER_MM)
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

    /** The card whose art is used for [card]: the emblem borrows Momir Vig's art. */
    private fun artNameFor(card: Creature): String =
        if (card.name == EMBLEM_NAME || card.name == TEST_TICKET_NAME) EMBLEM_ART_NAME else card.name

    /**
     * Art for [card], cached for the most recent card. The back face art is only fetched for
     * double-faced cards whose back has its own artwork. Blocking: call from a background thread.
     */
    private fun artFor(card: Creature): CardArt {
        artCache?.let { if (it.first == card.name) return it.second }

        val artName = artNameFor(card)
        val front = artRepository.fetchArtCrop(artName)
        val back =
            if (card.backHasOwnArt) artRepository.fetchArtCrop(artName, back = true) else null

        val art = CardArt(front, back)
        artCache = card.name to art
        return art
    }

    /**
     * Same pipeline as printing: draw at the printer's width, bring it to the printer's
     * geometry and dither it. Without a printer, a plain 576 px render is used.
     */
    /** No art at all when art printing is off; nothing is downloaded then. */
    private fun printedArtFor(card: Creature): CardArt =
        if (settings.printArt) artFor(card) else CardArt(null, null)

    private fun renderFor(card: Creature, driver: PrinterDriver?): Bitmap {
        val art = printedArtFor(card)
        if (driver == null) {
            return CardRenderer.renderCardBitmap(
                card,
                art.front,
                printWidth = FALLBACK_PREVIEW_WIDTH,
                backArt = art.back,
            )
        }
        val drawn = CardRenderer.renderCardBitmap(
            card,
            art.front,
            printWidth = driver.renderWidth,
            backArt = art.back,
        )
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
                val art = printedArtFor(card)
                CardRenderer.renderCardBitmap(
                    card,
                    art.front,
                    printWidth = driver.renderWidth,
                    backArt = art.back,
                )
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
/** A saved custom id that no longer exists falls back to Classic. */
private fun validChoice(choice: FormatChoice, formats: List<CustomFormat>): FormatChoice =
    if (choice is FormatChoice.Custom && formats.none { it.id == choice.id }) FormatChoice.Classic else choice

internal fun selectedCustomFormat(state: MainViewModel.UiState): CustomFormat? =
    (state.formatChoice as? FormatChoice.Custom)?.let { c -> state.customFormats.firstOrNull { it.id == c.id } }

/** Why [format] cannot be saved under its name, or null if the name is fine. */
internal fun formatNameProblem(format: CustomFormat, saved: List<CustomFormat>): String? {
    val name = format.name.trim()
    return when {
        name.isEmpty() -> "Enter a name to save the format"
        name.equals("Classic", ignoreCase = true) -> "\"Classic\" is taken by the standard format"
        saved.any { it.id != format.id && it.name.trim().equals(name, ignoreCase = true) } ->
            "A format named \"$name\" already exists"
        else -> null
    }
}

