package com.momir.android.printer.phomemo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.get
import com.momir.android.render.Monochrome
import java.io.ByteArrayOutputStream
import kotlin.math.min

object PhomemoEncoder {
    private const val MAX_LINES_PER_BLOCK = 255

    fun build(bitmap: Bitmap, init: ByteArray, finalize: ByteArray): ByteArray {
        val bw = Monochrome.toMonochrome(bitmap)
        val bytesPerLine = bw.width / 8
        val packed = packImage(bw, bytesPerLine)

        val out = ByteArrayOutputStream()
        out.write(init)

        val totalLines = bw.height
        var offset = 0
        while (offset < totalLines) {
            val lines = min(MAX_LINES_PER_BLOCK, totalLines - offset)
            out.write(byteArrayOf(0x1d, 0x76, 0x30, 0x00))
            out.write(le2(bytesPerLine))
            out.write(le2(lines))

            val start = offset * bytesPerLine
            out.write(packed, start, lines * bytesPerLine)
            offset += lines
        }

        out.write(finalize)
        return out.toByteArray()
    }

    private fun packImage(bitmap: Bitmap, bytesPerLine: Int): ByteArray {
        val out = ByteArray(bytesPerLine * bitmap.height)
        var i = 0
        for (y in 0 until bitmap.height) {
            for (xb in 0 until bytesPerLine) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xb * 8 + bit
                    val black = Color.red(bitmap[x, y]) < 128
                    if (black) b = b or (1 shl (7 - bit))
                }
                out[i++] = if (b == 0x0A) 0x14 else b.toByte()
            }
        }
        return out
    }

    private fun le2(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}