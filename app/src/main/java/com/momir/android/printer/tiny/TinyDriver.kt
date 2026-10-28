package com.momir.android.printer.tiny

import android.graphics.Bitmap
import com.momir.android.ble.FlowControl
import com.momir.android.ble.GattSpec
import com.momir.android.printer.PrintOptions
import com.momir.android.printer.PrinterDriver
import com.momir.android.render.ImageOps
import com.momir.android.render.Monochrome
import java.util.UUID

/** Values taken from TiMini-Print's "x6h" profile. */
data class TinyConfig(
    val energy: Int = 9500,
    val speed: Int = 10,
    val feedPadding: Int = 12,
    val lsbFirst: Boolean = true,
    val postFeeds: Int = 2,
    // Set to false if CCCD writes ever hang again; printing works without them.
    val subscribeNotifications: Boolean = true,
)

/** X6H (TiMini "tiny" family). */
class TinyDriver(private val config: TinyConfig = TinyConfig()) : PrinterDriver {
    companion object {
        private const val PRINT_WIDTH = 384
        private const val CHUNKS_PER_BURST = 1
        private const val BURST_DELAY_MS = 4L

        private fun uuid(short: String): UUID =
            UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    }

    override val name: String = "X6H"
    override val renderWidth: Int = PRINT_WIDTH
    override val printWidth: Int = PRINT_WIDTH

    // AE30 service, AE01 write (no response), AE02 notify (flow control and status replies).
    // AE04 (notify) and AE05 (indicate) exist but never sent anything in our tests.
    // MTU, discovery and CCCD writes run strictly in sequence on this printer.
    override val gatt: GattSpec = GattSpec(
        service = uuid("ae30"),
        write = uuid("ae01"),
        notify = listOf(uuid("ae02")),
        mtuRequest = 512,
        chunkSize = 180,
        sequentialSetup = true,
    )

    override fun matches(deviceName: String): Boolean =
        deviceName.uppercase().startsWith("X6H")

    override fun prepare(rendered: Bitmap): Bitmap =
        ImageOps.resizeToWidth(rendered, printWidth)

    override fun encode(image: Bitmap, options: PrintOptions): ByteArray =
        TinyEncoder.build(
            Monochrome.toMonochrome(image),
            blackening = options.darkness,
            energy = config.energy,
            speed = config.speed,
            feedPadding = config.feedPadding,
            lsbFirst = config.lsbFirst,
            postFeeds = config.postFeeds,
        )

    override fun newFlowControl(): FlowControl =
        TinyFlow(
            notify = gatt.notify,
            chunksPerBurst = CHUNKS_PER_BURST,
            burstDelayMs = BURST_DELAY_MS,
            subscribe = config.subscribeNotifications,
        )
}