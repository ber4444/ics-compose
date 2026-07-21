package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.layout.ContentScale
import com.livingpresence.inner.circle.squared.generated.resources.Res
import com.livingpresence.inner.circle.squared.generated.resources.background_image
import org.jetbrains.compose.resources.painterResource

/**
 * Actuals shared by every non-Android target (iOS + web). Both open events via the
 * default in-app action and paint the same login background, so these live in the
 * `nonAndroidMain` source set rather than being duplicated per platform.
 */
actual fun onEventClick(eventNumber: Int, defaultAction: () -> Unit) {
    defaultAction()
}

@Composable
actual fun loginBackgroundModifier(): Modifier = Modifier.paint(
    painter = painterResource(Res.drawable.background_image),
    contentScale = ContentScale.Crop,
)
