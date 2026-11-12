package com.momir.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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

private const val TAP_STEP = 1
private const val LONG_PRESS_STEP = 5

/**
 * Tap = 1, long press = 5.
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

    val tapMinus = { onDelta(-TAP_STEP) }
    val tapPlus = { onDelta(TAP_STEP) }
    val longMinus = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onDelta(-LONG_PRESS_STEP)
    }
    val longPlus = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onDelta(LONG_PRESS_STEP)
    }

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
                    onTap = tapPlus,
                    onLongPress = longPlus,
                )
                TapZone(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    onTap = tapMinus,
                    onLongPress = longMinus,
                )
            }
        } else {
            // Minus on the left, plus on the right.
            Row(modifier = Modifier.fillMaxSize()) {
                TapZone(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onTap = tapMinus,
                    onLongPress = longMinus,
                )
                TapZone(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onTap = tapPlus,
                    onLongPress = longPlus,
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