package com.momir.android.printer.phomemo

import android.graphics.Bitmap
import com.momir.android.ble.FlowControl
import com.momir.android.ble.GattSpec
import com.momir.android.ble.TimedPacing
import com.momir.android.printer.PrintOptions
import com.momir.android.printer.PrinterDriver
import com.momir.android.render.ImageOps
import java.util.UUID

/** M02 / M02S / M02 Pro / M04S: one class, configured per model through the factories below. */
class PhomemoDriver private constructor(
    private val spec: Spec,
    private val nameMatcher: (String) -> Boolean,
) : PrinterDriver {

    private class Spec(
        val name: String,
        val printWidth: Int,
        val renderWidth: Int,
        val init: ByteArray,
        val finalize: ByteArray,
        val chunkSize: Int,
        val chunksPerBurst: Int,
        val burstDelayMs: Long,
        val rotateForPrint: Boolean = false,
        val creditFlow: Boolean = false,
        val conservativePacing: Boolean = false,
    )

    override val name: String get() = spec.name
    override val renderWidth: Int get() = spec.renderWidth
    override val printWidth: Int get() = spec.printWidth

    override val gatt: GattSpec = GattSpec(
        service = SERVICE,
        write = WRITE,
        mtuRequest = 247,
        chunkSize = spec.chunkSize,
    )

    override fun matches(deviceName: String): Boolean = nameMatcher(deviceName.uppercase())

    // Rotation is a model property (M04-style), not a consequence of being wide.
    override fun prepare(rendered: Bitmap): Bitmap =
        if (spec.rotateForPrint) {
            ImageOps.rotateAndCenter(rendered, spec.printWidth)
        } else {
            ImageOps.resizeToWidth(rendered, spec.printWidth)
        }

    // These printers have no darkness command in this app, so options.darkness is ignored.
    override fun encode(image: Bitmap, options: PrintOptions): ByteArray =
        PhomemoEncoder.build(image, spec.init, spec.finalize)

    override fun newFlowControl(): FlowControl = when {
        spec.creditFlow ->
            CreditFlow(fallbackChunkDelayMs = spec.burstDelayMs)

        spec.conservativePacing ->
            TimedPacing(
                maxChunkBytes = 205,
                chunksPerBurst = 1,
                burstDelayMs = maxOf(spec.burstDelayMs, 70L),
                interChunkDelayMs = 12L,
            )

        else ->
            TimedPacing(
                chunksPerBurst = spec.chunksPerBurst,
                burstDelayMs = spec.burstDelayMs,
                interChunkDelayMs = 8L,
            )
    }

    companion object {
        private val SERVICE: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        private val WRITE: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

        private val M02_INIT = byteArrayOf(
            0x1b, 0x40,
            0x1f, 0x11, 0x02, 0x04,
            0x1b, 0x61, 0x01,
            0x1f, 0x11, 0x24, 0x00,
        )
        private val M02_FINALIZE = byteArrayOf(
            0x1b, 0x64, 0x02,
            0x1b, 0x64, 0x02,
            0x1f, 0x11, 0x08,
            0x1f, 0x11, 0x0e,
            0x1f, 0x11, 0x07,
            0x1f, 0x11, 0x09,
        )

        fun m02() = PhomemoDriver(
            Spec(
                name = "M02",
                printWidth = 384,
                renderWidth = 576,
                init = M02_INIT,
                finalize = M02_FINALIZE,
                chunkSize = 512,
                chunksPerBurst = 1,
                burstDelayMs = 50,
                conservativePacing = true,
            ),
        ) { n -> n.contains("M02") || n.contains("T02") }

        fun m02s() = PhomemoDriver(
            Spec(
                name = "M02S",
                printWidth = 576,
                renderWidth = 576,
                init = M02_INIT,
                finalize = M02_FINALIZE,
                chunkSize = 512,
                chunksPerBurst = 2,
                burstDelayMs = 50,
                conservativePacing = true,
            ),
        ) { n -> n.contains("M02S") || n.contains("MR.IN_") }

        // M02 Pro: physically measured community implementations indicate
        // 624 printable dots (78 bytes/line) at 300 DPI. FF03 is used as
        // optional vendor flow control on firmware that exposes it.
        // burstDelayMs is the per-chunk delay used when no FF03 credits arrive.
        fun m02Pro() = PhomemoDriver(
            Spec(
                name = "M02 PRO",
                printWidth = 624,
                renderWidth = 624,
                init = byteArrayOf(
                    0x1b, 0x40,
                    0x1f, 0x11, 0x02, 0x04,
                ),
                finalize = M02_FINALIZE,
                chunkSize = 128,
                chunksPerBurst = 1,
                burstDelayMs = 20,
                creditFlow = true,
            ),
        ) { n -> n.contains("M02 PRO") }

        fun m04s() = PhomemoDriver(
            Spec(
                name = "M04S",
                printWidth = 1232,
                renderWidth = 576,
                init = byteArrayOf(
                    0x1f, 0x11, 0x02, 0x04,
                    0x1f, 0x11, 0x37, 0x96.toByte(),
                    0x1f, 0x11, 0x0b,
                    0x1f, 0x11, 0x35, 0x00,
                ),
                finalize = byteArrayOf(0x1b, 0x64, 0x02),
                chunkSize = 205,
                chunksPerBurst = 3,
                burstDelayMs = 50,
                rotateForPrint = true,
            ),
        ) { n -> n.startsWith("M04") || n.contains("M04S") || n.contains("M04AS") }
    }
}