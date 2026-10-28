package com.momir.android.ble

import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

sealed interface JobOutcome {
    object Done : JobOutcome
    data class Fault(val message: String) : JobOutcome
}

/**
 * Decides how bytes are paced to a printer and how the end of a job is detected.
 * One instance lives per connection; [beginJob] resets the per-print state.
 */
interface FlowControl {
    /** Characteristics this strategy needs notifications from. */
    val notifyUuids: List<UUID> get() = emptyList()

    /** Upper bound for the size of one BLE write. */
    val maxChunkBytes: Int get() = Int.MAX_VALUE

    fun beginJob() {}
    fun reset() {}
    fun onSubscriptionResult(uuid: UUID, ok: Boolean) {}
    fun onNotification(uuid: UUID, value: ByteArray) {}

    suspend fun beforeChunk(index: Int) {}
    suspend fun afterChunk(index: Int) {}

    suspend fun finish(): JobOutcome {
        delay(2000.milliseconds)
        return JobOutcome.Done
    }
}

/** Fixed delays: a pause after every [chunksPerBurst] chunks plus an optional delay per chunk. */
open class TimedPacing(
    override val maxChunkBytes: Int = Int.MAX_VALUE,
    private val chunksPerBurst: Int,
    private val burstDelayMs: Long,
    private val interChunkDelayMs: Long,
) : FlowControl {
    override suspend fun afterChunk(index: Int) {
        if ((index + 1) % chunksPerBurst == 0) delay(burstDelayMs.milliseconds)
        if (interChunkDelayMs > 0) delay(interChunkDelayMs.milliseconds)
    }
}