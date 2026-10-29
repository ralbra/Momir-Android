package com.momir.android.data

import android.content.Context
import androidx.core.content.edit

/** Typed access to the app's saved settings. */
class SettingsStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "momir_settings"
        private const val KEY_BLACKENING = "blackening"
        private const val KEY_PREVIEW_MATCHES_PRINTER = "preview_matches_printer"
        private const val KEY_STARTING_LIFE = "starting_life"
        private const val KEY_ROTATE_PLAYER_ONE = "rotate_player_one"
        private const val KEY_BAR_HEIGHT_PERCENT = "bar_height_percent"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

        const val MIN_BLACKENING = 1
        const val MAX_BLACKENING = 5
        const val DEFAULT_BLACKENING = 3
        const val DEFAULT_PREVIEW_MATCHES_PRINTER = true

        const val MIN_STARTING_LIFE = 1
        const val MAX_STARTING_LIFE = 999
        const val DEFAULT_STARTING_LIFE = 20

        const val MIN_BAR_HEIGHT_PERCENT = 20
        const val MAX_BAR_HEIGHT_PERCENT = 50
        const val DEFAULT_BAR_HEIGHT_PERCENT = 33
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

    /** Life total both players start with after a reset. */
    var startingLife: Int
        get() = prefs.getInt(KEY_STARTING_LIFE, DEFAULT_STARTING_LIFE)
            .coerceIn(MIN_STARTING_LIFE, MAX_STARTING_LIFE)
        set(value) {
            prefs.edit {
                putInt(KEY_STARTING_LIFE, value.coerceIn(MIN_STARTING_LIFE, MAX_STARTING_LIFE))
            }
        }

    /** Turn the top player's counter by 180 degrees. */
    var rotatePlayerOne: Boolean
        get() = prefs.getBoolean(KEY_ROTATE_PLAYER_ONE, true)
        set(value) {
            prefs.edit { putBoolean(KEY_ROTATE_PLAYER_ONE, value) }
        }

    /** Height of the middle control bar in percent of the screen. */
    var barHeightPercent: Int
        get() = prefs.getInt(KEY_BAR_HEIGHT_PERCENT, DEFAULT_BAR_HEIGHT_PERCENT)
            .coerceIn(MIN_BAR_HEIGHT_PERCENT, MAX_BAR_HEIGHT_PERCENT)
        set(value) {
            prefs.edit {
                putInt(
                    KEY_BAR_HEIGHT_PERCENT,
                    value.coerceIn(MIN_BAR_HEIGHT_PERCENT, MAX_BAR_HEIGHT_PERCENT),
                )
            }
        }

    /** Keep the screen on while the app is open. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) {
            prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }
        }
}