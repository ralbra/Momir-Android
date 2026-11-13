package io.github.ralbra.randomize_the_gathering.printer

import io.github.ralbra.randomize_the_gathering.printer.phomemo.PhomemoDriver
import io.github.ralbra.randomize_the_gathering.printer.tiny.TinyDriver

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