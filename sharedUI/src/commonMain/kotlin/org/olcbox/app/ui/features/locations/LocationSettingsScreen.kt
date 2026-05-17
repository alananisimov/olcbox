package org.olcbox.app.ui.features.locations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.components.PingButton
import org.olcbox.app.ui.features.home.HomeScreenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSettingsTopBar(
    shareEnabled: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit
) {
    TopAppBar(
        title = { Text("Location settings") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            IconButton(
                onClick = onShare,
                enabled = shareEnabled,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(48.dp),
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share location"
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSettingsScreen(
    viewModel: LocationViewModel,
    homeViewModel: HomeScreenViewModel,
    onShareLocationRequested: (LocationConfig) -> Unit = {},
    onBack: () -> Unit
) {
    val config = viewModel.editingConfig
    val name = viewModel.editingName
    val isSaving = viewModel.isSaving
    val focusManager = LocalFocusManager.current
    val normalizedTransport = LocationConfig.normalizeTransport(
        config.transport,
        config.bypassProvider
    )
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    Scaffold(
        topBar = {
            LocationSettingsTopBar(
                shareEnabled = viewModel.isFormValid && !isSaving,
                onBack = onBack,
                onShare = { onShareLocationRequested(viewModel.editingConfig) }
            )
        },
        bottomBar = {
            if (!isKeyboardVisible) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ActionsBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        showDelete = viewModel.editingId != null,
                        isSaving = isSaving,
                        isFormValid = viewModel.isFormValid,
                        onDelete = {
                            viewModel.editingId?.let { id ->
                                viewModel.deleteLocation(id) { onBack() }
                            } ?: onBack()
                        },
                        onSave = {
                            viewModel.saveEditing {
                                homeViewModel.loadCurrentConfig()
                                homeViewModel.restartVpnIfRunning()
                                onBack()
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsTextField(
                    value = name,
                    onValueChange = viewModel::onNameChanged,
                    label = "Name",
                    placeholder = "Location name",
                    enabled = !isSaving,
                    isError = viewModel.nameError != null,
                    supportingText = viewModel.nameError,
                    leadingIcon = Icons.Rounded.Public,
                    onClear = { viewModel.onNameChanged("") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
            }

            item {
                CarrierPicker(
                    selectedProvider = config.bypassProvider,
                    enabled = !isSaving,
                    onProviderSelected = viewModel::onBypassProviderChanged
                )
            }

            item {
                TransportPicker(
                    selectedProvider = config.bypassProvider,
                    selectedTransport = config.transport,
                    enabled = !isSaving,
                    onTransportSelected = viewModel::onTransportChanged
                )
            }

            if (normalizedTransport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                item {
                    Vp8OptionsCard(
                        fps = config.vp8Fps,
                        batch = config.vp8Batch,
                        enabled = !isSaving,
                        onFpsChanged = viewModel::onVp8FpsChanged,
                        onBatchChanged = viewModel::onVp8BatchChanged
                    )
                }
            }

            item {
                SettingsTextField(
                    value = config.id,
                    onValueChange = viewModel::onServerChanged,
                    label = "Room ID",
                    placeholder = roomIdPlaceholder(config.bypassProvider),
                    enabled = !isSaving,
                    isError = viewModel.serverError != null,
                    supportingText = viewModel.serverError,
                    leadingIcon = Icons.Rounded.MeetingRoom,
                    onClear = { viewModel.onServerChanged("") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
            }

            item {
                SettingsTextField(
                    value = config.key,
                    onValueChange = viewModel::onPasswordChanged,
                    label = "Encryption key",
                    placeholder = "64 hex characters",
                    enabled = !isSaving,
                    isError = viewModel.keyError != null,
                    supportingText = viewModel.keyError,
                    leadingIcon = Icons.Rounded.Key,
                    onClear = { viewModel.onPasswordChanged("") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
            }

            item {
                SettingsTextField(
                    value = config.clientId,
                    onValueChange = viewModel::onClientIdChanged,
                    label = "Client ID",
                    placeholder = "android-01",
                    enabled = !isSaving,
                    isError = viewModel.clientIdError != null,
                    supportingText = viewModel.clientIdError,
                    leadingIcon = Icons.Rounded.Person,
                    onClear = { viewModel.onClientIdChanged("") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    )
                )
            }

            item {
                PingButton(
                    homeViewModel = homeViewModel,
                    configGetter = { viewModel.editingConfig }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CarrierPicker(
    selectedProvider: String,
    enabled: Boolean,
    onProviderSelected: (String) -> Unit
) {
    val selected = LocationConfig.normalizeProvider(selectedProvider)
    val options = LocationConfig.supportedBypassProviders

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(
            title = "Carrier"
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, provider ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    selected = selected == provider,
                    onClick = { onProviderSelected(provider) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = LocationConfig.providerDisplayName(provider),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun TransportPicker(
    selectedProvider: String,
    selectedTransport: String,
    enabled: Boolean,
    onTransportSelected: (String) -> Unit
) {
    val provider = LocationConfig.normalizeProvider(selectedProvider)
    val selected = LocationConfig.normalizeTransport(selectedTransport, provider)
    val options = LocationConfig.supportedTransportsForProvider(provider)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(
            title = "Transport"
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, transport ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    selected = selected == transport,
                    onClick = { onTransportSelected(transport) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = LocationConfig.transportDisplayName(transport),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun Vp8OptionsCard(
    fps: Int,
    batch: Int,
    enabled: Boolean,
    onFpsChanged: (String) -> Unit,
    onBatchChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle(
            title = "VP8 options",
            subtitle = "Fine-tune stream performance"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NumericTextField(
                value = fps,
                label = "FPS",
                enabled = enabled,
                onValueChange = onFpsChanged,
                modifier = Modifier.weight(1f)
            )
            NumericTextField(
                value = batch,
                label = "Batch",
                enabled = enabled,
                onValueChange = onBatchChanged,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    isError: Boolean,
    supportingText: String?,
    leadingIcon: ImageVector,
    onClear: () -> Unit,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions(),
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        isError = isError,
        singleLine = true,
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            if (value.isNotEmpty() && enabled) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        }
    )
}

@Composable
private fun NumericTextField(
    value: Int,
    label: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value.takeIf { it > 0 }?.toString().orEmpty(),
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        modifier = modifier
    )
}

@Composable
private fun ActionsBar(
    modifier: Modifier = Modifier,
    showDelete: Boolean,
    isSaving: Boolean,
    isFormValid: Boolean,
    onDelete: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDelete) {
            OutlinedIconButton(
                onClick = onDelete,
                modifier = Modifier.size(56.dp),
                enabled = !isSaving,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
            }

            Spacer(modifier = Modifier.width(14.dp))
        }

        Button(
            onClick = onSave,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            enabled = !isSaving && isFormValid,
            shape = CircleShape.copy(all = androidx.compose.foundation.shape.CornerSize(28.dp))
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun roomIdPlaceholder(provider: String): String {
    return when (LocationConfig.normalizeProvider(provider)) {
        LocationConfig.PROVIDER_TELEMOST -> "12345678901234"
        LocationConfig.PROVIDER_JITSI -> "https://meet.cryptopro.ru/room-name"
        LocationConfig.PROVIDER_JAZZ -> "room id or any"
        LocationConfig.PROVIDER_WB_STREAM -> "123e4567-e89b-12d3-a456-426614174000"
        else -> "room id"
    }
}
