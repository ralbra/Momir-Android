package io.github.ralbra.randomize_the_gathering.data

import io.github.ralbra.randomize_the_gathering.model.CardPool
import io.github.ralbra.randomize_the_gathering.model.CardSet
import io.github.ralbra.randomize_the_gathering.model.CardType
import io.github.ralbra.randomize_the_gathering.model.Creature
import io.github.ralbra.randomize_the_gathering.model.CustomFormat
import io.github.ralbra.randomize_the_gathering.model.FormatChoice
import io.github.ralbra.randomize_the_gathering.model.LegalFormat
import io.github.ralbra.randomize_the_gathering.model.PoolCard
import io.github.ralbra.randomize_the_gathering.model.Printing
import io.github.ralbra.randomize_the_gathering.model.Rarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFormatTest {
    private val sets = listOf(
        CardSet("neo", "Kamigawa", "expansion", "2022-02-18"),
        CardSet("unf", "Unfinity", "funny", "2022-10-07"),
        CardSet("ymid", "Alchemy: Innistrad", "alchemy", "2021-12-09"),
        CardSet("pip", "Fallout", "commander", "2024-03-08"),
    )

    private fun card(
        name: String,
        types: Int = CardType.CREATURE.bit,
        legendary: Boolean = false,
        formats: Int = LegalFormat.MODERN.bit or LegalFormat.LEGACY.bit,
        vararg printings: Printing,
    ) = PoolCard(
        card = Creature(name, 2, "Creature", "2", "2", "", "{1}{G}", false),
        types = types,
        legendary = legendary,
        legalFormats = formats,
        printings = printings.toList(),
    )

    private val elf = card("Elf", printings = arrayOf(Printing(0, Rarity.COMMON, false)))
    private val legend = card(
        "Legend",
        legendary = true,
        printings = arrayOf(Printing(0, Rarity.MYTHIC, false), Printing(3, Rarity.RARE, true)),
    )
    private val bolt = card("Bolt", types = CardType.INSTANT.bit, printings = arrayOf(Printing(0, Rarity.UNCOMMON, false)))
    private val clown = card("Clown", formats = 0, printings = arrayOf(Printing(1, Rarity.COMMON, false)))
    private val alchemyCard = card(
        "A-Elf",
        formats = LegalFormat.HISTORIC.bit or LegalFormat.ALCHEMY.bit,
        printings = arrayOf(Printing(2, Rarity.RARE, false)),
    )
    private val pool = CardPool(sets, listOf(elf, legend, bolt, clown, alchemyCard), "x")

    private fun names(f: CustomFormat) = CustomFormatFilter(pool, f).matchingCards().map { it.card.name }.toSet()

    @Test
    fun defaultsAreCreaturesInLegalNonDigitalNonUnSets() {
        assertEquals(setOf("Elf", "Legend"), names(CustomFormat(id = "1")))
    }

    @Test
    fun typesCombineAndAnyCardTakesEverything() {
        assertEquals(setOf("Elf", "Legend", "Bolt"), names(CustomFormat(id = "1", instant = true)))
        assertEquals(setOf("Bolt"), names(CustomFormat(id = "1", creature = false, instant = true)))
        assertEquals(setOf("Elf", "Legend", "Bolt"), names(CustomFormat(id = "1", creature = false, anyCard = true)))
        assertTrue(names(CustomFormat(id = "1", creature = false)).isEmpty())
    }

    @Test
    fun legendaryAndRarity() {
        assertEquals(setOf("Legend"), names(CustomFormat(id = "1", legendaryOnly = true)))
        assertEquals(setOf("Legend"), names(CustomFormat(id = "1", common = false)))
        // Legend still has its rare Fallout printing without mythics.
        assertEquals(setOf("Elf", "Legend"), names(CustomFormat(id = "1", mythic = false)))
        // ...but not if Universes Beyond is off too.
        assertEquals(setOf("Elf"), names(CustomFormat(id = "1", mythic = false, universesBeyond = false)))
    }

    @Test
    fun unSetsAndAlchemy() {
        assertTrue("Clown" in names(CustomFormat(id = "1", unSets = true)))
        val withAlchemy = CustomFormat.DEFAULT_FORMATS + LegalFormat.ALCHEMY
        assertTrue("A-Elf" in names(CustomFormat(id = "1", formats = withAlchemy)))
        // Legal in Historic, but Alchemy printings are removed while Alchemy is off.
        assertFalse("A-Elf" in names(CustomFormat(id = "1")))
    }

    @Test
    fun formatsAreOrCombined() {
        assertTrue(names(CustomFormat(id = "1", formats = setOf(LegalFormat.STANDARD))).isEmpty())
        assertEquals(setOf("Elf", "Legend"), names(CustomFormat(id = "1", formats = setOf(LegalFormat.MODERN))))
    }

    @Test
    fun fineFilterListsFormatSetsAndKeepsDisabledOnes() {
        val filter = CustomFormatFilter(pool, CustomFormat(id = "1", disabledSets = setOf("neo")))
        assertEquals(listOf("neo", "pip"), filter.fineFilterSets().map { it.code })
        // Elf only exists in the switched off set.
        assertFalse(filter.isAllowed(elf))
        assertEquals(listOf(Printing(3, Rarity.RARE, true)), filter.allowedPrintings(legend))
    }

    @Test
    fun codecRoundTripAndDefaults() {
        val formats = listOf(
            CustomFormat(id = "a", name = "Mine", instant = true, formats = setOf(LegalFormat.PAUPER), disabledSets = setOf("neo")),
            CustomFormat(id = "b", name = "Defaults"),
        )
        assertEquals(formats, CustomFormatCodec.decode(CustomFormatCodec.encode(formats)))
        // Missing fields use the defaults; broken data gives an empty list.
        assertEquals(listOf(CustomFormat(id = "c", name = "Old")), CustomFormatCodec.decode("""[{"id":"c","name":"Old"}]"""))
        assertTrue(CustomFormatCodec.decode("not json").isEmpty())
    }

    @Test
    fun formatChoiceKeys() {
        assertEquals(FormatChoice.Classic, FormatChoice.fromKey("CLASSIC"))
        assertEquals(FormatChoice.Classic, FormatChoice.fromKey(null))
        assertEquals(FormatChoice.NewCustom, FormatChoice.fromKey("NEW_CUSTOM"))
        assertEquals(FormatChoice.Custom("x1"), FormatChoice.fromKey(FormatChoice.Custom("x1").key))
    }
}
