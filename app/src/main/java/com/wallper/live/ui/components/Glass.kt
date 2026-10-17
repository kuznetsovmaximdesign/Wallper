package com.wallper.live.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Frosted surface laid over live video. Real background blur is impossible over a
 * SurfaceView, so depth comes from a translucent fill, a top highlight and a hairline.
 */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF0C0C12),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.62f))
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.10f),
                    0.35f to Color.White.copy(alpha = 0.02f),
                    1f to Color.Transparent,
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
        content = content,
    )
}
