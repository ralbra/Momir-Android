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
import androidx.core.graphics.scale
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Card art from Scryfall. With the "cache art" setting on, art is also read from and saved to
 * the device; without any art a placeholder is drawn so a card can always be printed.
 */
class ArtRepository(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient(),
    private val settings: SettingsStore = SettingsStore(context),
) {
    private companion object {
        // Regular Scryfall art crops are 626 x 457 pixels; the placeholder uses the same format.
        const val ART_WIDTH = 626
        const val ART_HEIGHT = 457
    }

    /** The bundled placeholder, cropped to the art format. Decoded once. */
    private val dummyArt: Bitmap? by lazy {
        loadBundledDummyArt()?.let { cropToArtFormat(it) }
    }

    init {
        // Creates .../Android/data/<app>/files/art so that "adb push" has a target folder.
        try {
            externalArtDir()?.mkdirs()
        } catch (_: Exception) {
            // Not having external storage is fine: the internal folder is used instead.
        }
    }

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

        return dummyArt ?: createGeneratedDummyArt(name)
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

    /** App-specific external storage: needs no permission, and "adb push" can fill it. */
    private fun externalArtDir(): File? =
        context.getExternalFilesDir(null)?.let { File(it, "art") }

    private fun internalArtDir(): File = File(context.filesDir, "art")

    /** Folders that are searched for cached art, in this order. */
    private fun readDirs(): List<File> = listOfNotNull(externalArtDir(), internalArtDir())

    /** Where newly downloaded art is saved. */
    private fun writeDir(): File = externalArtDir() ?: internalArtDir()

    /** Must match cache_name() in tools/fetch_art.py: sha256 of the UTF-8 card name. */
    private fun cacheFileName(name: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$hash.img"
    }

    private fun readCached(name: String): Bitmap? {
        val fileName = cacheFileName(name)
        for (dir in readDirs()) {
            val file = File(dir, fileName)
            if (file.isFile) {
                BitmapFactory.decodeFile(file.path)?.let { return it }
            }
        }
        return null
    }

    private fun writeCache(name: String, bytes: ByteArray) {
        try {
            val dir = writeDir()
            dir.mkdirs()
            val file = File(dir, cacheFileName(name))
            val tmp = File(dir, "${file.name}.tmp")
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

    /** Centre-crops to the aspect ratio of downloaded art and scales to its usual size. */
    private fun cropToArtFormat(src: Bitmap): Bitmap {
        val targetRatio = ART_WIDTH.toFloat() / ART_HEIGHT.toFloat()
        val sourceRatio = src.width.toFloat() / src.height.toFloat()

        val cropWidth: Int
        val cropHeight: Int
        if (sourceRatio > targetRatio) {
            cropHeight = src.height
            cropWidth = (src.height * targetRatio).roundToInt().coerceIn(1, src.width)
        } else {
            cropWidth = src.width
            cropHeight = (src.width / targetRatio).roundToInt().coerceIn(1, src.height)
        }

        val x = (src.width - cropWidth) / 2
        val y = (src.height - cropHeight) / 2
        val cropped = Bitmap.createBitmap(src, x, y, cropWidth, cropHeight)
        return cropped.scale(ART_WIDTH, ART_HEIGHT)
    }

    /** Last resort when even the bundled placeholder is missing: drawn at the art format. */
    private fun createGeneratedDummyArt(name: String): Bitmap {
        val bmp = createBitmap(ART_WIDTH, ART_HEIGHT)
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.rgb(242, 244, 248))

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 55, 72)
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRect(
            14f,
            14f,
            (ART_WIDTH - 14).toFloat(),
            (ART_HEIGHT - 14).toFloat(),
            border,
        )

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 55, 72)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(99, 115, 129)
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }

        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(75, 85, 99)
            textSize = 170f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("NO ART AVAILABLE", ART_WIDTH / 2f, 70f, title)
        canvas.drawText("offline fallback", ART_WIDTH / 2f, 104f, subtitle)
        canvas.drawText("?", ART_WIDTH / 2f, 300f, mark)

        val maxName =
            if (name.length > 40) {
                name.take(37) + "..."
            } else {
                name
            }

        canvas.drawText(maxName, ART_WIDTH / 2f, 420f, subtitle)
        return bmp
    }
}
