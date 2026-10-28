package com.momir.android.ble

import java.util.UUID

data class GattSpec(
    val service: UUID,
    val write: UUID,
    val notify: List<UUID> = emptyList(),
    val mtuRequest: Int = 247,
    val chunkSize: Int,
    /** Run MTU -> service discovery -> CCCD writes strictly one after another. */
    val sequentialSetup: Boolean = false,
)

/** What the BLE transport needs to know about a printer. */
interface BleTarget {
    val name: String
    val gatt: GattSpec
    fun newFlowControl(): FlowControl
}