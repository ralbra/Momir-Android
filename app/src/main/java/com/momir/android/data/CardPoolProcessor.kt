package com.momir.android.data

import com.momir.android.model.CardFace
import com.momir.android.model.CardPool
import com.momir.android.model.CardSet
import com.momir.android.model.CardType
import com.momir.android.model.Creature
import com.momir.android.model.LegalFormat
import com.momir.android.model.PoolCard
import com.momir.android.model.Printing
import com.momir.android.model.Rarity
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.BufferedSink
import okio.BufferedSource
import java.io.IOException

/**
 * Turns Scryfall's "Default Cards" bulk file (one entry per printing) into the card pool for
 * custom formats, and reads and writes the compact file it is stored in.
 * Plain JVM code (no Android classes), so it can be unit tested.
 */
object CardPoolProcessor {
    /** Bump when the stored file changes in an incompatible way. */
    const val FILE_VERSION = 1

    /** Not game cards (tokens, art cards, oversized casual formats, front cards...). */
    private val SKIPPED_SET_TYPES = setOf(
        "token",
        "memorabilia",
        "minigame",
        "vanguard",
        "planechase",
        "archenemy",
    )

    /** Reversible cards repeat a card that also has a normal printing. */
    private val SKIPPED_LAYOUTS = setOf(
        "token",
        "double_faced_token",
        "emblem",
        "art_series",
        "planar",
        "scheme",
        "vanguard",
        "reversible_card",
        "front_card",
    )

    private const val UB_SECURITY_STAMP = "triangle"

    // ---- Reading the Scryfall bulk file -------------------------------------------

    private class Face(
        val name: String,
        val manaCost: String,
        val typeLine: String,
        val text: String,
        val power: String,
        val toughness: String,
    )

    private class Entry {
        var oracleId = ""
        var name = ""
        var layout = ""
        var set = ""
        var setName = ""
        var setType = ""
        var releasedAt = ""
        var rarity = ""
        var securityStamp = ""
        var legalities = 0
        var typeLine = ""
        var cmc = 0.0
        var manaCost = ""
        var text = ""
        var power = ""
        var toughness = ""
        val faces = mutableListOf<Face>()
        var faceOracleId = ""
    }

    private class Builder(val card: PoolCard) {
        val printings = LinkedHashSet<Printing>()
    }

    /**
     * Reads the bulk file. Works with JSON Lines (one card per line, what Scryfall serves now)
     * and with a single JSON array (the older format).
     */
    fun process(source: BufferedSource, sourceUpdatedAt: String, onCard: (Int) -> Unit = {}): CardPool {
        val sets = LinkedHashMap<String, CardSet>()
        val setIndex = HashMap<String, Int>()
        val builders = LinkedHashMap<String, Builder>()
        var count = 0

        val reader = JsonReader.of(source)
        reader.isLenient = true // JSON Lines: many top-level values

        val inArray = reader.peek() == JsonReader.Token.BEGIN_ARRAY
        if (inArray) reader.beginArray()

        while (if (inArray) reader.hasNext() else reader.peek() != JsonReader.Token.END_DOCUMENT) {
            val e = readEntry(reader)
            count++
            if (count % 1000 == 0) onCard(count)

            if (e.setType in SKIPPED_SET_TYPES || e.layout in SKIPPED_LAYOUTS) continue
            val id = e.oracleId.ifEmpty { e.faceOracleId }.ifEmpty { e.name }
            if (id.isEmpty() || e.set.isEmpty()) continue

            val index = setIndex.getOrPut(e.set) {
                sets[e.set] = CardSet(e.set, e.setName, e.setType, e.releasedAt)
                setIndex.size
            }
            val builder = builders.getOrPut(id) { Builder(toPoolCard(e)) }
            builder.printings.add(
                Printing(
                    setIndex = index,
                    rarity = Rarity.fromScryfall(e.rarity),
                    universesBeyond = e.securityStamp == UB_SECURITY_STAMP,
                ),
            )
        }
        if (inArray) reader.endArray()

        // Sets newest first; printings point at the new positions.
        val setList = sets.values.toList()
        val order = setList.indices.sortedWith(
            compareByDescending<Int> { setList[it].releasedAt }.thenBy { setList[it].name },
        )
        val newIndex = IntArray(setList.size)
        order.forEachIndexed { position, old -> newIndex[old] = position }

        val cards = builders.values.map { b ->
            // An Un-card is one that only has printings in Un-sets.
            val funny = b.printings.all { setList[it.setIndex].isUnSet }
            b.card.copy(
                card = b.card.card.copy(isFunny = funny),
                printings = b.printings.map { it.copy(setIndex = newIndex[it.setIndex]) },
            )
        }
        return CardPool(order.map { setList[it] }, cards, sourceUpdatedAt)
    }

    private fun toPoolCard(e: Entry): PoolCard {
        // Multi-face cards: the front face is what is rolled and filtered on.
        val front = e.faces.firstOrNull()
        val frontType = front?.typeLine ?: e.typeLine
        val keepOtherFaces = e.layout !in FaceRules.LAYOUTS_WITHOUT_USABLE_OTHER_FACE
        val extra =
            if (keepOtherFaces && e.faces.size > 1) {
                e.faces.drop(1).map {
                    CardFace(
                        name = it.name,
                        manaCost = it.manaCost,
                        type = it.typeLine,
                        power = it.power,
                        toughness = it.toughness,
                        text = it.text,
                    )
                }
            } else {
                emptyList()
            }

        val card = Creature(
            name = e.name,
            manaValue = e.cmc.toInt(),
            type = frontType,
            power = front?.power ?: e.power,
            toughness = front?.toughness ?: e.toughness,
            text = front?.text ?: e.text,
            manaCost = front?.manaCost ?: e.manaCost,
            isFunny = false, // set from all printings in process()
            displayName = front?.name ?: e.name,
            extraFaces = extra,
            backHasOwnArt = extra.isNotEmpty() && e.layout in FaceRules.SEPARATE_ART_LAYOUTS,
        )

        val (types, legendary) = parseTypes(frontType)
        return PoolCard(
            card = card,
            types = types,
            legendary = legendary,
            legalFormats = e.legalities,
            printings = emptyList(),
        )
    }

    /** Card type bits and the Legendary flag from a type line like "Legendary Creature — Elf". */
    internal fun parseTypes(typeLine: String): Pair<Int, Boolean> {
        val words = typeLine.substringBefore("—").substringBefore(" - ").split(' ')
        var bits = 0
        CardType.entries.forEach { if (it.word in words) bits = bits or it.bit }
        return bits to ("Legendary" in words)
    }

    private fun readEntry(reader: JsonReader): Entry {
        val e = Entry()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "oracle_id" -> e.oracleId = str(reader)
                "name" -> e.name = str(reader)
                "layout" -> e.layout = str(reader)
                "set" -> e.set = str(reader)
                "set_name" -> e.setName = str(reader)
                "set_type" -> e.setType = str(reader)
                "released_at" -> e.releasedAt = str(reader)
                "rarity" -> e.rarity = str(reader)
                "security_stamp" -> e.securityStamp = str(reader)
                "legalities" -> e.legalities = readLegalities(reader)
                "type_line" -> e.typeLine = str(reader)
                "cmc" -> e.cmc = if (reader.peek() == JsonReader.Token.NUMBER) reader.nextDouble() else {
                    reader.skipValue()
                    0.0
                }
                "mana_cost" -> e.manaCost = str(reader)
                "oracle_text" -> e.text = str(reader)
                "power" -> e.power = str(reader)
                "toughness" -> e.toughness = str(reader)
                "card_faces" -> readFaces(reader, e)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return e
    }

    private fun readLegalities(reader: JsonReader): Int {
        var bits = 0
        reader.beginObject()
        while (reader.hasNext()) {
            val format = LegalFormat.fromKey(reader.nextName())
            val status = str(reader)
            if (format != null && (status == "legal" || status == "restricted")) {
                bits = bits or format.bit
            }
        }
        reader.endObject()
        return bits
    }

    private fun readFaces(reader: JsonReader, e: Entry) {
        reader.beginArray()
        while (reader.hasNext()) {
            var name = ""
            var manaCost = ""
            var typeLine = ""
            var text = ""
            var power = ""
            var toughness = ""
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> name = str(reader)
                    "mana_cost" -> manaCost = str(reader)
                    "type_line" -> typeLine = str(reader)
                    "oracle_text" -> text = str(reader)
                    "power" -> power = str(reader)
                    "toughness" -> toughness = str(reader)
                    "oracle_id" -> {
                        val id = str(reader)
                        if (e.faceOracleId.isEmpty()) e.faceOracleId = id
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            e.faces.add(Face(name, manaCost, typeLine, text, power, toughness))
        }
        reader.endArray()
    }

    /** A string value; null and non-string values become "". */
    private fun str(reader: JsonReader): String = when (reader.peek()) {
        JsonReader.Token.STRING -> reader.nextString()
        JsonReader.Token.NUMBER -> reader.nextString()
        else -> {
            reader.skipValue()
            ""
        }
    }

    // ---- Stored file ---------------------------------------------------------------
    //
    // {"version":1,"source":"<updated_at>",
    //  "sets":[["code","name","type","yyyy-mm-dd"],...],
    //  "cards":[{"n":..,"fn":..,"mv":..,"t":..,"p":..,"h":..,"x":..,"m":..,"f":bool,"ba":bool,
    //            "ef":[{"n","m","t","p","h","x"}],"ty":typeBits,"lg":legendary,"fm":formatBits,
    //            "pr":[packedPrinting,...]}]}
    // packedPrinting = setIndex shl 3 or rarity.ordinal shl 1 or (1 if Universes Beyond)

    fun write(pool: CardPool, sink: BufferedSink) {
        val w = JsonWriter.of(sink)
        w.beginObject()
        w.name("version").value(FILE_VERSION.toLong())
        w.name("source").value(pool.sourceUpdatedAt)
        w.name("sets").beginArray()
        pool.sets.forEach { s ->
            w.beginArray().value(s.code).value(s.name).value(s.type).value(s.releasedAt).endArray()
        }
        w.endArray()
        w.name("cards").beginArray()
        pool.cards.forEach { pc ->
            val c = pc.card
            w.beginObject()
            w.name("n").value(c.name)
            if (c.displayName != c.name) w.name("fn").value(c.displayName)
            w.name("mv").value(c.manaValue.toLong())
            w.name("t").value(c.type)
            if (c.power.isNotEmpty()) w.name("p").value(c.power)
            if (c.toughness.isNotEmpty()) w.name("h").value(c.toughness)
            if (c.text.isNotEmpty()) w.name("x").value(c.text)
            if (c.manaCost.isNotEmpty()) w.name("m").value(c.manaCost)
            if (c.isFunny) w.name("f").value(true)
            if (c.backHasOwnArt) w.name("ba").value(true)
            if (c.extraFaces.isNotEmpty()) {
                w.name("ef").beginArray()
                c.extraFaces.forEach { f ->
                    w.beginObject()
                    w.name("n").value(f.name)
                    w.name("m").value(f.manaCost)
                    w.name("t").value(f.type)
                    w.name("p").value(f.power)
                    w.name("h").value(f.toughness)
                    w.name("x").value(f.text)
                    w.endObject()
                }
                w.endArray()
            }
            w.name("ty").value(pc.types.toLong())
            if (pc.legendary) w.name("lg").value(true)
            w.name("fm").value(pc.legalFormats.toLong())
            w.name("pr").beginArray()
            pc.printings.forEach { p ->
                val packed = (p.setIndex shl 3) or (p.rarity.ordinal shl 1) or (if (p.universesBeyond) 1 else 0)
                w.value(packed.toLong())
            }
            w.endArray()
            w.endObject()
        }
        w.endArray()
        w.endObject()
        w.flush()
    }

    fun read(source: BufferedSource): CardPool {
        val r = JsonReader.of(source)
        var version = -1
        var updatedAt = ""
        val sets = mutableListOf<CardSet>()
        val cards = mutableListOf<PoolCard>()

        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "version" -> version = r.nextInt()
                "source" -> updatedAt = str(r)
                "sets" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginArray()
                        val code = str(r)
                        val name = str(r)
                        val type = str(r)
                        val released = str(r)
                        while (r.hasNext()) r.skipValue()
                        r.endArray()
                        sets.add(CardSet(code, name, type, released))
                    }
                    r.endArray()
                }
                "cards" -> {
                    r.beginArray()
                    while (r.hasNext()) cards.add(readStoredCard(r))
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()

        if (version != FILE_VERSION) throw IOException("Card pool file version $version is not supported")
        return CardPool(sets, cards, updatedAt)
    }

    private fun readStoredCard(r: JsonReader): PoolCard {
        var name = ""
        var displayName = ""
        var mv = 0
        var type = ""
        var power = ""
        var toughness = ""
        var text = ""
        var manaCost = ""
        var funny = false
        var backArt = false
        var faces = emptyList<CardFace>()
        var types = 0
        var legendary = false
        var formats = 0
        val printings = mutableListOf<Printing>()
        val rarities = Rarity.entries

        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "n" -> name = str(r)
                "fn" -> displayName = str(r)
                "mv" -> mv = r.nextInt()
                "t" -> type = str(r)
                "p" -> power = str(r)
                "h" -> toughness = str(r)
                "x" -> text = str(r)
                "m" -> manaCost = str(r)
                "f" -> funny = r.nextBoolean()
                "ba" -> backArt = r.nextBoolean()
                "ef" -> {
                    val list = mutableListOf<CardFace>()
                    r.beginArray()
                    while (r.hasNext()) {
                        var fn = ""
                        var fm = ""
                        var ft = ""
                        var fp = ""
                        var fh = ""
                        var fx = ""
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "n" -> fn = str(r)
                                "m" -> fm = str(r)
                                "t" -> ft = str(r)
                                "p" -> fp = str(r)
                                "h" -> fh = str(r)
                                "x" -> fx = str(r)
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        list.add(CardFace(fn, fm, ft, fp, fh, fx))
                    }
                    r.endArray()
                    faces = list
                }
                "ty" -> types = r.nextInt()
                "lg" -> legendary = r.nextBoolean()
                "fm" -> formats = r.nextInt()
                "pr" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        val packed = r.nextInt()
                        printings.add(
                            Printing(
                                setIndex = packed ushr 3,
                                rarity = rarities[(packed ushr 1) and 0x3],
                                universesBeyond = packed and 1 == 1,
                            ),
                        )
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()

        return PoolCard(
            card = Creature(
                name = name,
                manaValue = mv,
                type = type,
                power = power,
                toughness = toughness,
                text = text,
                manaCost = manaCost,
                isFunny = funny,
                displayName = displayName.ifEmpty { name },
                extraFaces = faces,
                backHasOwnArt = backArt,
            ),
            types = types,
            legendary = legendary,
            legalFormats = formats,
            printings = printings,
        )
    }
}
