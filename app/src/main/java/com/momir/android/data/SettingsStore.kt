package com.momir.android.data

import android.content.Context
import androidx.core.content.edit

/** Typed access to the app's saved settings. */
class SettingsStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "momir_settings"
        private const val KEY_BLACKENING = "blackening"

        const val MIN_BLACKENING = 1
        const val MAX_BLACKENING = 5
        const val DEFAULT_BLACKENING = 3
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
}