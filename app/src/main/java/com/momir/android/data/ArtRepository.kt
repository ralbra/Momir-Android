package com.momir.android.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Card art from Scryfall. With the "cache art" setting on, art is also read from and saved to
 * internal storage; without any art a placeholder is drawn so a card can always be printed.
 */
class ArtRepository(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient(),
    private val settings: SettingsStore = SettingsStore(context),
) {
    /** URL of the full card image shown in the UI (Scryfall "normal" version). */
    fun imageUrl(name: String, version: String = "normal"): String =
        "https://api.scryfall.com/cards/named"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("exact", name)
            .addQueryParameter("format", "image")
            .addQueryParameter("version", version)
            .build()
            .toString()

    /** Blocking: run it on a background thread. */
    fun fetchArtCrop(name: String): Bitmap? {
        val useCache = settings.cacheArt
        if (useCache) {
            readCached(name)?.let { return it }
        }

        val bytes = downloadArtBytes(name)
        if (bytes != null) {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                if (useCache) writeCache(name, bytes)
                return bitmap
            }
        }

        return loadBundledDummyArt() ?: createGeneratedDummyArt(name)
    }

    private fun downloadArtBytes(name: String): ByteArray? = try {
        val req = Request.Builder()
            .url(imageUrl(name, "art_crop"))
            .header("User-Agent", "momir-android/1.0")
            .header("Accept", "image/*")
            .build()

        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body.bytes() else null
        }
    } catch (_: Exception) {
        null
    }

    // ---- Disk cache (only used when the setting is on) ----------------------------

    private fun artDir() = File(context.filesDir, "art")

    /** Must match cache_name() in tools/fetch_art.py: sha256 of the UTF-8 card name. */
    private fun cacheFile(name: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(artDir(), "$hash.img")
    }

    private fun readCached(name: String): Bitmap? {
        val file = cacheFile(name)
        return if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    private fun writeCache(name: String, bytes: ByteArray) {
        try {
            artDir().mkdirs()
            val file = cacheFile(name)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: Exception) {
            // A full disk must never break rolling a card.
        }
    }

    // ---- Placeholder art ----------------------------------------------------------

    @SuppressLint("DiscouragedApi")
    private fun loadBundledDummyArt(): Bitmap? {
        try {
            context.assets.open("dummy.png").use { input ->
                return BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
        }

        return try {
            val resId = context.resources.getIdentifier(
                "dummy",
                "drawable",
                context.packageName,
            )
            if (resId != 0) {
                BitmapFactory.decodeResource(context.resources, resId)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createGeneratedDummyArt(name: String): Bitmap {
        val width = 1024
        val height = 1024
        val bmp = createBitmap(width, height)
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.rgb(242, 244, 248))

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 55, 72)
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        canvas.drawRect(
            24f,
            24f,
            (width - 24).toFloat(),
            (height - 24).toFloat(),
            border,
        )

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 55, 72)
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(99, 115, 129)
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }

        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(75, 85, 99)
            textSize = 260f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("NO ART AVAILABLE", width / 2f, 150f, title)
        canvas.drawText("offline fallback", width / 2f, 210f, subtitle)
        canvas.drawText("?", width / 2f, 590f, mark)

        val maxName =
            if (name.length > 40) {
                name.take(37) + "..."
            } else {
                name
            }

        canvas.drawText(maxName, width / 2f, 910f, subtitle)
        return bmp
    }
}
