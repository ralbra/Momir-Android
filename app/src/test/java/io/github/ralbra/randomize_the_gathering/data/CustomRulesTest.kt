package io.github.ralbra.randomize_the_gathering.data

import io.github.ralbra.randomize_the_gathering.model.CardPool
import io.github.ralbra.randomize_the_gathering.model.CardSet
import io.github.ralbra.randomize_the_gathering.model.CardType
import io.github.ralbra.randomize_the_gathering.model.Creature
import io.github.ralbra.randomize_the_gathering.model.CustomFormat
import io.github.ralbra.randomize_the_gathering.model.LegalFormat
import io.github.ralbra.randomize_the_gathering.model.PoolCard
import io.github.ralbra.randomize_the_gathering.model.Printing
import io.github.ralbra.randomize_the_gathering.model.Rarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CustomRulesTest {
    @Test
    fun defaultEmblemMatchesClassicMomir() {
        assertEquals(
            "{X}, Discard a card: Create a token that's a copy of a creature card with mana value X " +
                "chosen at random. Activate only as a sorcery and only once each turn.",
            EmblemText.forFormat(CustomFormat(id = "1")),
        )
    }

    @Test
    fun emblemOptions() {
        assertEquals(
            "{X}: Create a token that's a copy of a legendary creature, enchantment, or artifact card with " +
                "mana value X chosen at random. Card type chances: creature 60%, enchantment 20%, artifact 20%. " +
                "If that card is common, draw a card. Activate only as a sorcery.",
            EmblemText.forFormat(
                CustomFormat(
                    id = "1", enchantment = true, artifact = true, legendaryOnly = true,
                    discardCost = false, drawOnCommon = true, oncePerTurn = false,
                ),
            ),
        )
        assertEquals(
            "{X}, Discard a card: Copy an instant or sorcery card with mana value X chosen at random. " +
                "You may cast the copy without paying its mana cost. Card type chances: instant 50%, sorcery 50%. Activate only as a sorcery and only once each turn.",
            EmblemText.forFormat(CustomFormat(id = "1", creature = false, instant = true, sorcery = true)),
        )
        assertEquals(
            "{X}, Discard a card: Choose a creature or instant card with mana value X at random. If it's an " +
                "instant card, copy it and you may cast the copy without paying its mana cost. Otherwise, create " +
                "a token that's a copy of it. Card type chances: creature 80%, instant 20%. Activate only as a sorcery and only once each turn.",
            EmblemText.forFormat(CustomFormat(id = "1", instant = true)),
        )
        assertTrue(
            EmblemText.forFormat(CustomFormat(id = "1", anyCard = true))
                .contains("Choose a card with mana value X at random. If it's an instant or sorcery card"),
        )
    }

    private fun card(name: String, mv: Int, vararg printings: Printing) = PoolCard(
        card = Creature(name, mv, "Creature", "1", "1", "", "", false),
        types = CardType.CREATURE.bit,
        legendary = false,
        legalFormats = LegalFormat.LEGACY.bit,
        printings = printings.toList(),
    )

    @Test
    fun rollerPicksAllowedPrintingsAtTheManaValue() {
        val sets = listOf(CardSet("aaa", "A", "expansion", "2020-01-01"), CardSet("bbb", "B", "expansion", "2019-01-01"))
        val pool = CardPool(
            sets,
            listOf(
                card("One", 1, Printing(0, Rarity.COMMON, false), Printing(1, Rarity.RARE, false)),
                card("Two", 2, Printing(0, Rarity.UNCOMMON, false)),
            ),
            "x",
        )
        val roller = CustomRoller(pool, CustomFormat(id = "1", rare = false))
        assertEquals(1, roller.count(1))
        assertNull(roller.roll(5))
        repeat(20) {
            val r = roller.roll(1, Random(it))!!
            assertEquals("One", r.card.card.name)
            assertEquals(Rarity.COMMON, r.printing.rarity)
            assertTrue(r.isCommon)
            assertEquals("AAA - Common", r.footer())
        }
        assertTrue(roller.isFor(pool, CustomFormat(id = "1", rare = false)))
    }

    @Test
    fun chancesStayValid() {
        val f = CustomFormat(id = "1")
        // Turning all four on: creatures keep the rest.
        val all = CustomFormat.EXTRA_TYPES.fold(f) { acc, t -> acc.withType(t, true) }
        assertEquals(20, all.creatureChance)
        assertEquals(100, all.typeChances().sumOf { it.second })
        // A slider cannot push creatures below 1%.
        val maxed = all.withChance(CardType.INSTANT, 100)
        assertEquals(1, maxed.creatureChance)
        assertEquals(39, maxed.instantChance)
        // Enabling another type with no room left takes from the largest other type.
        val two = f.withType(CardType.INSTANT, true).withChance(CardType.INSTANT, 99).withType(CardType.SORCERY, true)
        assertTrue(two.creatureChance >= 1)
        assertEquals(20, two.sorceryChance)
        // Creatures off: weights scaled to 100.
        val off = f.withType(CardType.INSTANT, true).withChance(CardType.INSTANT, 30)
            .withType(CardType.SORCERY, true).withChance(CardType.SORCERY, 10)
            .withType(CardType.CREATURE, false)
        assertEquals(listOf(CardType.INSTANT to 75, CardType.SORCERY to 25), off.typeChances())
        // Turning creatures back on with large weights shrinks them to fit.
        val big = off.withChance(CardType.INSTANT, 100).withChance(CardType.SORCERY, 100).withType(CardType.CREATURE, true)
        assertTrue(big.creatureChance >= 1)
        // No sliders for a single type or any card.
        assertTrue(f.typeChances().isEmpty())
        assertTrue(f.withType(CardType.CREATURE, false).withType(CardType.INSTANT, true).typeChances().isEmpty())
        assertTrue(all.copy(anyCard = true).typeChances().isEmpty())
        // Old or broken saved data is repaired.
        val broken = CustomFormatCodec.decode(
            """[{"id":"x","enchantment":true,"artifact":true,"enchantmentChance":90,"artifactChance":90}]""",
        ).single()
        assertTrue(broken.creatureChance >= 1)
        assertEquals(listOf(all), CustomFormatCodec.decode(CustomFormatCodec.encode(listOf(all))))
    }

    @Test
    fun rollerFollowsTypeChances() {
        val sets = listOf(CardSet("aaa", "A", "expansion", "2020-01-01"))
        fun typed(name: String, types: Int) = PoolCard(
            card = Creature(name, 2, "", "", "", "", "", false),
            types = types,
            legendary = false,
            legalFormats = LegalFormat.LEGACY.bit,
            printings = listOf(Printing(0, Rarity.COMMON, false)),
        )
        // Nine creatures and one instant: uniform would give the instant 10%.
        val cards = (1..9).map { typed("C$it", CardType.CREATURE.bit) } + typed("I", CardType.INSTANT.bit) +
            typed("S3", CardType.SORCERY.bit).copy(card = Creature("S3", 3, "", "", "", "", "", false))
        val pool = CardPool(sets, cards, "x")
        val format = CustomFormat(id = "1").withType(CardType.INSTANT, true).withChance(CardType.INSTANT, 70)
            .withType(CardType.SORCERY, true).withChance(CardType.SORCERY, 10)
        val roller = CustomRoller(pool, format)
        val random = Random(42)
        val instants = (1..10_000).count { roller.roll(2, random)!!.card.card.name == "I" }
        // No sorcery at MV 2: its 10% is shared, instants get 70/90.
        assertTrue("instants: $instants", instants in 7300..8250)
    }

    @Test
    fun searchPoolRanksAndSkipsClassicNames() {
        val sets = listOf(CardSet("new", "New", "expansion", "2024-01-01"), CardSet("old", "Old", "expansion", "2000-01-01"))
        fun named(name: String) = PoolCard(
            card = Creature(name, 1, "Instant", "", "", "", "", false),
            types = CardType.INSTANT.bit,
            legendary = false,
            legalFormats = LegalFormat.LEGACY.bit,
            printings = listOf(Printing(1, Rarity.COMMON, false), Printing(0, Rarity.UNCOMMON, false)),
        )
        val pool = CardPool(sets, listOf(named("Lightning Bolt"), named("Bolt"), named("Boltwave"), named("Fire // Ice")), "x")
        assertEquals(listOf("Bolt", "Boltwave", "Lightning Bolt"), searchPool(pool, "bolt", emptySet()).map { it.name })
        assertEquals(listOf("Boltwave", "Lightning Bolt"), searchPool(pool, "bolt", setOf("Bolt")).map { it.name })
        assertEquals("NEW - Uncommon", searchPool(pool, "ice", emptySet()).single().footer)
        assertTrue(searchPool(pool, "b", emptySet()).isEmpty())
    }
}
