package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.home_logs_close
import multiplatform_app.sharedui.generated.resources.home_logs_title
import multiplatform_app.sharedui.generated.resources.home_save
import multiplatform_app.sharedui.generated.resources.home_share
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsSheet(
    logs: List<String>,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    val scope = rememberCoroutineScope()

    val closeSheet = {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        LogsContent(
            logs = logs,
            modifier = Modifier.fillMaxHeight(0.8f),
            onSaveClick = onSaveClick,
            onShareClick = onShareClick,
            onCloseClick = { closeSheet() }
        )
    }
}

@Composable
fun LogsContent(
    logs: List<String>,
    modifier: Modifier = Modifier,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    val logsTitleText = stringResource(Res.string.home_logs_title)
    val saveText = stringResource(Res.string.home_save)
    val shareText = stringResource(Res.string.home_share)
    val closeLogsText = stringResource(Res.string.home_logs_close)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = logsTitleText,
                style = MaterialTheme.typography.headlineSmall,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = logs.isNotEmpty(),
                    onClick = onSaveClick
                ) {
                    Text(saveText)
                }
                TextButton(
                    enabled = logs.isNotEmpty(),
                    onClick = onShareClick
                ) {
                    Text(shareText)
                }

                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = closeLogsText,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.05f))
        ) {
            LogLines(
                logs = logs,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp)
            )
        }
    }
}

@Composable
fun LogLines(
    logs: List<String>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding
    ) {
        itemsIndexed(
            items = logs,
            key = { index, log -> "$index:$log" }
        ) { _, log ->
            Text(
                text = log,
                fontSize = 12.sp,
                color = getLogColor(log),
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun getLogColor(log: String): Color {
    return when {
        log.contains("❌") || log.contains("Error") -> Color.Red
        log.contains("✅") || log.contains("OK") -> Color(0xFF4CAF50)
        log.contains("⚠️") -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
