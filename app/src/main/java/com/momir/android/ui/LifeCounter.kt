package com.momir.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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

private const val TAP_STEP = 1
private const val LONG_PRESS_STEP = 5

/**
 * Left half: minus, right half: plus. Tap = 1, long press = 5.
 * With [rotated] the whole counter (drawing and touch) is turned by 180 degrees, so the
 * player on the other side of the table gets the same left/right behaviour.
 */
@Composable
fun LifeCounter(
    life: Int,
    onDelta: (Int) -> Unit,
    background: Color,
    contentColor: Color,
    rotated: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = if (rotated) 180f else 0f }
            .background(background),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            TapZone(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onTap = { onDelta(-TAP_STEP) },
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelta(-LONG_PRESS_STEP)
                },
            )
            TapZone(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onTap = { onDelta(TAP_STEP) },
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelta(LONG_PRESS_STEP)
                },
            )
        }

        Text(
            text = "−",
            color = contentColor.copy(alpha = 0.35f),
            fontSize = 44.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 28.dp),
        )
        Text(
            text = "+",
            color = contentColor.copy(alpha = 0.35f),
            fontSize = 44.sp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 28.dp),
        )
        Text(
            text = life.toString(),
            color = contentColor,
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun TapZone(
    modifier: Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { tap() },
                onLongPress = { longPress() },
            )
        },
    )
}