package com.momir.android.data

import com.momir.android.model.CardPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.gzip
import okio.sink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads Scryfall's "Default Cards" bulk file (about 80 MB, one entry per printing) and
 * stores the custom format card pool built from it. The download is deleted afterwards.
 *
 * Scryfall's rules: one request to api.scryfall.com for the bulk file's address, with an
 * accurate User-Agent and an Accept header; the file itself comes from data.scryfall.io.
 */
class CardPoolBootstrapper(
    private val appDir: File,
    private val client: OkHttpClient = defaultClient(),
) {
    enum class Stage {
        LOOKING_UP,
        DOWNLOADING,
        PROCESSING,
        LOADING,
    }

    companion object {
        private const val BULK_INFO_URL = "https://api.scryfall.com/bulk-data/default-cards"
        private const val USER_AGENT = "momir-android/1.0"
        const val POOL_FILE = "card_pool_v1.json"
        private const val DOWNLOAD_FILE = "default_cards.download"

        /** Download (about 80 MB) plus the stored pool, with some headroom. */
        private const val REQUIRED_FREE_BYTES = 200L * 1024 * 1024

        private fun defaultClient() = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val poolFile get() = File(appDir, POOL_FILE)

    fun hasStoredPool(): Boolean = poolFile.isFile

    /** Reads the stored pool, or returns null if there is none or it cannot be read. */
    fun loadStored(): CardPool? {
        val file = poolFile
        if (!file.isFile) return null
        return try {
            file.source().buffer().use { CardPoolProcessor.read(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Downloads the bulk file, builds the pool and stores it. Blocking: call it from a
     * background thread. [onProgress] gets downloaded and total bytes (total is -1 if unknown).
     */
    fun download(
        onStage: (Stage) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): CardPool {
        appDir.mkdirs()
        if (appDir.usableSpace < REQUIRED_FREE_BYTES) {
            throw IOException("Not enough free storage: about 200 MB are needed")
        }

        onStage(Stage.LOOKING_UP)
        val (url, updatedAt, expectedSize) = lookUpBulkFile()

        onStage(Stage.DOWNLOADING)
        val download = File(appDir, DOWNLOAD_FILE)
        try {
            downloadTo(url, download, expectedSize, onProgress)

            onStage(Stage.PROCESSING)
            val pool = openMaybeGzip(download).use { CardPoolProcessor.process(it, updatedAt) }
            if (pool.cards.isEmpty()) throw IOException("The downloaded card file contains no cards")

            val tmp = File(appDir, "$POOL_FILE.tmp")
            tmp.sink().buffer().use { CardPoolProcessor.write(pool, it) }
            if (!tmp.renameTo(poolFile)) {
                tmp.copyTo(poolFile, overwrite = true)
                tmp.delete()
            }

            onStage(Stage.LOADING)
            return pool
        } finally {
            download.delete()
        }
    }

    private data class BulkFile(val url: String, val updatedAt: String, val size: Long)

    private fun lookUpBulkFile(): BulkFile {
        val req = Request.Builder()
            .url(BULK_INFO_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Scryfall bulk data lookup failed: HTTP ${resp.code}")
            resp.body.string()
        }
        val info = JSONObject(body)
        // Scryfall serves JSON Lines now; the plain JSON address is kept as a fallback.
        val url = info.optString("jsonl_download_uri").ifEmpty { info.optString("download_uri") }
        if (url.isEmpty()) throw IOException("Scryfall bulk data has no download address")
        val size = info.optLong("compressed_size", info.optLong("size", -1L))
        return BulkFile(url, info.optString("updated_at"), size)
    }

    private fun downloadTo(url: String, target: File, expectedSize: Long, onProgress: (Long, Long) -> Unit) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Card file download failed: HTTP ${resp.code}")
            val total = resp.body.contentLength().takeIf { it > 0 } ?: expectedSize
            resp.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done - lastReport >= 512 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
        }
    }

    /** The file is gzip-compressed unless the server already unpacked it on the way. */
    private fun openMaybeGzip(file: File): okio.BufferedSource {
        val gzipped = file.inputStream().use { s ->
            val b1 = s.read()
            val b2 = s.read()
            b1 == 0x1f && b2 == 0x8b
        }
        return if (gzipped) file.source().gzip().buffer() else file.source().buffer()
    }
}
