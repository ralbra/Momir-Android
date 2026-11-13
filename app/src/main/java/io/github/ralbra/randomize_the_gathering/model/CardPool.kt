package io.github.ralbra.randomize_the_gathering.model

/**
 * Card data for custom Momir formats: every card type, with its printings, so rarity, set and
 * format filters can be applied. Built from Scryfall's "Default Cards" bulk file.
 * Classic Momir does not use this; it keeps the creature list from MTGJSON.
 */
class CardPool(
    /** Sets that have at least one printing in [cards], newest first. */
    val sets: List<CardSet>,
    val cards: List<PoolCard>,
    /** "updated_at" of the Scryfall bulk file the pool was built from. */
    val sourceUpdatedAt: String,
)

data class CardSet(
    val code: String,
    val name: String,
    /** Scryfall set type, e.g. "expansion", "commander", "funny", "alchemy". */
    val type: String,
    /** yyyy-mm-dd */
    val releasedAt: String,
) {
    val isUnSet: Boolean get() = type == SET_TYPE_FUNNY
    val isAlchemy: Boolean get() = type == SET_TYPE_ALCHEMY

    companion object {
        const val SET_TYPE_FUNNY = "funny"
        const val SET_TYPE_ALCHEMY = "alchemy"
    }
}

/**
 * Rarity of one printing. Scryfall's "special" and "bonus" (for example Timeshifted cards)
 * are counted as rare, so they are allowed whenever rares are allowed.
 */
enum class Rarity {
    COMMON,
    UNCOMMON,
    RARE,
    MYTHIC,
    ;

    companion object {
        fun fromScryfall(value: String): Rarity = when (value) {
            "common" -> COMMON
            "uncommon" -> UNCOMMON
            "mythic" -> MYTHIC
            else -> RARE
        }
    }
}

/** One printing of a card: in which set, at which rarity, and whether it is Universes Beyond. */
data class Printing(
    /** Index into [CardPool.sets]. */
    val setIndex: Int,
    val rarity: Rarity,
    val universesBeyond: Boolean,
)

/** Card types of the front face. [bit] values are stored in the data file: never change them. */
enum class CardType(val word: String, val bit: Int) {
    CREATURE("Creature", 1 shl 0),
    ARTIFACT("Artifact", 1 shl 1),
    ENCHANTMENT("Enchantment", 1 shl 2),
    INSTANT("Instant", 1 shl 3),
    SORCERY("Sorcery", 1 shl 4),
    LAND("Land", 1 shl 5),
    PLANESWALKER("Planeswalker", 1 shl 6),
    BATTLE("Battle", 1 shl 7),
    KINDRED("Kindred", 1 shl 8),
}

/**
 * Formats a custom format can filter by. [key] is Scryfall's legality key. [bit] values are
 * stored in the data file: never change them, only add new ones.
 */
enum class LegalFormat(val key: String, val label: String, val bit: Int) {
    STANDARD("standard", "Standard", 1 shl 0),
    PIONEER("pioneer", "Pioneer", 1 shl 1),
    HISTORIC("historic", "Historic", 1 shl 2),
    MODERN("modern", "Modern", 1 shl 3),
    BRAWL("brawl", "Brawl", 1 shl 4),
    LEGACY("legacy", "Legacy", 1 shl 5),
    COMPETITIVE_BRAWL("competitivebrawl", "Comp. Brawl", 1 shl 6),
    VINTAGE("vintage", "Vintage", 1 shl 7),
    TIMELESS("timeless", "Timeless", 1 shl 8),
    COMMANDER("commander", "Commander", 1 shl 9),
    PAUPER("pauper", "Pauper", 1 shl 10),
    OATHBREAKER("oathbreaker", "Oathbreaker", 1 shl 11),
    PENNY("penny", "Penny", 1 shl 12),
    ALCHEMY("alchemy", "Alchemy", 1 shl 13),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String): LegalFormat? = byKey[key]
    }
}

/** A card of the pool. [card] holds what is printed, in the same shape as Classic cards. */
data class PoolCard(
    val card: Creature,
    /** [CardType] bits of the front face. */
    val types: Int,
    /** Front face has the Legendary supertype. */
    val legendary: Boolean,
    /** [LegalFormat] bits of the formats the card is legal (or restricted) in. */
    val legalFormats: Int,
    val printings: List<Printing>,
) {
    fun hasType(type: CardType): Boolean = types and type.bit != 0
    fun isLegalIn(format: LegalFormat): Boolean = legalFormats and format.bit != 0
}
