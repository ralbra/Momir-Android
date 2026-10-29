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

/** Card art from Scryfall, with an offline fallback so a card can always be drawn. */
class ArtRepository(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient(),
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

    /** Blocking network call: run it on a background thread. */
    fun fetchArtCrop(name: String): Bitmap? {
        val network = try {
            val req = Request.Builder()
                .url(imageUrl(name, "art_crop"))
                .header("User-Agent", "momir-android/1.0")
                .header("Accept", "image/*")
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body.bytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }

        return network ?: loadBundledDummyArt() ?: createGeneratedDummyArt(name)
    }

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