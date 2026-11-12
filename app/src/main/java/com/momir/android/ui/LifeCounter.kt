package com.momir.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val lifeText = life.toString()

    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer { rotationZ = if (rotated) 180f else 0f }
            .background(background),
    ) {
        val compact = maxWidth < 200.dp
        val shortSide = if (maxWidth < maxHeight) maxWidth else maxHeight
        val digitScale = if (lifeText.length > 3) 3f / lifeText.length else 1f
        val lifeSize = (shortSide.value * 0.42f).coerceIn(32f, 96f) * digitScale
        val hintSize = if (compact) 24.sp else 44.sp
        val hintPadding = if (compact) 10.dp else 28.dp
        val startAlignment = if (compact) Alignment.BottomStart else Alignment.CenterStart
        val endAlignment = if (compact) Alignment.BottomEnd else Alignment.CenterEnd

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
            fontSize = hintSize,
            modifier = Modifier
                .align(startAlignment)
                .padding(horizontal = hintPadding, vertical = 4.dp),
        )
        Text(
            text = "+",
            color = contentColor.copy(alpha = 0.35f),
            fontSize = hintSize,
            modifier = Modifier
                .align(endAlignment)
                .padding(horizontal = hintPadding, vertical = 4.dp),
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
                .align(Alignment.TopCenter)
                .padding(top = 6.dp),
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