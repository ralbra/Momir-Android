package com.momir.android.data

import com.momir.android.model.CardPool
import com.momir.android.model.CardSet
import com.momir.android.model.CardType
import com.momir.android.model.Creature
import com.momir.android.model.CustomFormat
import com.momir.android.model.LegalFormat
import com.momir.android.model.PoolCard
import com.momir.android.model.Printing
import com.momir.android.model.Rarity
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
                "mana value X chosen at random. If that card is common, draw a card. Activate only as a sorcery.",
            EmblemText.forFormat(
                CustomFormat(
                    id = "1", enchantment = true, artifact = true, legendaryOnly = true,
                    discardCost = false, drawOnCommon = true, oncePerTurn = false,
                ),
            ),
        )
        assertEquals(
            "{X}, Discard a card: Copy an instant or sorcery card with mana value X chosen at random. " +
                "You may cast the copy without paying its mana cost. Activate only as a sorcery and only once each turn.",
            EmblemText.forFormat(CustomFormat(id = "1", creature = false, instant = true, sorcery = true)),
        )
        assertEquals(
            "{X}, Discard a card: Choose a creature or instant card with mana value X at random. If it's an " +
                "instant card, copy it and you may cast the copy without paying its mana cost. Otherwise, create " +
                "a token that's a copy of it. Activate only as a sorcery and only once each turn.",
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
}
