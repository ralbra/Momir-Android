package com.momir.android.printer.phomemo

import android.util.Log
import com.momir.android.ble.FlowControl
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MomirFlow"

/** M02 Pro: the printer sends credits on FF03; falls back to timed pacing if none arrive. */
class CreditFlow(private val fallbackChunkDelayMs: Long) : FlowControl {
    companion object {
        val FLOW_CONTROL_UUID: UUID = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb")
        private const val INITIAL_CREDIT_WINDOW = 4
        private const val INITIAL_WINDOW_DELAY_MS = 5L
        private const val CREDIT_DETECT_TIMEOUT_MS = 250L
        private const val CREDIT_TIMEOUT_MS = 1_000L
    }

    override val notifyUuids: List<UUID> = listOf(FLOW_CONTROL_UUID)

    private val credits = Channel<Unit>(Channel.UNLIMITED)

    @Volatile
    private var subscribed = false
    private var confirmed = false
    private var fallback = true

    private fun drain() {
        while (credits.tryReceive().isSuccess) {
            // discard stale credits
        }
    }

    override fun reset() {
        subscribed = false
        drain()
    }

    override fun beginJob() {
        drain()
        confirmed = false
        fallback = !subscribed
        Log.i(TAG, "Credit flow: subscribed=$subscribed")
    }

    override fun onSubscriptionResult(uuid: UUID, ok: Boolean) {
        if (uuid != FLOW_CONTROL_UUID) return
        subscribed = ok
        if (ok) {
            Log.i(TAG, "M02 Pro FF03 notifications enabled")
        } else {
            Log.w(TAG, "M02 Pro FF03 notification setup failed; using timed pacing")
        }
    }

    override fun onNotification(uuid: UUID, value: ByteArray) {
        if (uuid != FLOW_CONTROL_UUID) return
        if (value.size >= 2 && value[0] == 0x01.toByte() && value[1] == 0x01.toByte()) {
            credits.trySend(Unit)
            return
        }
        Log.d(
            TAG,
            "Unexpected FF03 notification: " +
                    value.joinToString(" ") { "%02x".format(it.toInt() and 0xff) },
        )
    }

    override suspend fun beforeChunk(index: Int) {
        if (fallback || index < INITIAL_CREDIT_WINDOW) return

        val timeoutMs = if (confirmed) CREDIT_TIMEOUT_MS else CREDIT_DETECT_TIMEOUT_MS
        val received = withTimeoutOrNull(timeoutMs.milliseconds) {
            credits.receive()
            true
        } ?: false

        if (received) {
            if (!confirmed) Log.i(TAG, "M02 Pro FF03 credit flow confirmed")
            confirmed = true
        } else {
            fallback = true
            Log.w(
                TAG,
                "No M02 Pro FF03 credit received within ${timeoutMs}ms; " +
                        "falling back to ${fallbackChunkDelayMs}ms pacing",
            )
        }
    }

    override suspend fun afterChunk(index: Int) {
        when {
            fallback -> delay(fallbackChunkDelayMs.milliseconds)
            index < INITIAL_CREDIT_WINDOW - 1 -> delay(INITIAL_WINDOW_DELAY_MS.milliseconds)
            else -> {
                // In confirmed credit mode the next beforeChunk waits for FF03.
            }
        }
    }
}