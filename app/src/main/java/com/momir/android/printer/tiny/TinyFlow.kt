package com.momir.android.printer.tiny

import android.os.SystemClock
import android.util.Log
import com.momir.android.ble.JobOutcome
import com.momir.android.ble.TimedPacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MomirFlow"

/**
 * X6H (TiMini "tiny" family): the printer asks the phone to pause/resume, and answers the
 * final A3 state query when the job is done. The first payload byte of that reply is non-zero
 * when the printer needs attention (cover open / no paper).
 *
 * Pacing between chunks is inherited from [TimedPacing].
 */
class TinyFlow(
    private val notify: List<UUID>,
    chunksPerBurst: Int,
    burstDelayMs: Long,
    private val subscribe: Boolean,
) : TimedPacing(
    maxChunkBytes = Int.MAX_VALUE,
    chunksPerBurst = chunksPerBurst,
    burstDelayMs = burstDelayMs,
    interChunkDelayMs = 0,
) {
    companion object {
        private const val PAUSE_TIMEOUT_MS = 5_000L
        private const val DONE_TIMEOUT_MS = 60_000L

        // 51 78 AE 01 01 00 10 70 FF  /  51 78 AE 01 01 00 00 00 FF
        private val PAUSE = byteArrayOf(
            0x51, 0x78, 0xAE.toByte(), 0x01, 0x01, 0x00, 0x10, 0x70, 0xFF.toByte(),
        )
        private val RESUME = byteArrayOf(
            0x51, 0x78, 0xAE.toByte(), 0x01, 0x01, 0x00, 0x00, 0x00, 0xFF.toByte(),
        )
    }

    override val notifyUuids: List<UUID> get() = if (subscribe) notify else emptyList()

    @Volatile
    private var paused = false

    @Volatile
    private var jobDone = false

    @Volatile
    private var status: ByteArray? = null
    private val rx = ByteArrayOutputStream()

    override fun reset() {
        paused = false
        jobDone = false
        status = null
        synchronized(rx) { rx.reset() }
    }

    override fun beginJob() {
        jobDone = false
        status = null
        synchronized(rx) { rx.reset() }
    }

    override fun onNotification(uuid: UUID, value: ByteArray) {
        Log.d(TAG, "TINY notify $uuid: ${value.joinToString("") { "%02x".format(it) }}")

        // Reply to the final A3 state query: the printer has processed the job.
        if (
            value.size >= 6 &&
            value[0] == 0x51.toByte() &&
            value[1] == 0x78.toByte() &&
            value[2] == 0xA3.toByte()
        ) {
            val len = (value[4].toInt() and 0xFF) or ((value[5].toInt() and 0xFF) shl 8)
            if (value.size >= 6 + len) {
                val payload = value.copyOfRange(6, 6 + len)
                status = payload
                Log.i(
                    TAG,
                    "TINY status payload: " + payload.joinToString(" ") { "%02x".format(it) },
                )
            }
            jobDone = true
        }

        synchronized(rx) {
            rx.write(value)
            val buf = rx.toByteArray()
            val pause = buf.lastIndexOfSub(PAUSE)
            val resume = buf.lastIndexOfSub(RESUME)
            if (pause >= 0 || resume >= 0) {
                paused = pause > resume
                Log.d(TAG, "TINY flow control: paused=$paused")
                rx.reset()
            } else if (rx.size() > 256) {
                rx.reset()
            }
        }
    }

    override suspend fun beforeChunk(index: Int) {
        if (!paused) return

        val waitStart = SystemClock.elapsedRealtime()
        Log.d(TAG, "TINY paused before chunk ${index + 1}; waiting")
        val resumed = withTimeoutOrNull(PAUSE_TIMEOUT_MS.milliseconds) {
            while (paused) delay(5.milliseconds)
            true
        } ?: false
        Log.d(
            TAG,
            "TINY wait ended after ${SystemClock.elapsedRealtime() - waitStart} ms " +
                    "(resumed=$resumed)",
        )
        if (!resumed) {
            Log.w(TAG, "No TINY resume within ${PAUSE_TIMEOUT_MS}ms; continuing")
            paused = false
        }
    }

    override suspend fun finish(): JobOutcome {
        if (!subscribe) {
            // Without notifications there is no completion reply to wait for.
            delay(2000.milliseconds)
            return JobOutcome.Done
        }

        val done = withTimeoutOrNull(DONE_TIMEOUT_MS.milliseconds) {
            while (!jobDone) delay(50.milliseconds)
            true
        } ?: false
        Log.i(TAG, if (done) "Printer reported job done" else "No completion reply; continuing")

        val s = status
        if (done && s != null && s.isNotEmpty() && s[0] != 0.toByte()) {
            return JobOutcome.Fault(
                "Printer reported status 0x%02x (check paper / cover)".format(s[0]),
            )
        }
        return JobOutcome.Done
    }

    private fun ByteArray.lastIndexOfSub(sub: ByteArray): Int {
        if (sub.isEmpty() || size < sub.size) return -1
        for (start in size - sub.size downTo 0) {
            var match = true
            for (j in sub.indices) {
                if (this[start + j] != sub[j]) {
                    match = false
                    break
                }
            }
            if (match) return start
        }
        return -1
    }
}