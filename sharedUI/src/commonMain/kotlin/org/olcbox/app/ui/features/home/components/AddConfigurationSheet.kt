package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.home_add_connection_subtitle
import multiplatform_app.sharedui.generated.resources.home_add_connection_title
import multiplatform_app.sharedui.generated.resources.home_add_create_custom_title
import multiplatform_app.sharedui.generated.resources.home_add_create_custom_value
import multiplatform_app.sharedui.generated.resources.home_add_import_file_title
import multiplatform_app.sharedui.generated.resources.home_add_import_file_value
import multiplatform_app.sharedui.generated.resources.home_add_paste_link_title
import multiplatform_app.sharedui.generated.resources.home_add_paste_link_value
import multiplatform_app.sharedui.generated.resources.home_add_scan_qr_title
import multiplatform_app.sharedui.generated.resources.home_add_scan_qr_value
import multiplatform_app.sharedui.generated.resources.home_add_update_subscriptions_title
import multiplatform_app.sharedui.generated.resources.home_add_update_subscriptions_value
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConfigurationSheet(
    canScanQr: Boolean,
    hasSubscriptions: Boolean,
    onDismiss: () -> Unit,
    onScanQrClick: () -> Unit,
    onPasteLinkClick: () -> Unit,
    onImportFileClick: () -> Unit,
    onUpdateSubscriptionsClick: () -> Unit,
    onAddCustomLocationClick: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val addConnectionTitleText = stringResource(Res.string.home_add_connection_title)
    val addConnectionSubtitleText = stringResource(Res.string.home_add_connection_subtitle)
    val scanQrTitleText = stringResource(Res.string.home_add_scan_qr_title)
    val scanQrValueText = stringResource(Res.string.home_add_scan_qr_value)
    val pasteLinkTitleText = stringResource(Res.string.home_add_paste_link_title)
    val pasteLinkValueText = stringResource(Res.string.home_add_paste_link_value)
    val importFileTitleText = stringResource(Res.string.home_add_import_file_title)
    val importFileValueText = stringResource(Res.string.home_add_import_file_value)
    val updateSubscriptionsTitleText = stringResource(Res.string.home_add_update_subscriptions_title)
    val updateSubscriptionsValueText = stringResource(Res.string.home_add_update_subscriptions_value)
    val createCustomLocationTitleText = stringResource(Res.string.home_add_create_custom_title)
    val createCustomLocationValueText = stringResource(Res.string.home_add_create_custom_value)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            AddSheetHeader(
                title = addConnectionTitleText,
                subtitle = addConnectionSubtitleText
            )

            Spacer(Modifier.height(20.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canScanQr) {
                    AddSheetAction(
                        title = scanQrTitleText,
                        value = scanQrValueText,
                        icon = Icons.Outlined.QrCodeScanner,
                        onClick = onScanQrClick
                    )
                }

                AddSheetAction(
                    title = pasteLinkTitleText,
                    value = pasteLinkValueText,
                    icon = Icons.AutoMirrored.Outlined.Input,
                    onClick = onPasteLinkClick
                )

                AddSheetAction(
                    title = importFileTitleText,
                    value = importFileValueText,
                    icon = Icons.Outlined.FileOpen,
                    onClick = onImportFileClick
                )

                if (hasSubscriptions) {
                    AddSheetAction(
                        title = updateSubscriptionsTitleText,
                        value = updateSubscriptionsValueText,
                        icon = Icons.Outlined.Refresh,
                        showChevron = false,
                        onClick = onUpdateSubscriptionsClick
                    )
                }

                AddSheetAction(
                    title = createCustomLocationTitleText,
                    value = createCustomLocationValueText,
                    icon = Icons.Outlined.Add,
                    onClick = onAddCustomLocationClick
                )
            }
        }
    }
}

@Composable
private fun AddSheetHeader(
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddSheetAction(
    title: String,
    value: String,
    icon: ImageVector,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showChevron) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
