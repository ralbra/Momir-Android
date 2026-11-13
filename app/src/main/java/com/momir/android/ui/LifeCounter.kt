package com.momir.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tap = 1. Holding repeats like the starting life buttons: by 1 at first, then to the next
 * multiple of 5.
 * Full-width tile: left half is minus, right half is plus.
 * With [stackedControls] (two or more counters share a row, so each tile is narrow): top half
 * is plus, bottom half is minus, because side-by-side areas would get too small to hit.
 * With [rotated] the whole counter (drawing and touch) is turned by 180 degrees, so the
 * player on the other side of the table gets the same behaviour.
 * The life text scales with the size of the tile, so 2 to 6 counters fit on the screen.
 */
@Composable
fun LifeCounter(
    life: Int,
    label: String,
    onDelta: (Int) -> Unit,
    background: Color,
    contentColor: Color,
    rotated: Boolean,
    stackedControls: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val lifeText = life.toString()
    val currentLife by rememberUpdatedState(life)
    val delta by rememberUpdatedState(onDelta)

    // Steps from the current life, so holding lands on multiples of 5.
    fun step(direction: Int, big: Boolean) {
        if (big) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        delta(holdStep(currentLife, direction, big) - currentLife)
    }

    val minus: (Boolean) -> Unit = { big -> step(-1, big) }
    val plus: (Boolean) -> Unit = { big -> step(1, big) }
    val holdStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer { rotationZ = if (rotated) 180f else 0f }
            .background(background),
    ) {
        val shortSide = if (maxWidth < maxHeight) maxWidth else maxHeight
        val digitScale = if (lifeText.length > 3) 3f / lifeText.length else 1f
        val lifeSize = (shortSide.value * 0.42f).coerceIn(32f, 96f) * digitScale

        if (stackedControls) {
            // Plus on top, minus at the bottom.
            Column(modifier = Modifier.fillMaxSize()) {
                TapZone(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    onStep = plus,
                    onHoldStart = holdStarted,
                )
                TapZone(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    onStep = minus,
                    onHoldStart = holdStarted,
                )
            }
        } else {
            // Minus on the left, plus on the right.
            Row(modifier = Modifier.fillMaxSize()) {
                TapZone(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onStep = minus,
                    onHoldStart = holdStarted,
                )
                TapZone(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onStep = plus,
                    onHoldStart = holdStarted,
                )
            }
        }

        val hintSize = if (stackedControls) 32.sp else 44.sp
        val hintPadding = if (stackedControls) {
            Modifier.padding(vertical = 6.dp)
        } else {
            Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
        }
        val minusAlignment = if (stackedControls) Alignment.BottomCenter else Alignment.CenterStart
        val plusAlignment = if (stackedControls) Alignment.TopCenter else Alignment.CenterEnd

        Text(
            text = "−",
            color = contentColor.copy(alpha = 0.35f),
            fontSize = hintSize,
            modifier = Modifier
                .align(minusAlignment)
                .then(hintPadding),
        )
        Text(
            text = "+",
            color = contentColor.copy(alpha = 0.35f),
            fontSize = hintSize,
            modifier = Modifier
                .align(plusAlignment)
                .then(hintPadding),
        )
        Text(
            text = lifeText,
            color = contentColor,
            fontSize = lifeSize.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )
        Text(
            text = label,
            color = contentColor.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(if (stackedControls) Alignment.TopStart else Alignment.TopCenter)
                .padding(top = 6.dp, start = if (stackedControls) 8.dp else 0.dp),
        )
    }
}

/** Steps once per tap and keeps stepping while held ([onStep] gets true for steps of 5). */
@Composable
private fun TapZone(
    modifier: Modifier,
    onStep: (big: Boolean) -> Unit,
    onHoldStart: () -> Unit,
) {
    val step by rememberUpdatedState(onStep)
    val holdStart by rememberUpdatedState(onHoldStart)
    Box(
        modifier = modifier.pointerInput(Unit) {
            coroutineScope {
                awaitEachGesture {
                    awaitFirstDown()
                    var repeated = false
                    val repeat = launch {
                        delay(HOLD_START_MS)
                        holdStart()
                        var count = 0
                        while (true) {
                            repeated = true
                            step(count >= HOLD_SMALL_STEPS)
                            count++
                            delay(holdDelayMs(count))
                        }
                    }
                    val up = waitForUpOrCancellation()
                    repeat.cancel()
                    if (up != null && !repeated) step(false)
                }
            }
        },
    )
}
