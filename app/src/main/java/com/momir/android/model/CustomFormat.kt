package com.momir.android.model

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

    fun allowsRarity(rarity: Rarity): Boolean = when (rarity) {
        Rarity.COMMON -> common
        Rarity.UNCOMMON -> uncommon
        Rarity.RARE -> rare
        Rarity.MYTHIC -> mythic
    }

    companion object {
        /** Every format except Alchemy is on by default. */
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
