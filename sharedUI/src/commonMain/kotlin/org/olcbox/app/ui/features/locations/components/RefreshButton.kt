package org.olcbox.app.ui.features.locations.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.home_ping
import org.jetbrains.compose.resources.stringResource

@Composable
fun RefreshButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
    tint: Color
) {
    val pingText = stringResource(Res.string.home_ping)
    val rotation by if (isRefreshing) {
        val infiniteTransition = rememberInfiniteTransition()
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ), label = ""
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    TextButton(
        modifier = Modifier
            .height(48.dp),
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = tint)
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .rotate(rotation)
        )
        Spacer(Modifier.size(8.dp))
        Text(pingText)
    }
}
