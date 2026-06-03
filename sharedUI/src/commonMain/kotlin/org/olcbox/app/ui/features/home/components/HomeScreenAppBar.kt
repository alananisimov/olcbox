package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.home_appbar_subtitle
import multiplatform_app.sharedui.generated.resources.home_content_add_configuration
import multiplatform_app.sharedui.generated.resources.home_content_application_settings
import multiplatform_app.sharedui.generated.resources.home_content_history
import multiplatform_app.sharedui.generated.resources.home_content_split_tunneling
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenAppBar(
    onHistoryClick: () -> Unit = {},
    showAppSettingsButton: Boolean = false,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onAddClick: () -> Unit = {}
) {
    val subtitleText = stringResource(Res.string.home_appbar_subtitle)
    val applicationSettingsDescText = stringResource(Res.string.home_content_application_settings)
    val historyDescText = stringResource(Res.string.home_content_history)
    val splitTunnelingDescText = stringResource(Res.string.home_content_split_tunneling)
    val addConfigurationDescText = stringResource(Res.string.home_content_add_configuration)
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "olcbox",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        navigationIcon = {
            if (showAppSettingsButton) {
                IconButton(onClick = onAppSettingsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = applicationSettingsDescText,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                IconButton(onClick = onHistoryClick) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = historyDescText,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        actions = {
            if (showSplitTunnelingButton) {
                IconButton(onClick = onSplitTunnelingClick) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = splitTunnelingDescText,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            IconButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = addConfigurationDescText,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}
