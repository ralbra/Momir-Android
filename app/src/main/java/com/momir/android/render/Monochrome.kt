package com.momir.android.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set

object Monochrome {
    /** Grayscale + Floyd-Steinberg dithering; the result contains only black and white pixels. */
    fun toMonochrome(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val out = createBitmap(w, h)

        val gray = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = bitmap[x, y]
                gray[y * w + x] = (Color.red(c) * 0.299f) + (Color.green(c) * 0.587f) + (Color.blue(c) * 0.114f)
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val oldVal = gray[idx]
                val newVal = if (oldVal < 128f) 0f else 255f
                out[x, y] = if (newVal == 0f) Color.BLACK else Color.WHITE

                val err = oldVal - newVal
                if (x + 1 < w) gray[idx + 1] += err * (7f / 16f)
                if (y + 1 < h && x - 1 >= 0) gray[(y + 1) * w + (x - 1)] += err * (3f / 16f)
                if (y + 1 < h) gray[(y + 1) * w + x] += err * (5f / 16f)
                if (y + 1 < h && x + 1 < w) gray[(y + 1) * w + (x + 1)] += err * (1f / 16f)
            }
        }

        return out
    }
}