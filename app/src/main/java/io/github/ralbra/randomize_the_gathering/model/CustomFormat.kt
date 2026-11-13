package io.github.ralbra.randomize_the_gathering.model

/** A saved custom Momir format, made with the wizard. Defaults are the wizard's defaults. */
data class CustomFormat(
    val id: String,
    val name: String = "",

    // General: what can be summoned
    val creature: Boolean = true,
    val enchantment: Boolean = false,
    val artifact: Boolean = false,
    val instant: Boolean = false,
    val sorcery: Boolean = false,
    /** Any card type, lands included; the type toggles above are then ignored. */
    val anyCard: Boolean = false,
    /**
     * Chances in percent of the additional card types. With creatures on, creatures get the
     * rest (100 minus these, at least 1). With creatures off they are weights, scaled to 100%.
     * Kept while a type is off, so switching it back on restores the old value.
     */
    val enchantmentChance: Int = DEFAULT_CHANCE,
    val artifactChance: Int = DEFAULT_CHANCE,
    val instantChance: Int = DEFAULT_CHANCE,
    val sorceryChance: Int = DEFAULT_CHANCE,

    // General: the emblem's ability
    val discardCost: Boolean = true,
    val drawOnCommon: Boolean = false,
    val oncePerTurn: Boolean = true,

    // Card filters
    val legendaryOnly: Boolean = false,
    val mythic: Boolean = true,
    val rare: Boolean = true,
    val uncommon: Boolean = true,
    val common: Boolean = true,

    // Format filters: a card counts if it is legal in at least one of [formats].
    val formats: Set<LegalFormat> = DEFAULT_FORMATS,
    /** Printings with the Universes Beyond stamp. */
    val universesBeyond: Boolean = true,
    /** Printings in Un-sets; with this on they count even though they are not legal anywhere. */
    val unSets: Boolean = false,

    // Fine filter: codes of sets switched off.
    val disabledSets: Set<String> = emptySet(),
) {
    /** True when at least one card type can be summoned. */
    val hasCardType: Boolean get() = anyCard || creature || enchantment || artifact || instant || sorcery

    fun isTypeOn(type: CardType): Boolean = when (type) {
        CardType.CREATURE -> creature
        CardType.ENCHANTMENT -> enchantment
        CardType.ARTIFACT -> artifact
        CardType.INSTANT -> instant
        CardType.SORCERY -> sorcery
        else -> false
    }

    /** The stored chance of an additional type (see [enchantmentChance]). */
    fun storedChance(type: CardType): Int = when (type) {
        CardType.ENCHANTMENT -> enchantmentChance
        CardType.ARTIFACT -> artifactChance
        CardType.INSTANT -> instantChance
        CardType.SORCERY -> sorceryChance
        else -> 0
    }

    private fun withStoredChance(type: CardType, value: Int): CustomFormat = when (type) {
        CardType.ENCHANTMENT -> copy(enchantmentChance = value)
        CardType.ARTIFACT -> copy(artifactChance = value)
        CardType.INSTANT -> copy(instantChance = value)
        CardType.SORCERY -> copy(sorceryChance = value)
        else -> this
    }

    private fun withTypeFlag(type: CardType, on: Boolean): CustomFormat = when (type) {
        CardType.CREATURE -> copy(creature = on)
        CardType.ENCHANTMENT -> copy(enchantment = on)
        CardType.ARTIFACT -> copy(artifact = on)
        CardType.INSTANT -> copy(instant = on)
        CardType.SORCERY -> copy(sorcery = on)
        else -> this
    }

    /** Additional types that are switched on. */
    val activeExtraTypes: List<CardType> get() = EXTRA_TYPES.filter { isTypeOn(it) }

    /** What creatures get with creatures on: the rest of the additional types' chances. */
    val creatureChance: Int get() = 100 - activeExtraTypes.sumOf { storedChance(it) }

    /** True when the type chance sliders apply: more than one type, and not "any card". */
    val usesTypeChances: Boolean
        get() = !anyCard && activeExtraTypes.isNotEmpty() && (creature || activeExtraTypes.size > 1)

    /** The highest chance [type] can get without pushing creatures below 1%. */
    fun maxChance(type: CardType): Int =
        if (creature) 99 - activeExtraTypes.filter { it != type }.sumOf { storedChance(it) } else 100

    fun withChance(type: CardType, value: Int): CustomFormat =
        withStoredChance(type, value.coerceIn(1, maxChance(type).coerceAtLeast(1)))

    /** Switches a card type on or off and keeps the chances adding up. */
    fun withType(type: CardType, on: Boolean): CustomFormat {
        var f = withTypeFlag(type, on)
        if (on && type != CardType.CREATURE) f = f.withStoredChance(type, f.storedChance(type).coerceIn(1, 100))
        return f.fitChances(keep = type.takeIf { on && it != CardType.CREATURE })
    }

    /** Repairs chances that leave creatures below 1%, e.g. from older saved data. */
    fun fitted(): CustomFormat = fitChances(keep = null)

    /**
     * With creatures on, scales the additional types down until creatures keep at least 1%.
     * [keep] is changed only when nothing else is left to take from.
     */
    private fun fitChances(keep: CardType?): CustomFormat {
        if (!creature) return this
        var f = this
        while (f.creatureChance < 1) {
            val others = f.activeExtraTypes.filter { f.storedChance(it) > 1 }
            val from = others.filter { it != keep }.maxByOrNull { f.storedChance(it) }
                ?: others.maxByOrNull { f.storedChance(it) }
                ?: break
            val over = 1 - f.creatureChance
            val value = f.storedChance(from)
            f = f.withStoredChance(from, (value - over).coerceAtLeast(maxOf(1, value / 2)))
        }
        return f
    }

    /**
     * The chance of each card type in percent, adding up to 100, or empty when every allowed
     * card is equally likely ("any card", or a single type).
     */
    fun typeChances(): List<Pair<CardType, Int>> {
        if (!usesTypeChances) return emptyList()
        val extras = activeExtraTypes
        if (creature) return listOf(CardType.CREATURE to creatureChance) + extras.map { it to storedChance(it) }

        // Creatures off: weights scaled to 100, rounding by largest remainder.
        val weights = extras.map { storedChance(it) }
        val total = weights.sum()
        val exact = weights.map { it * 100.0 / total }
        val floors = exact.map { it.toInt() }.toMutableList()
        var left = 100 - floors.sum()
        exact.indices.sortedByDescending { exact[it] - floors[it] }.forEach { i ->
            if (left > 0) {
                floors[i]++
                left--
            }
        }
        return extras.zip(floors)
    }

    fun allowsRarity(rarity: Rarity): Boolean = when (rarity) {
        Rarity.COMMON -> common
        Rarity.UNCOMMON -> uncommon
        Rarity.RARE -> rare
        Rarity.MYTHIC -> mythic
    }

    companion object {
        /** Every format except Alchemy is on by default. */
        const val DEFAULT_CHANCE = 20

        /** Card types that get a chance slider, in the wizard's order. */
        val EXTRA_TYPES = listOf(CardType.ENCHANTMENT, CardType.ARTIFACT, CardType.INSTANT, CardType.SORCERY)

        val DEFAULT_FORMATS: Set<LegalFormat> = LegalFormat.entries.filter { it != LegalFormat.ALCHEMY }.toSet()
    }
}

/** What is selected in the start screen's format dropdown. */
sealed interface FormatChoice {
    /** Stored in the settings. */
    val key: String

    data object Classic : FormatChoice {
        override val key = "CLASSIC"
    }

    /** "<-- New Custom Game -->": opens the wizard for a new format. */
    data object NewCustom : FormatChoice {
        override val key = "NEW_CUSTOM"
    }

    data class Custom(val id: String) : FormatChoice {
        override val key get() = PREFIX + id
    }

    companion object {
        private const val PREFIX = "custom:"

        fun fromKey(key: String?): FormatChoice = when {
            key == NewCustom.key -> NewCustom
            key != null && key.startsWith(PREFIX) -> Custom(key.removePrefix(PREFIX))
            else -> Classic
        }
    }
}
