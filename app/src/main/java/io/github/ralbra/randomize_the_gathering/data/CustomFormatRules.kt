package io.github.ralbra.randomize_the_gathering.data

import io.github.ralbra.randomize_the_gathering.model.CardPool
import io.github.ralbra.randomize_the_gathering.model.CardSet
import io.github.ralbra.randomize_the_gathering.model.CustomFormat
import io.github.ralbra.randomize_the_gathering.model.PoolCard
import io.github.ralbra.randomize_the_gathering.model.Printing
import io.github.ralbra.randomize_the_gathering.model.Rarity
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
        val chances = f.typeChances().takeIf { it.isNotEmpty() }?.let { list ->
            " Card type chances: " + list.joinToString(", ") { (type, pct) -> "${type.word.lowercase()} $pct%" } + "."
        } ?: ""
        val draw = if (f.drawOnCommon) " If that card is common, draw a card." else ""
        val timing = if (f.oncePerTurn) {
            " Activate only as a sorcery and only once each turn."
        } else {
            " Activate only as a sorcery."
        }
        return "$cost: $effect$chances$draw$timing"
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

    /**
     * With type chances, first a card type is picked by its chance (types without a card at this
     * mana value drop out and the others share their chance), then a card of that type. A card
     * with two types, e.g. an artifact creature, can come from either.
     */
    fun roll(manaValue: Int, random: Random = Random.Default): CustomRoll? {
        val entries = byManaValue[manaValue] ?: return null
        val candidates = pickTypeBucket(entries, random) ?: entries
        val (card, printings) = candidates.randomOrNull(random) ?: return null
        val printing = printings.random(random)
        return CustomRoll(card, printing, pool.sets[printing.setIndex])
    }

    private fun pickTypeBucket(
        entries: List<Pair<PoolCard, List<Printing>>>,
        random: Random,
    ): List<Pair<PoolCard, List<Printing>>>? {
        val buckets = format.typeChances().mapNotNull { (type, chance) ->
            entries.filter { it.first.hasType(type) }
                .takeIf { it.isNotEmpty() && chance > 0 }
                ?.let { it to chance }
        }
        if (buckets.isEmpty()) return null
        var ticket = random.nextInt(buckets.sumOf { it.second })
        for ((bucket, chance) in buckets) {
            if (ticket < chance) return bucket
            ticket -= chance
        }
        return buckets.last().first
    }

    fun isFor(pool: CardPool, format: CustomFormat): Boolean = this.pool === pool && this.format == format
}

/** "NEO - Common": printed at the bottom of a ticket rolled in a custom format. */
fun CustomRoll.footer(): String =
    "${set.code.uppercase()} - ${printing.rarity.name.lowercase().replaceFirstChar { it.uppercase() }}"

/**
 * Cards of [pool] whose name matches [query] (exact, then prefix, then anywhere), skipping
 * names in [exclude]. Each card carries the set and rarity of its newest printing.
 */
fun searchPool(pool: CardPool, query: String, exclude: Set<String>, limit: Int = 8): List<io.github.ralbra.randomize_the_gathering.model.Creature> {
    val q = query.trim().lowercase()
    if (q.length < 2) return emptyList()
    return pool.cards.asSequence()
        .filter { it.card.name !in exclude }
        .mapNotNull { card ->
            val name = card.card.name.lowercase()
            val parts = name.split(" // ")
            val rank = when {
                name == q || parts.any { it == q } -> 0
                name.startsWith(q) || parts.any { it.startsWith(q) } -> 1
                name.contains(q) -> 2
                else -> null
            }
            rank?.let { it to card }
        }
        .sortedWith(compareBy({ it.first }, { it.second.card.name }))
        .take(limit)
        .map { (_, card) ->
            // Sets are sorted newest first, so the lowest index is the newest printing.
            val printing = card.printings.minByOrNull { it.setIndex }
            if (printing == null) {
                card.card
            } else {
                card.card.copy(footer = CustomRoll(card, printing, pool.sets[printing.setIndex]).footer())
            }
        }
        .toList()
}
