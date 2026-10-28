package com.momir.android.printer.tiny

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.get
import java.io.ByteArrayOutputStream

object TinyEncoder {
    private fun crc8(d: ByteArray): Int {
        var c = 0
        for (b in d) {
            c = c xor (b.toInt() and 0xFF)
            repeat(8) {
                c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF
            }
        }
        return c
    }

    private fun pkt(cmd: Int, p: ByteArray): ByteArray =
        byteArrayOf(
            0x51, 0x78, cmd.toByte(), 0,
            (p.size and 0xFF).toByte(), (p.size shr 8).toByte(),
        ) + p + byteArrayOf(crc8(p).toByte(), 0xFF.toByte())

    private fun writeRuns(color: Int, count: Int, out: ByteArrayOutputStream) {
        var n = count
        while (n > 127) {
            out.write((color shl 7) or 127)
            n -= 127
        }
        if (n > 0) out.write((color shl 7) or n)
    }

    private fun rle(line: IntArray): ByteArray {
        val o = ByteArrayOutputStream()
        var prev = line[0]
        var cnt = 1
        var hasBlack = prev != 0
        for (i in 1 until line.size) {
            val p = line[i]
            if (p != 0) hasBlack = true
            if (p == prev) {
                cnt++
            } else {
                writeRuns(prev, cnt, o)
                prev = p
                cnt = 1
            }
        }
        if (hasBlack) writeRuns(prev, cnt, o)
        if (o.size() == 0) writeRuns(prev, cnt, o)
        return o.toByteArray()
    }

    private fun pack(line: IntArray, lsbFirst: Boolean): ByteArray {
        val out = ByteArray((line.size + 7) / 8)
        for (x in line.indices) {
            if (line[x] != 0) {
                val bit = if (lsbFirst) x % 8 else 7 - x % 8
                out[x / 8] = (out[x / 8].toInt() or (1 shl bit)).toByte()
            }
        }
        return out
    }

    /** [bw] must already be monochrome (pure black/white), e.g. from ThermalRenderer.bitmapToMonochrome(). */
    fun build(
        bw: Bitmap,
        blackening: Int = 3,
        energy: Int = 9500,
        speed: Int = 10,
        feedPadding: Int = 12,
        lsbFirst: Boolean = true,
        postFeeds: Int = 2,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pkt(0xA4, byteArrayOf((0x30 + blackening.coerceIn(1, 5)).toByte())))
        if (energy > 0) {
            out.write(pkt(0xAF, byteArrayOf((energy and 0xFF).toByte(), (energy shr 8).toByte())))
        }
        out.write(pkt(0xBE, byteArrayOf(0)))
        out.write(pkt(0xBD, byteArrayOf(speed.toByte())))

        val w = bw.width
        val wBytes = (w + 7) / 8
        for (y in 0 until bw.height) {
            val line = IntArray(w) { x -> if (Color.red(bw[x, y]) < 128) 1 else 0 }
            val r = rle(line)
            if (r.size <= wBytes) {
                out.write(pkt(0xBF, r))
            } else {
                out.write(pkt(0xA2, pack(line, lsbFirst)))
            }
            if ((y + 1) % 200 == 0) out.write(pkt(0xBD, byteArrayOf(speed.toByte())))
        }

        out.write(pkt(0xBD, byteArrayOf(feedPadding.toByte())))
        repeat(postFeeds) { out.write(pkt(0xA1, byteArrayOf(0x30, 0x00))) }
        out.write(pkt(0xBD, byteArrayOf(feedPadding.toByte())))
        out.write(pkt(0xA3, byteArrayOf(0)))
        return out.toByteArray()
    }
}