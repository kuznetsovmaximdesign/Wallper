package com.wallper.live.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Skeleton placeholder shown while a cover is being cut from the footage.
 * The sweep is read only while drawing, so it redraws the placeholder without
 * recomposing the card on every frame.
 */
@Composable
fun Modifier.shimmer(): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val sweep = transition.animateFloat(
        initialValue = -600f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    return drawBehind {
        val x = sweep.value
        drawRect(
            Brush.linearGradient(
                colors = ShimmerColors,
                start = Offset(x, x * 0.4f),
                end = Offset(x + 600f, (x + 600f) * 0.4f),
            ),
        )
    }
}

private val ShimmerColors = listOf(Color(0xFF15151B), Color(0xFF23232C), Color(0xFF15151B))
