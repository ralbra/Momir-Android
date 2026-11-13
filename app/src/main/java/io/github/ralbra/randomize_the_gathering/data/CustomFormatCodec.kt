package io.github.ralbra.randomize_the_gathering.data

import io.github.ralbra.randomize_the_gathering.model.CustomFormat
import io.github.ralbra.randomize_the_gathering.model.LegalFormat
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

/**
 * Saved custom formats as JSON. Formats are stored by their Scryfall key and sets by code,
 * so they survive card data updates. Unknown or missing fields fall back to the defaults.
 */
object CustomFormatCodec {
    fun encode(formats: List<CustomFormat>): String {
        val buffer = Buffer()
        val w = JsonWriter.of(buffer)
        w.beginArray()
        formats.forEach { f ->
            w.beginObject()
            w.name("id").value(f.id)
            w.name("name").value(f.name)
            w.name("creature").value(f.creature)
            w.name("enchantment").value(f.enchantment)
            w.name("artifact").value(f.artifact)
            w.name("instant").value(f.instant)
            w.name("sorcery").value(f.sorcery)
            w.name("anyCard").value(f.anyCard)
            w.name("enchantmentChance").value(f.enchantmentChance.toLong())
            w.name("artifactChance").value(f.artifactChance.toLong())
            w.name("instantChance").value(f.instantChance.toLong())
            w.name("sorceryChance").value(f.sorceryChance.toLong())
            w.name("discardCost").value(f.discardCost)
            w.name("drawOnCommon").value(f.drawOnCommon)
            w.name("oncePerTurn").value(f.oncePerTurn)
            w.name("legendaryOnly").value(f.legendaryOnly)
            w.name("mythic").value(f.mythic)
            w.name("rare").value(f.rare)
            w.name("uncommon").value(f.uncommon)
            w.name("common").value(f.common)
            w.name("formats").beginArray()
            f.formats.sortedBy { it.ordinal }.forEach { w.value(it.key) }
            w.endArray()
            w.name("universesBeyond").value(f.universesBeyond)
            w.name("unSets").value(f.unSets)
            w.name("disabledSets").beginArray()
            f.disabledSets.sorted().forEach { w.value(it) }
            w.endArray()
            w.endObject()
        }
        w.endArray()
        w.flush()
        return buffer.readUtf8()
    }

    /** Never throws: broken data gives an empty list. */
    fun decode(json: String?): List<CustomFormat> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val r = JsonReader.of(Buffer().writeUtf8(json))
            val out = mutableListOf<CustomFormat>()
            r.beginArray()
            while (r.hasNext()) readFormat(r)?.let { out.add(it) }
            r.endArray()
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readFormat(r: JsonReader): CustomFormat? {
        var f = CustomFormat(id = "")
        r.beginObject()
        while (r.hasNext()) {
            f = when (r.nextName()) {
                "id" -> f.copy(id = r.nextString())
                "name" -> f.copy(name = r.nextString())
                "creature" -> f.copy(creature = r.nextBoolean())
                "enchantment" -> f.copy(enchantment = r.nextBoolean())
                "artifact" -> f.copy(artifact = r.nextBoolean())
                "instant" -> f.copy(instant = r.nextBoolean())
                "sorcery" -> f.copy(sorcery = r.nextBoolean())
                "anyCard" -> f.copy(anyCard = r.nextBoolean())
                "enchantmentChance" -> f.copy(enchantmentChance = r.nextInt().coerceIn(1, 100))
                "artifactChance" -> f.copy(artifactChance = r.nextInt().coerceIn(1, 100))
                "instantChance" -> f.copy(instantChance = r.nextInt().coerceIn(1, 100))
                "sorceryChance" -> f.copy(sorceryChance = r.nextInt().coerceIn(1, 100))
                "discardCost" -> f.copy(discardCost = r.nextBoolean())
                "drawOnCommon" -> f.copy(drawOnCommon = r.nextBoolean())
                "oncePerTurn" -> f.copy(oncePerTurn = r.nextBoolean())
                "legendaryOnly" -> f.copy(legendaryOnly = r.nextBoolean())
                "mythic" -> f.copy(mythic = r.nextBoolean())
                "rare" -> f.copy(rare = r.nextBoolean())
                "uncommon" -> f.copy(uncommon = r.nextBoolean())
                "common" -> f.copy(common = r.nextBoolean())
                "formats" -> f.copy(formats = readStrings(r).mapNotNull { LegalFormat.fromKey(it) }.toSet())
                "universesBeyond" -> f.copy(universesBeyond = r.nextBoolean())
                "unSets" -> f.copy(unSets = r.nextBoolean())
                "disabledSets" -> f.copy(disabledSets = readStrings(r).toSet())
                else -> {
                    r.skipValue()
                    f
                }
            }
        }
        r.endObject()
        return f.takeIf { it.id.isNotEmpty() }?.fitted()
    }

    private fun readStrings(r: JsonReader): List<String> {
        val list = mutableListOf<String>()
        r.beginArray()
        while (r.hasNext()) list.add(r.nextString())
        r.endArray()
        return list
    }
}
