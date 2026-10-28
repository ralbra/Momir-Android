package com.momir.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

object ImageOps {
    fun resizeToWidth(bitmap: Bitmap, width: Int): Bitmap {
        if (bitmap.width == width) return bitmap

        val h = (bitmap.height.toFloat() * (width.toFloat() / bitmap.width.toFloat())).toInt()
        return bitmap.scale(width, h.coerceAtLeast(1))
    }

    fun rotateAndCenter(bitmap: Bitmap, printWidth: Int): Bitmap {
        val rotated = createBitmap(bitmap.height, bitmap.width)

        val canvas = Canvas(rotated)
        canvas.rotate(90f)
        canvas.translate(0f, -bitmap.height.toFloat())
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val out = createBitmap(printWidth, rotated.height)

        val c = Canvas(out)
        c.drawColor(Color.WHITE)

        val x = (printWidth - rotated.width) / 2f
        c.drawBitmap(rotated, x, 0f, null)

        return out
    }
}