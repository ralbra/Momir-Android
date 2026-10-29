package com.momir.android.data

import android.content.Context
import androidx.core.content.edit

/** Typed access to the app's saved settings. */
class SettingsStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "momir_settings"
        private const val KEY_BLACKENING = "blackening"
        private const val KEY_PREVIEW_MATCHES_PRINTER = "preview_matches_printer"

        const val MIN_BLACKENING = 1
        const val MAX_BLACKENING = 5
        const val DEFAULT_BLACKENING = 3
        const val DEFAULT_PREVIEW_MATCHES_PRINTER = true
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Print darkness, 1 (light) to 5 (dark). */
    var blackening: Int
        get() = prefs.getInt(KEY_BLACKENING, DEFAULT_BLACKENING)
            .coerceIn(MIN_BLACKENING, MAX_BLACKENING)
        set(value) {
            prefs.edit {
                putInt(KEY_BLACKENING, value.coerceIn(MIN_BLACKENING, MAX_BLACKENING))
            }
        }

    /**
     * true: the preview is drawn and dithered for the connected printer, like the printout.
     * false: classic 576 px grayscale preview.
     */
    var previewMatchesPrinter: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW_MATCHES_PRINTER, DEFAULT_PREVIEW_MATCHES_PRINTER)
        set(value) {
            prefs.edit { putBoolean(KEY_PREVIEW_MATCHES_PRINTER, value) }
        }
}