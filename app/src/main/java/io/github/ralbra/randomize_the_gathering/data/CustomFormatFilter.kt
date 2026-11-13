package io.github.ralbra.randomize_the_gathering.data

import io.github.ralbra.randomize_the_gathering.model.CardPool
import io.github.ralbra.randomize_the_gathering.model.CardSet
import io.github.ralbra.randomize_the_gathering.model.CardType
import io.github.ralbra.randomize_the_gathering.model.CustomFormat
import io.github.ralbra.randomize_the_gathering.model.LegalFormat
import io.github.ralbra.randomize_the_gathering.model.PoolCard
import io.github.ralbra.randomize_the_gathering.model.Printing

/**
 * Applies a [CustomFormat] to the [CardPool].
 *
 * A printing is allowed when its rarity is allowed, its set is not switched off in the fine
 * filter, and it passes the set groups (Universes Beyond, Alchemy and Un-sets are removed when
 * switched off). A card is allowed when its front face has a chosen type (and is legendary,
 * if required) and it has an allowed printing that is legal in one of the chosen formats, or
 * is in an Un-set with Un-sets switched on.
 */
class CustomFormatFilter(
    private val pool: CardPool,
    private val format: CustomFormat,
) {
    private val formatBits = format.formats.fold(0) { acc, f -> acc or f.bit }
    private val alchemyOn = LegalFormat.ALCHEMY in format.formats

    private val typeBits: Int =
        if (format.anyCard) {
            -1
        } else {
            listOfNotNull(
                CardType.CREATURE.takeIf { format.creature },
                CardType.ENCHANTMENT.takeIf { format.enchantment },
                CardType.ARTIFACT.takeIf { format.artifact },
                CardType.INSTANT.takeIf { format.instant },
                CardType.SORCERY.takeIf { format.sorcery },
            ).fold(0) { acc, t -> acc or t.bit }
        }

    private val setDisabled = BooleanArray(pool.sets.size) { pool.sets[it].code in format.disabledSets }

    /** Universes Beyond, Alchemy and Un-set printings are removed when switched off. */
    private fun passesSetGroups(set: CardSet, printing: Printing): Boolean =
        (format.universesBeyond || !printing.universesBeyond) &&
            (alchemyOn || !set.isAlchemy) &&
            (format.unSets || !set.isUnSet)

    /** Legal in a chosen format, or an Un-set printing with Un-sets on. */
    private fun passesFormats(card: PoolCard, set: CardSet): Boolean =
        card.legalFormats and formatBits != 0 || (format.unSets && set.isUnSet)

    private fun passesTypes(card: PoolCard): Boolean =
        (typeBits == -1 || card.types and typeBits != 0) && (!format.legendaryOnly || card.legendary)

    /** The printings of [card] that the format allows; empty if the card is not allowed. */
    fun allowedPrintings(card: PoolCard): List<Printing> {
        if (!passesTypes(card)) return emptyList()
        return card.printings.filter { p ->
            val set = pool.sets[p.setIndex]
            !setDisabled[p.setIndex] &&
                format.allowsRarity(p.rarity) &&
                passesSetGroups(set, p) &&
                passesFormats(card, set)
        }
    }

    fun isAllowed(card: PoolCard): Boolean = allowedPrintings(card).isNotEmpty()

    /** All allowed cards. */
    fun matchingCards(): List<PoolCard> = pool.cards.filter { isAllowed(it) }

    /**
     * Sets offered in the fine filter: sets with at least one printing that passes the format
     * filters (formats and set groups). Card types, rarities and the fine filter itself are
     * ignored, so switching a set off does not hide it. Newest first, as in the pool.
     */
    fun fineFilterSets(): List<CardSet> {
        val offered = BooleanArray(pool.sets.size)
        pool.cards.forEach { card ->
            card.printings.forEach { p ->
                if (!offered[p.setIndex]) {
                    val set = pool.sets[p.setIndex]
                    if (passesSetGroups(set, p) && passesFormats(card, set)) offered[p.setIndex] = true
                }
            }
        }
        return pool.sets.filterIndexed { i, _ -> offered[i] }
    }
}
