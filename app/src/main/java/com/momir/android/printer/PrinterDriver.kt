package com.momir.android.printer

import android.graphics.Bitmap
import com.momir.android.ble.BleTarget

data class PrintOptions(val darkness: Int = 3)

/** Everything printer-specific: Bluetooth data (via [BleTarget]), geometry and encoding. */
interface PrinterDriver : BleTarget {
    /** Width the card is drawn at. */
    val renderWidth: Int

    /** Width in dots that is sent to the printer. */
    val printWidth: Int

    /** True if this driver handles a printer that advertises [deviceName]. */
    fun matches(deviceName: String): Boolean

    /** Brings the rendered card to the printer's geometry (resize, rotate, center). */
    fun prepare(rendered: Bitmap): Bitmap

    /** Turns the prepared image into the byte stream for this printer. */
    fun encode(image: Bitmap, options: PrintOptions): ByteArray
}