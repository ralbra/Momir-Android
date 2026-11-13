package com.momir.android.ui

/** Delay before holding starts to repeat, in milliseconds. */
internal const val HOLD_START_MS = 400L

/** Steps of 1 before holding switches to steps of 5. */
internal const val HOLD_SMALL_STEPS = 6

internal fun holdDelayMs(stepsDone: Int): Long = if (stepsDone > HOLD_SMALL_STEPS) 220L else 120L

/**
 * One step of a counter: 1 in [direction], or ([big]) to the next multiple of 5. Works for
 * negative values too (-3 goes up to 0 and down to -5).
 */
internal fun holdStep(value: Int, direction: Int, big: Boolean): Int = when {
    !big -> value + direction
    direction > 0 -> (Math.floorDiv(value, 5) + 1) * 5
    else -> Math.floorDiv(value - 1, 5) * 5
}
