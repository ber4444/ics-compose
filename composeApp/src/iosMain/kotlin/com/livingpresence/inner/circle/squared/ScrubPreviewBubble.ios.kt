package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun ScrubPreviewBubble(
    bitmap: ImageBitmap?,
    positionLabel: String,
    thumbCenterRootX: Float,
    boxRootX: Float,
    boxWidthPx: Float,
) {
    val density = LocalDensity.current
    val bubbleWidthDp = 140.dp
    val bubbleWidthPx = with(density) { bubbleWidthDp.toPx() }
    val maxThumbX = boxRootX + boxWidthPx
    
    val safeThumbX = thumbCenterRootX.coerceIn(
        boxRootX + bubbleWidthPx / 2f,
        maxThumbX - bubbleWidthPx / 2f
    )
    val offsetX = safeThumbX - boxRootX - (bubbleWidthPx / 2f)

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), -20) }
            .size(width = bubbleWidthDp, height = 90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray)
            .border(2.dp, Color.White, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Text(
            text = positionLabel,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}
