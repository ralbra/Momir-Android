package com.momir.android.data

import com.momir.android.model.CardPool
import com.momir.android.model.CardSet
import com.momir.android.model.CustomFormat
import com.momir.android.model.PoolCard
import com.momir.android.model.Printing
import com.momir.android.model.Rarity
import kotlin.random.Random

/** The emblem's rules text for a custom format, built from its options. */
object EmblemText {
    fun forFormat(f: CustomFormat): String {
        val cost = if (f.discardCost) "{X}, Discard a card" else "{X}"

        val permanents = if (f.anyCard) {
            true
        } else {
            f.creature || f.enchantment || f.artifact
        }
        val spells = f.anyCard || f.instant || f.sorcery
        val legendary = if (f.legendaryOnly) "legendary " else ""

        val types = if (f.anyCard) {
            emptyList()
        } else {
            listOfNotNull(
                "creature".takeIf { f.creature },
                "enchantment".takeIf { f.enchantment },
                "artifact".takeIf { f.artifact },
                "instant".takeIf { f.instant },
                "sorcery".takeIf { f.sorcery },
            )
        }
        val card = withArticle(legendary + (types.joinOr() + " card").trim())
        val spellTypes = if (f.anyCard) listOf("instant", "sorcery") else types.filter { it == "instant" || it == "sorcery" }
        val spellCard = withArticle(spellTypes.joinOr() + " card")

        val effect = when {
            permanents && !spells ->
                "Create a token that's a copy of $card with mana value X chosen at random."
            spells && !permanents ->
                "Copy $card with mana value X chosen at random. You may cast the copy without paying its mana cost."
            else ->
                "Choose $card with mana value X at random. If it's $spellCard, copy it and you may cast " +
                    "the copy without paying its mana cost. Otherwise, create a token that's a copy of it."
        }
        val draw = if (f.drawOnCommon) " If that card is common, draw a card." else ""
        val timing = if (f.oncePerTurn) {
            " Activate only as a sorcery and only once each turn."
        } else {
            " Activate only as a sorcery."
        }
        return "$cost: $effect$draw$timing"
    }

    private fun List<String>.joinOr(): String = when (size) {
        0 -> ""
        1 -> first()
        2 -> "${this[0]} or ${this[1]}"
        else -> dropLast(1).joinToString(", ") + ", or " + last()
    }

    private fun withArticle(phrase: String): String =
        (if (phrase.first().lowercaseChar() in "aeiou") "an " else "a ") + phrase
}

/** A card rolled in a custom format, with the printing that was picked for it. */
data class CustomRoll(
    val card: PoolCard,
    val printing: Printing,
    val set: CardSet,
) {
    val isCommon: Boolean get() = printing.rarity == Rarity.COMMON
}

/**
 * Rolls cards for one custom format. The allowed cards are sorted by mana value once, so a roll
 * is just two random picks: a card (each card is equally likely, however often it was printed)
 * and then one of its allowed printings, whose rarity counts for "draw if common".
 */
class CustomRoller(
    private val pool: CardPool,
    val format: CustomFormat,
) {
    private val byManaValue: Map<Int, List<Pair<PoolCard, List<Printing>>>> by lazy {
        val filter = CustomFormatFilter(pool, format)
        pool.cards
            .mapNotNull { card -> filter.allowedPrintings(card).takeIf { it.isNotEmpty() }?.let { card to it } }
            .groupBy { it.first.card.manaValue }
    }

    /** Prepares the lookup; call it off the main thread before the first roll. */
    fun warmUp() {
        byManaValue.size
    }

    fun count(manaValue: Int): Int = byManaValue[manaValue]?.size ?: 0

    fun roll(manaValue: Int, random: Random = Random.Default): CustomRoll? {
        val (card, printings) = byManaValue[manaValue]?.randomOrNull(random) ?: return null
        val printing = printings.random(random)
        return CustomRoll(card, printing, pool.sets[printing.setIndex])
    }

    fun isFor(pool: CardPool, format: CustomFormat): Boolean = this.pool === pool && this.format == format
}

/** "NEO - Common": printed at the bottom of a ticket rolled in a custom format. */
fun CustomRoll.footer(): String =
    "${set.code.uppercase()} - ${printing.rarity.name.lowercase().replaceFirstChar { it.uppercase() }}"
