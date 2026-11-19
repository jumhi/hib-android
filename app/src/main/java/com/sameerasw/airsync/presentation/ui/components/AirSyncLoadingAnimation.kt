package com.sameerasw.airsync.presentation.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** H.I.B's code-native loading mark: sleepy eyes wake while a device signal pulses. */
@Composable
fun AirSyncLoadingAnimation(
    isPlus: Boolean = false,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "hib_loading")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "signal_pulse"
    )
    val awake by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "sleepy_eyes"
    )

    Canvas(modifier = modifier) {
        val unit = size.minDimension
        val origin = Offset((size.width - unit) / 2f, (size.height - unit) / 2f)
        val cyan = Color(0xFF20D9FF)
        val indigo = Color(0xFF111533)
        val lime = Color(0xFFB9FF38)
        val pillarWidth = unit * 0.18f
        val pillarHeight = unit * 0.58f
        val top = origin.y + unit * 0.24f

        drawRoundRect(
            color = cyan,
            topLeft = Offset(origin.x + unit * 0.23f, top),
            size = Size(pillarWidth, pillarHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillarWidth / 2f)
        )
        drawRoundRect(
            color = cyan,
            topLeft = Offset(origin.x + unit * 0.59f, top),
            size = Size(pillarWidth, pillarHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(pillarWidth / 2f)
        )
        drawRoundRect(
            color = cyan,
            topLeft = Offset(origin.x + unit * 0.34f, origin.y + unit * 0.48f),
            size = Size(unit * 0.32f, unit * 0.14f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit * 0.07f)
        )

        val eyeHeight = unit * (0.016f + 0.03f * awake)
        drawLine(
            color = indigo,
            start = Offset(origin.x + unit * 0.275f, origin.y + unit * 0.43f),
            end = Offset(origin.x + unit * 0.365f, origin.y + unit * 0.43f + eyeHeight),
            strokeWidth = unit * 0.035f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = indigo,
            start = Offset(origin.x + unit * 0.635f, origin.y + unit * 0.43f + eyeHeight),
            end = Offset(origin.x + unit * 0.725f, origin.y + unit * 0.43f),
            strokeWidth = unit * 0.035f,
            cap = StrokeCap.Round
        )

        val signalCenter = Offset(origin.x + unit * 0.5f, origin.y + unit * 0.17f)
        drawCircle(color = lime, radius = unit * 0.045f, center = signalCenter)
        drawCircle(
            color = lime.copy(alpha = (1.35f - pulse).coerceIn(0f, 0.8f)),
            radius = unit * 0.08f * pulse,
            center = signalCenter,
            style = Stroke(width = unit * 0.018f)
        )
        if (isPlus) {
            drawCircle(color = lime, radius = unit * 0.012f, center = Offset(origin.x + unit * 0.82f, origin.y + unit * 0.28f))
        }
    }
}
