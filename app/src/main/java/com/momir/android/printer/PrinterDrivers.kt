package com.momir.android.printer

import com.momir.android.printer.phomemo.PhomemoDriver
import com.momir.android.printer.tiny.TinyDriver

object PrinterDrivers {
    // Most specific first: "M02 PRO" and "M02S" both contain "M02".
    private val all: List<PrinterDriver> = listOf(
        TinyDriver(),
        PhomemoDriver.m04s(),
        PhomemoDriver.m02Pro(),
        PhomemoDriver.m02s(),
        PhomemoDriver.m02(),
    )

    fun forName(deviceName: String): PrinterDriver? =
        all.firstOrNull { it.matches(deviceName) }
}