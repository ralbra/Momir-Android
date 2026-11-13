package com.momir.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.momir.android.data.CustomFormatFilter
import com.momir.android.model.CardPool
import com.momir.android.model.CardSet
import com.momir.android.model.CardType
import com.momir.android.model.CustomFormat
import com.momir.android.model.LegalFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val sliderIn = fadeIn() + expandVertically()
private val sliderOut = fadeOut() + shrinkVertically()

private enum class WizardSection(val title: String) {
    GENERAL("General"),
    CARD_FILTERS("Card filters"),
    FORMAT_FILTERS("Format filters"),
    FINE_FILTER("Fine filter"),
}

/** Set types in the order the fine filter shows them; other types follow, sorted by name. */
private val SET_TYPE_ORDER = listOf(
    "expansion", "core", "masters", "draft_innovation", "commander", "alchemy", "funny",
)

private val SET_TYPE_LABELS = mapOf(
    "expansion" to "Expansions",
    "core" to "Core sets",
    "masters" to "Masters sets",
    "draft_innovation" to "Draft innovation",
    "commander" to "Commander",
    "alchemy" to "Alchemy",
    "funny" to "Un-sets",
    "promo" to "Promos",
    "box" to "Box sets",
    "duel_deck" to "Duel decks",
    "from_the_vault" to "From the Vault",
    "masterpiece" to "Masterpieces",
    "starter" to "Starter sets",
    "premium_deck" to "Premium decks",
    "spellbook" to "Spellbooks",
    "arsenal" to "Arsenal",
    "eternal" to "Eternal",
    "treasure_chest" to "Treasure chest",
    "planechase" to "Planechase",
    "archenemy" to "Archenemy",
    "vanguard" to "Vanguard",
    "memorabilia" to "Memorabilia",
    "token" to "Tokens",
    "minigame" to "Minigames",
)

private fun setTypeLabel(type: String): String =
    SET_TYPE_LABELS[type] ?: type.replace('_', ' ').replaceFirstChar { it.uppercase() }

private class SetGroup(val type: String, val sets: List<CardSet>)

private fun groupSets(sets: List<CardSet>): List<SetGroup> =
    sets.groupBy { it.type }
        .map { (type, list) -> SetGroup(type, list) }
        .sortedWith(
            compareBy<SetGroup> { SET_TYPE_ORDER.indexOf(it.type).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { setTypeLabel(it.type) },
        )

/**
 * Full-screen editor for a custom format: accordion sections (one open at a time) and a Save
 * button pinned at the bottom. Works on [draft], which lives in the ViewModel so it survives
 * rotation.
 */
@Composable
fun FormatWizard(
    ui: MainViewModel.UiState,
    draft: CustomFormat,
    vm: MainViewModel,
) {
    val original = ui.wizardOriginal
    val isNew = original == null
    val changed = if (isNew) draft != CustomFormat(id = draft.id) else draft != original

    var expanded by rememberSaveable { mutableStateOf<WizardSection?>(WizardSection.GENERAL) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val tryClose = { if (changed) confirmDiscard = true else vm.closeWizard() }

    // The pool object only changes when its status does.
    val pool: CardPool? = remember(ui.cardPool.state, ui.cardPool.updatedAt) { vm.cardPool() }

    // Counting does not depend on the name, so typing does not start a new count.
    val countKey = draft.copy(name = "")
    val matching by produceState<Int?>(initialValue = null, pool, countKey) {
        value = null
        value = pool?.let { p -> withContext(Dispatchers.Default) { CustomFormatFilter(p, countKey).matchingCards().size } }
    }

    val fineSets = rememberFineSets(pool, draft)
    var setQuery by rememberSaveable { mutableStateOf("") }
    // Open set groups, as a comma-separated list of set types so it survives rotation.
    var openGroups by rememberSaveable { mutableStateOf("") }

    val nameProblem = formatNameProblem(draft, ui.customFormats)
    val saveProblem = nameProblem ?: if (!draft.hasCardType) "Choose at least one card type to summon" else null

    Dialog(
        onDismissRequest = tryClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isNew) "New custom format" else "Edit format",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = tryClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close wizard")
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    if (pool == null) {
                        item(key = "pool") {
                            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "Custom formats need the custom format card data. You can set " +
                                            "up the format now; the card count and the fine filter appear " +
                                            "once the data is there.",
                                    )
                                    CardPoolSettings(ui.cardPool, onDownload = vm::downloadCardPool)
                                }
                            }
                        }
                    }

                    WizardSection.entries.forEach { section ->
                        item(key = section.name) {
                            AccordionSection(
                                title = section.title,
                                expanded = expanded == section,
                                onToggle = { expanded = if (expanded == section) null else section },
                            ) {
                                when (section) {
                                    WizardSection.GENERAL -> GeneralSection(draft, nameProblem, vm)
                                    WizardSection.CARD_FILTERS -> CardFilterSection(draft, vm)
                                    WizardSection.FORMAT_FILTERS -> FormatFilterSection(draft, vm)
                                    WizardSection.FINE_FILTER -> FineFilterIntro(pool, fineSets, draft, vm)
                                }
                            }
                        }
                        if (section == WizardSection.FINE_FILTER && expanded == section && fineSets != null) {
                            fineFilterItems(
                                sets = fineSets,
                                draft = draft,
                                query = setQuery,
                                onQuery = { setQuery = it },
                                openGroups = openGroups.split(',').filter { it.isNotEmpty() }.toSet(),
                                onOpenGroups = { openGroups = it.joinToString(",") },
                                vm = vm,
                            )
                        }
                    }
                }

                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when {
                            pool == null -> "Card count: needs the card data"
                            matching == null -> "Counting cards..."
                            matching == 0 -> "No card matches these settings"
                            else -> "$matching matching cards"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (matching == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    if (saveProblem != null) {
                        Text(
                            saveProblem,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!isNew) {
                            OutlinedButton(
                                onClick = { confirmDelete = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("Delete") }
                        }
                        Button(
                            onClick = vm::saveDraft,
                            enabled = saveProblem == null,
                            modifier = Modifier.weight(1f),
                        ) { Text("Save") }
                    }
                }
            }
        }

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Discard changes?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDiscard = false
                            vm.closeWizard()
                        },
                    ) { Text("Discard") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
                },
            )
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete \"${original?.name}\"?") },
                text = { Text("The format is removed for good.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            vm.deleteDraftFormat()
                        },
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun GeneralSection(draft: CustomFormat, nameProblem: String?, vm: MainViewModel) {
    OutlinedTextField(
        value = draft.name,
        onValueChange = { name -> vm.updateDraft { it.copy(name = name.take(40)) } },
        label = { Text("Name") },
        singleLine = true,
        isError = nameProblem != null && draft.name.isNotEmpty(),
        supportingText = nameProblem?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    val typesEnabled = !draft.anyCard
    val chances = draft.typeChances().toMap()
    val sliders = draft.usesTypeChances

    SettingSwitch("Summon a random Creature", draft.creature, enabled = typesEnabled) { v ->
        vm.updateDraft { it.withType(CardType.CREATURE, v) }
    }
    // Creatures get whatever the other types leave over.
    AnimatedVisibility(visible = sliders && draft.creature, enter = sliderIn, exit = sliderOut) {
        ChanceSlider(
            label = "Creature chance",
            value = draft.creatureChance.coerceAtLeast(0),
            shown = chances[CardType.CREATURE] ?: 0,
            enabled = false,
            onChange = {},
        )
    }

    CustomFormat.EXTRA_TYPES.forEach { type ->
        SettingSwitch("Summon a random ${type.word}", draft.isTypeOn(type), enabled = typesEnabled) { v ->
            vm.updateDraft { it.withType(type, v) }
        }
        AnimatedVisibility(visible = sliders && draft.isTypeOn(type), enter = sliderIn, exit = sliderOut) {
            ChanceSlider(
                label = "${type.word} chance",
                value = draft.storedChance(type),
                shown = chances[type] ?: 0,
                enabled = true,
                onChange = { v -> vm.updateDraft { it.withChance(type, v) } },
            )
        }
    }
    if (sliders && !draft.creature) {
        Hint("Creatures are off, so the sliders are weights: the chances shown are scaled to add up to 100%.")
    }

    SettingSwitch(
        "Summon a complete random card",
        draft.anyCard,
        description = "Any card type, lands included. The toggles above are ignored.",
    ) { v -> vm.updateDraft { it.copy(anyCard = v) } }

    HorizontalDivider()
    SettingSwitch("Discard a card as an additional cost", draft.discardCost) { v ->
        vm.updateDraft { it.copy(discardCost = v) }
    }
    SettingSwitch("Draw a card if you summon a common", draft.drawOnCommon) { v ->
        vm.updateDraft { it.copy(drawOnCommon = v) }
    }
    SettingSwitch("Use only once each turn", draft.oncePerTurn) { v ->
        vm.updateDraft { it.copy(oncePerTurn = v) }
    }
}

@Composable
private fun CardFilterSection(draft: CustomFormat, vm: MainViewModel) {
    SettingSwitch("Summon only Legendaries", draft.legendaryOnly) { v ->
        vm.updateDraft { it.copy(legendaryOnly = v) }
    }
    SettingSwitch("Allow Mythics", draft.mythic) { v -> vm.updateDraft { it.copy(mythic = v) } }
    SettingSwitch("Allow Rares", draft.rare) { v -> vm.updateDraft { it.copy(rare = v) } }
    SettingSwitch("Allow Uncommons", draft.uncommon) { v -> vm.updateDraft { it.copy(uncommon = v) } }
    SettingSwitch("Allow Commons", draft.common) { v -> vm.updateDraft { it.copy(common = v) } }
    Hint("A card counts if it has a printing with an allowed rarity in a set that is switched on.")
}

@Composable
private fun FormatFilterSection(draft: CustomFormat, vm: MainViewModel) {
    Hint("A card counts if it is legal in at least one of the switched on formats.")
    LegalFormat.entries.filter { it != LegalFormat.ALCHEMY }.forEach { format ->
        SettingSwitch(format.label, format in draft.formats) { v ->
            vm.updateDraft { it.copy(formats = if (v) it.formats + format else it.formats - format) }
        }
    }
    HorizontalDivider()
    SettingSwitch(
        "Universe Beyond",
        draft.universesBeyond,
        description = "Printings with the Universes Beyond stamp.",
    ) { v -> vm.updateDraft { it.copy(universesBeyond = v) } }
    SettingSwitch(
        "Alchemy",
        LegalFormat.ALCHEMY in draft.formats,
        description = "Digital Alchemy cards and sets.",
    ) { v ->
        vm.updateDraft {
            it.copy(formats = if (v) it.formats + LegalFormat.ALCHEMY else it.formats - LegalFormat.ALCHEMY)
        }
    }
    SettingSwitch(
        "Un-Sets",
        draft.unSets,
        description = "Unglued, Unhinged, Unstable, Unsanctioned, Unfinity and similar sets.",
    ) { v -> vm.updateDraft { it.copy(unSets = v) } }
}

/** Sets offered by the fine filter for the format filters of [draft], computed off the main thread. */
@Composable
private fun rememberFineSets(pool: CardPool?, draft: CustomFormat): List<CardSet>? {
    // Only the format filters decide which sets are offered.
    val key = CustomFormat(
        id = "",
        formats = draft.formats,
        universesBeyond = draft.universesBeyond,
        unSets = draft.unSets,
    )
    val sets by produceState<List<CardSet>?>(initialValue = null, pool, key) {
        value = null
        value = pool?.let { p -> withContext(Dispatchers.Default) { CustomFormatFilter(p, key).fineFilterSets() } }
    }
    return sets
}

@Composable
private fun FineFilterIntro(pool: CardPool?, sets: List<CardSet>?, draft: CustomFormat, vm: MainViewModel) {
    if (pool == null) {
        Hint("Needs the custom format card data.")
        return
    }
    if (sets == null) {
        Hint("Loading sets...")
        return
    }
    val off = sets.count { it.code in draft.disabledSets }
    Hint("Switch single sets of the selected formats on or off. ${sets.size - off} of ${sets.size} sets are on.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { vm.updateDraft { it.copy(disabledSets = it.disabledSets - sets.map { s -> s.code }.toSet()) } },
        ) { Text("All on") }
        OutlinedButton(
            onClick = { vm.updateDraft { it.copy(disabledSets = it.disabledSets + sets.map { s -> s.code }) } },
        ) { Text("All off") }
    }
}

/**
 * The fine filter's search field, set groups and sets, as separate lazy items so long groups
 * (there are hundreds of promo sets) stay smooth.
 */
private fun LazyListScope.fineFilterItems(
    sets: List<CardSet>,
    draft: CustomFormat,
    query: String,
    onQuery: (String) -> Unit,
    openGroups: Set<String>,
    onOpenGroups: (Set<String>) -> Unit,
    vm: MainViewModel,
) {
    val q = query.trim()
    val shown = if (q.isEmpty()) {
        sets
    } else {
        sets.filter { it.name.contains(q, ignoreCase = true) || it.code.equals(q, ignoreCase = true) }
    }
    val groups = groupSets(shown)

    item(key = "fine-search") {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search sets") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
    }
    if (groups.isEmpty()) {
        item(key = "fine-empty") {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) { Hint("No set matches \"$q\".") }
        }
    }

    groups.forEach { group ->
        val codes = group.sets.map { it.code }
        // While searching, all groups with hits are open.
        val groupOpen = q.isNotEmpty() || group.type in openGroups
        item(key = "group-${group.type}") {
            val onCount = codes.count { it !in draft.disabledSets }
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onOpenGroups(if (group.type in openGroups) openGroups - group.type else openGroups + group.type)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(setTypeLabel(group.type), fontWeight = FontWeight.Bold)
                        Text(
                            "$onCount/${codes.size} on",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { vm.updateDraft { it.copy(disabledSets = it.disabledSets - codes.toSet()) } },
                    ) { Text("All") }
                    TextButton(
                        onClick = { vm.updateDraft { it.copy(disabledSets = it.disabledSets + codes) } },
                    ) { Text("None") }
                    Icon(
                        imageVector = if (groupOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (groupOpen) "Collapse" else "Expand",
                    )
                }
            }
        }
        if (groupOpen) {
            items(group.sets, key = { "set-${it.code}" }) { set ->
                SetRow(set, set.code !in draft.disabledSets) { on ->
                    vm.updateDraft {
                        it.copy(disabledSets = if (on) it.disabledSets - set.code else it.disabledSets + set.code)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetRow(set: CardSet, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!on) }
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(set.name)
            Text(
                "${set.code.uppercase()} - ${set.releasedAt.take(4)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** A 1-100% slider. [shown] is the chance actually used, which can differ from [value] while creatures are off. */
@Composable
private fun ChanceSlider(
    label: String,
    value: Int,
    shown: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.padding(start = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
            Text("$shown%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = color)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 1f..100f,
            steps = 98,
            enabled = enabled,
        )
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
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
