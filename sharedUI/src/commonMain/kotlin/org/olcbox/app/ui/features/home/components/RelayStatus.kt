package org.olcbox.app.ui.features.home.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.home_connected
import multiplatform_app.sharedui.generated.resources.home_disconnected
import multiplatform_app.sharedui.generated.resources.home_no_location_selected
import multiplatform_app.sharedui.generated.resources.home_relay_active
import multiplatform_app.sharedui.generated.resources.home_relay_inactive
import multiplatform_app.sharedui.generated.resources.home_status_active_icon_desc
import multiplatform_app.sharedui.generated.resources.home_status_inactive_icon_desc
import org.jetbrains.compose.resources.stringResource

@Composable
fun RelayStatus(
    isActive: Boolean,
    requiresSetup: Boolean = false,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            isActive -> MaterialTheme.colorScheme.primaryContainer
            requiresSetup -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "relayStatusContainer"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = when {
            isActive -> MaterialTheme.colorScheme.primary
            requiresSetup -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        label = "relayStatusIconContainer"
    )
    val iconContentColor = when {
        isActive -> MaterialTheme.colorScheme.onPrimary
        requiresSetup -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val textColor = when {
        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        requiresSetup -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface
    }
    val relayActiveText = stringResource(Res.string.home_relay_active)
    val relayInactiveText = stringResource(Res.string.home_relay_inactive)
    val connectedText = stringResource(Res.string.home_connected)
    val disconnectedText = stringResource(Res.string.home_disconnected)
    val noLocationSelectedText = stringResource(Res.string.home_no_location_selected)
    val activeIconDesc = stringResource(Res.string.home_status_active_icon_desc)
    val inactiveIconDesc = stringResource(Res.string.home_status_inactive_icon_desc)
    val title = when {
        isActive -> relayActiveText
        requiresSetup -> relayInactiveText
        else -> relayInactiveText
    }
    val subtitle = when {
        isActive -> connectedText
        requiresSetup -> noLocationSelectedText
        else -> disconnectedText
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = iconContainerColor,
                contentColor = iconContentColor
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isActive) {
                        Icon(
                            tint = iconContentColor,
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = activeIconDesc
                        )
                    } else {
                        Icon(
                            tint = iconContentColor,
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = inactiveIconDesc
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(24.dp))

            Column {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = textColor.copy(alpha = 0.82f)
                )
            }
        }
    }
}
