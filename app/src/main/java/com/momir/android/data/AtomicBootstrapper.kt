package com.momir.android.data

import com.momir.android.model.CardFace
import com.momir.android.model.Creature
import com.squareup.moshi.JsonReader
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class AtomicBootstrapper(
    private val appDir: File,
    private val seed: () -> InputStream? = { null },
) {
    enum class Stage {
        DOWNLOADING,
        PROCESSING,
        LOADING,
    }

    companion object {
        private const val ATOMIC_URL = "https://mtgjson.com/api/v5/AtomicCards.json"
        private const val RAW_FILE = "AtomicCards.json"

        // Bump the number when what is stored changes: the file is then rebuilt from the raw
        // download, which stays on the device.
        private const val PROCESSED_FILE = "creatures_v3.json"
        private val OLD_PROCESSED_FILES = listOf("creatures_v2.json", "creatures_min.json")
        private const val RETRY_COUNT = 3
    }

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(140, TimeUnit.SECONDS)
        .build()

    fun loadOrBootstrap(onStage: (Stage) -> Unit = {}): Map<Int, List<Creature>> {
        val processed = File(appDir, PROCESSED_FILE)
        if (!processed.exists() && !seedProcessed(processed)) {
            val raw = File(appDir, RAW_FILE)
            if (!raw.exists()) {
                onStage(Stage.DOWNLOADING)
                downloadAtomic(raw)
            }
            onStage(Stage.PROCESSING)
            processAtomic(raw, processed)
        }
        onStage(Stage.LOADING)
        val result = loadProcessed(processed)
        deleteOldProcessedFiles()
        return result
    }

    fun forceRefresh(onStage: (Stage) -> Unit = {}): Map<Int, List<Creature>> {
        val raw = File(appDir, RAW_FILE)
        val processed = File(appDir, PROCESSED_FILE)

        onStage(Stage.DOWNLOADING)
        downloadAtomic(raw)
        onStage(Stage.PROCESSING)
        processAtomic(raw, processed)
        onStage(Stage.LOADING)
        val result = loadProcessed(processed)
        deleteOldProcessedFiles()
        return result
    }

    private fun deleteOldProcessedFiles() {
        OLD_PROCESSED_FILES.forEach { File(appDir, it).delete() }
    }

    /** Copies the database bundled in assets, if there is one. */
    private fun seedProcessed(target: File): Boolean {
        val input = seed() ?: return false
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadAtomic(target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        var lastError: Throwable? = null

        repeat(RETRY_COUNT) { attempt ->
            val req = Request.Builder().url(ATOMIC_URL).build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Failed AtomicCards download: HTTP ${resp.code}")
                    }
                    resp.body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                if (!tmp.exists() || tmp.length() < 1024L * 1024L) {
                    throw IOException("Downloaded AtomicCards file is unexpectedly small")
                }

                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                }
                tmp.delete()
                return
            } catch (e: Exception) {
                lastError = e
                tmp.delete()

                if (attempt < RETRY_COUNT - 1) {
                    val backoffMs = 1200L * (attempt + 1)
                    Thread.sleep(backoffMs)
                }
            }
        }

        throw IOException("AtomicCards download failed after $RETRY_COUNT attempts: ${lastError?.message}", lastError)
    }

    private fun processAtomic(rawFile: File, outFile: File) {
        val byMv = mutableMapOf<Int, MutableList<Creature>>()

        rawFile.inputStream().source().buffer().use { source ->
            val reader = JsonReader.of(source)
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "data" -> parseDataObject(reader, byMv)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        val root = JSONObject()
        byMv.toSortedMap().forEach { (mv, list) ->
            val arr = JSONArray()
            list.forEach { c ->
                val o = JSONObject()
                    .put("n", c.name)
                    .put("fn", c.displayName)
                    .put("mv", c.manaValue)
                    .put("t", c.type)
                    .put("p", c.power)
                    .put("h", c.toughness)
                    .put("x", c.text)
                    .put("m", c.manaCost)
                    .put("f", c.isFunny)
                    .put("ba", c.backHasOwnArt)

                if (c.extraFaces.isNotEmpty()) {
                    val faces = JSONArray()
                    c.extraFaces.forEach { face ->
                        faces.put(
                            JSONObject()
                                .put("n", face.name)
                                .put("m", face.manaCost)
                                .put("t", face.type)
                                .put("p", face.power)
                                .put("h", face.toughness)
                                .put("x", face.text)
                        )
                    }
                    o.put("ef", faces)
                }

                arr.put(o)
            }
            root.put(mv.toString(), arr)
        }

        val tmpOut = File(outFile.parentFile, "${outFile.name}.tmp")
        tmpOut.writeText(root.toString())
        if (!tmpOut.renameTo(outFile)) {
            tmpOut.copyTo(outFile, overwrite = true)
            tmpOut.delete()
        }
    }

    /** One entry of a card's array in AtomicCards.json: a face, or a variant of the card. */
    private class ParsedFace(
        val name: String,
        val faceName: String?,
        val side: String?,
        val layout: String,
        val manaValue: Int,
        val type: String,
        val power: String,
        val toughness: String,
        val text: String,
        val manaCost: String,
        val isFunny: Boolean,
        val isCreature: Boolean,
    )

    private fun parseDataObject(reader: JsonReader, byMv: MutableMap<Int, MutableList<Creature>>) {
        reader.beginObject()
        while (reader.hasNext()) {
            val cardName = reader.nextName()
            reader.beginArray()
            val entries = mutableListOf<ParsedFace>()
            while (reader.hasNext()) {
                entries.add(parseFace(reader, cardName))
            }
            reader.endArray()

            val creature = buildCreature(entries)
            if (creature != null) {
                byMv.getOrPut(creature.manaValue) { mutableListOf() }.add(creature)
            }
        }
        reader.endObject()
    }

    /**
     * Entries with a faceName are the faces of one multi-face card. Without faces the array
     * holds variants of the same card, and only the first one is used.
     * The first face must be a creature, as before. The other faces are only kept for layouts
     * where a Momir token can use them, see [FaceRules.LAYOUTS_WITHOUT_USABLE_OTHER_FACE].
     */
    private fun buildCreature(entries: List<ParsedFace>): Creature? {
        if (entries.isEmpty()) return null

        val named = entries.filter { it.faceName != null }
        val faces =
            if (named.size >= 2) named.sortedBy { it.side ?: "a" } else listOf(entries.first())

        val first = faces.first()
        if (!first.isCreature) return null

        val keepOtherFaces = first.layout !in FaceRules.LAYOUTS_WITHOUT_USABLE_OTHER_FACE
        val extra =
            if (keepOtherFaces) {
                faces.drop(1).map { face ->
                    CardFace(
                        name = face.faceName ?: face.name,
                        manaCost = face.manaCost,
                        type = face.type,
                        power = face.power,
                        toughness = face.toughness,
                        text = face.text,
                    )
                }
            } else {
                emptyList()
            }

        return Creature(
            name = first.name,
            manaValue = first.manaValue,
            type = first.type,
            power = first.power,
            toughness = first.toughness,
            text = first.text,
            manaCost = first.manaCost,
            isFunny = first.isFunny,
            displayName = first.faceName ?: first.name,
            extraFaces = extra,
            backHasOwnArt = extra.isNotEmpty() && first.layout in FaceRules.SEPARATE_ART_LAYOUTS,
        )
    }

    private fun parseFace(reader: JsonReader, fallbackName: String): ParsedFace {
        var name = fallbackName
        var faceName: String? = null
        var side: String? = null
        var layout = ""
        var manaValue = 0
        var type = ""
        var power = ""
        var toughness = ""
        var text = ""
        var manaCost = ""
        var isFunny = false
        var isCreature = false

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString()
                "faceName" -> faceName = nextStringOrEmpty(reader).ifEmpty { null }
                "side" -> side = nextStringOrEmpty(reader).ifEmpty { null }
                "layout" -> layout = nextStringOrEmpty(reader)
                "manaValue" -> manaValue = reader.nextDouble().toInt()
                "type" -> type = reader.nextString()
                "power" -> power = nextStringOrEmpty(reader)
                "toughness" -> toughness = nextStringOrEmpty(reader)
                "text" -> text = nextStringOrEmpty(reader)
                "manaCost" -> manaCost = nextStringOrEmpty(reader)
                "isFunny" -> isFunny = reader.nextBoolean()
                "types" -> {
                    isCreature = parseTypesArray(reader)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return ParsedFace(
            name = name,
            faceName = faceName,
            side = side,
            layout = layout,
            manaValue = manaValue,
            type = type,
            power = power,
            toughness = toughness,
            text = text,
            manaCost = manaCost,
            isFunny = isFunny,
            isCreature = isCreature,
        )
    }

    private fun parseTypesArray(reader: JsonReader): Boolean {
        var creature = false
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.nextString() == "Creature") creature = true
        }
        reader.endArray()
        return creature
    }

    private fun nextStringOrEmpty(reader: JsonReader): String {
        return if (reader.peek() == JsonReader.Token.NULL) {
            reader.nextNull<String>()
            ""
        } else {
            reader.nextString()
        }
    }

    private fun loadProcessed(file: File): Map<Int, List<Creature>> {
        val root = JSONObject(file.readText())
        val out = mutableMapOf<Int, List<Creature>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val mv = keys.next().toInt()
            val arr = root.getJSONArray(mv.toString())
            val cards = mutableListOf<Creature>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)

                // Files written before faces were stored have no "ef", "fn" or "ba".
                val faceArray = o.optJSONArray("ef")
                val extraFaces =
                    if (faceArray == null) {
                        emptyList()
                    } else {
                        (0 until faceArray.length()).map { j ->
                            val f = faceArray.getJSONObject(j)
                            CardFace(
                                name = f.optString("n"),
                                manaCost = f.optString("m"),
                                type = f.optString("t"),
                                power = f.optString("p"),
                                toughness = f.optString("h"),
                                text = f.optString("x"),
                            )
                        }
                    }

                cards.add(
                    Creature(
                        name = o.optString("n"),
                        manaValue = o.optInt("mv"),
                        type = o.optString("t"),
                        power = o.optString("p"),
                        toughness = o.optString("h"),
                        text = o.optString("x"),
                        manaCost = o.optString("m"),
                        isFunny = o.optBoolean("f"),
                        displayName = o.optString("fn").ifEmpty { o.optString("n") },
                        extraFaces = extraFaces,
                        backHasOwnArt = o.optBoolean("ba"),
                    )
                )
            }
            out[mv] = cards
        }
        return out
    }
}
