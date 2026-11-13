package io.github.ralbra.randomize_the_gathering.data

import io.github.ralbra.randomize_the_gathering.model.CardType
import io.github.ralbra.randomize_the_gathering.model.LegalFormat
import io.github.ralbra.randomize_the_gathering.model.Rarity
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPoolProcessorTest {
    private fun line(
        oracleId: String,
        name: String,
        set: String,
        rarity: String,
        typeLine: String = "Creature — Elf Druid",
        setType: String = "expansion",
        layout: String = "normal",
        released: String = "2020-01-01",
        extra: String = "",
    ) = """{"oracle_id":"$oracleId","name":"$name","layout":"$layout","set":"$set","set_name":"Set $set",""" +
        """"set_type":"$setType","released_at":"$released","rarity":"$rarity","cmc":1.0,"mana_cost":"{G}",""" +
        """"type_line":"$typeLine","oracle_text":"{T}: Add {G}.","power":"1","toughness":"1",""" +
        """"legalities":{"modern":"legal","vintage":"restricted","standard":"not_legal","unknownformat":"legal"}$extra}"""

    private fun process(vararg lines: String) =
        CardPoolProcessor.process(Buffer().writeUtf8(lines.joinToString("\n")), "2026-11-12T22:06:24.370+00:00")

    @Test
    fun mergesPrintingsOfOneCard() {
        val pool = process(
            line("a", "Llanowar Elves", "m12", "common", released = "2011-07-15"),
            line("a", "Llanowar Elves", "dom", "common", released = "2018-04-27"),
            line("a", "Llanowar Elves", "sld", "rare", released = "2019-12-02", extra = ""","security_stamp":"triangle""""),
        )
        assertEquals(1, pool.cards.size)
        val card = pool.cards.single()
        assertEquals(3, card.printings.size)
        // Sets are sorted newest first.
        assertEquals(listOf("sld", "dom", "m12"), pool.sets.map { it.code })
        assertTrue(card.printings.any { pool.sets[it.setIndex].code == "sld" && it.rarity == Rarity.RARE && it.universesBeyond })
        assertTrue(card.isLegalIn(LegalFormat.MODERN))
        assertTrue(card.isLegalIn(LegalFormat.VINTAGE))
        assertFalse(card.isLegalIn(LegalFormat.STANDARD))
        assertTrue(card.hasType(CardType.CREATURE))
    }

    @Test
    fun skipsTokensAndArtCards() {
        val pool = process(
            line("t", "Spirit", "tm12", "common", setType = "token", layout = "token"),
            line("s", "Art Card", "am12", "common", layout = "art_series"),
            line("b", "Lightning Bolt", "lea", "common", typeLine = "Instant"),
        )
        assertEquals(listOf("Lightning Bolt"), pool.cards.map { it.card.name })
        assertTrue(pool.cards.single().hasType(CardType.INSTANT))
    }

    @Test
    fun typesAndLegendary() {
        val (bits, legendary) = CardPoolProcessor.parseTypes("Legendary Artifact Creature — Golem")
        assertTrue(legendary)
        assertEquals(CardType.ARTIFACT.bit or CardType.CREATURE.bit, bits)
    }

    @Test
    fun transformKeepsBackFaceAdventureDropsIt() {
        val transformFaces = ""","card_faces":[""" +
            """{"name":"Delver of Secrets","mana_cost":"{U}","type_line":"Creature — Human Wizard","oracle_text":"Front","power":"1","toughness":"1"},""" +
            """{"name":"Insectile Aberration","mana_cost":"","type_line":"Creature — Human Insect","oracle_text":"Flying","power":"3","toughness":"2"}]"""
        val adventureFaces = ""","card_faces":[""" +
            """{"name":"Bonecrusher Giant","mana_cost":"{2}{R}","type_line":"Creature — Giant","oracle_text":"Front","power":"4","toughness":"3"},""" +
            """{"name":"Stomp","mana_cost":"{1}{R}","type_line":"Instant — Adventure","oracle_text":"Damage"}]"""
        val pool = process(
            line("d", "Delver of Secrets // Insectile Aberration", "isd", "common", layout = "transform", extra = transformFaces),
            line("g", "Bonecrusher Giant // Stomp", "eld", "rare", layout = "adventure", extra = adventureFaces),
        )
        val delver = pool.cards.first { it.card.displayName == "Delver of Secrets" }.card
        assertEquals(listOf("Insectile Aberration"), delver.extraFaces.map { it.name })
        assertTrue(delver.backHasOwnArt)
        assertEquals("{U}", delver.manaCost)

        val giant = pool.cards.first { it.card.displayName == "Bonecrusher Giant" }
        assertTrue(giant.card.extraFaces.isEmpty())
        assertTrue(giant.hasType(CardType.CREATURE))
        assertFalse(giant.hasType(CardType.INSTANT))
    }

    @Test
    fun unCardOnlyWhenAllPrintingsAreInUnSets() {
        val pool = process(
            line("u", "Funny Elf", "unf", "common", setType = "funny"),
            line("v", "Normal Elf", "unf", "common", setType = "funny"),
            line("v", "Normal Elf", "m12", "common"),
        )
        assertTrue(pool.cards.first { it.card.name == "Funny Elf" }.card.isFunny)
        assertFalse(pool.cards.first { it.card.name == "Normal Elf" }.card.isFunny)
    }

    @Test
    fun readsJsonArrayToo() {
        val array = "[" + line("a", "Llanowar Elves", "m12", "common") + "]"
        val pool = CardPoolProcessor.process(Buffer().writeUtf8(array), "x")
        assertEquals(1, pool.cards.size)
    }

    @Test
    fun storedFileRoundTrip() {
        val pool = process(
            line("a", "Llanowar Elves", "m12", "common"),
            line("a", "Llanowar Elves", "sld", "mythic", released = "2021-01-01", extra = ""","security_stamp":"triangle""""),
            line("b", "Lightning Bolt", "lea", "uncommon", typeLine = "Instant"),
        )
        val buffer = Buffer()
        CardPoolProcessor.write(pool, buffer)
        val back = CardPoolProcessor.read(buffer)
        assertEquals(pool.sets, back.sets)
        assertEquals(pool.cards, back.cards)
        assertEquals(pool.sourceUpdatedAt, back.sourceUpdatedAt)
    }
}
