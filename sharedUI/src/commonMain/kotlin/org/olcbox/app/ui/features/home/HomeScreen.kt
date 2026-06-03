package org.olcbox.app.ui.features.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.home_imported_from_clipboard
import multiplatform_app.sharedui.generated.resources.home_subscriptions_not_updated
import multiplatform_app.sharedui.generated.resources.home_subscriptions_updated
import multiplatform_app.sharedui.generated.resources.run
import multiplatform_app.sharedui.generated.resources.setup
import multiplatform_app.sharedui.generated.resources.stop
import org.olcbox.app.ui.components.StartButton
import org.olcbox.app.ui.features.home.components.AddConfigurationSheet
import org.olcbox.app.ui.features.home.components.HomeScreenAppBar
import org.olcbox.app.ui.features.home.components.LocationSelectorScreen
import org.olcbox.app.ui.features.home.components.LogsSheet
import org.olcbox.app.ui.features.home.components.RelayStatus
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    scrollState: ScrollState,
    onToggleClick: () -> Unit = { viewModel.ToggleVpn() },
    onImportFileRequested: () -> Unit = {},
    onImportFromClipboardRequested: (onImported: () -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    onScanQrRequested: () -> Unit = {},
    onCopyConfigRequested: () -> Unit = { viewModel.onCopyFullConfigClicked() },
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    showAppSettingsButton: Boolean = false,
    canScanQr: Boolean = false,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onOpenLocationSettings: (String?) -> Unit,
    onAddLocation: () -> Unit
) {
    var isLogsSheetOpen by remember { mutableStateOf(false) }
    var isAddSheetOpen by remember { mutableStateOf(false) }

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pingsState = locationViewModel.pingsState
    val locations = locationViewModel.locations.toList()
    val hasSubscriptions = locations.any { !it.subscriptionUrl.isNullOrBlank() }

    val requiresSetup = !state.canStartVpn && !state.isVpnConnected && !state.isVpnLoading
    val actionRunText = stringResource(Res.string.run)
    val actionStopText = stringResource(Res.string.stop)
    val actionSetupText = stringResource(Res.string.setup)
    val subscriptionsUpdatedTemplate = stringResource(Res.string.home_subscriptions_updated)
    val subscriptionsNotUpdatedText = stringResource(Res.string.home_subscriptions_not_updated)
    val importedFromClipboardText = stringResource(Res.string.home_imported_from_clipboard)

    val primaryActionLabel = when {
        requiresSetup -> actionSetupText
        state.isVpnLoading || state.isVpnConnected -> actionStopText
        else -> actionRunText
    }

    fun refreshSubscriptions() {
        viewModel.refreshSubscriptions { updatedCount ->
            locationViewModel.loadLocations {
                viewModel.restartVpnIfRunning()

                val message = if (updatedCount > 0) {
                    subscriptionsUpdatedTemplate.format(updatedCount)
                } else {
                    subscriptionsNotUpdatedText
                }

                scope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    fun refreshHttpPings(targetLocationIds: List<String>? = null) {
        locationViewModel.refreshPings(
            targetLocationIds = targetLocationIds,
            performPing = { config ->
                viewModel.performPingFor(config)
            },
        )
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        },
        topBar = {
            HomeScreenAppBar(
                onHistoryClick = { isLogsSheetOpen = true },
                showAppSettingsButton = showAppSettingsButton,
                onAppSettingsClick = onAppSettingsClick,
                showSplitTunnelingButton = showSplitTunnelingButton,
                onSplitTunnelingClick = onSplitTunnelingClick,
                onAddClick = { isAddSheetOpen = true }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RelayStatus(
                isActive = state.isVpnConnected,
                requiresSetup = requiresSetup
            )

            Spacer(modifier = Modifier.height(16.dp))

            StartButton(
                isActive = state.isVpnConnected,
                isLoading = state.isVpnLoading,
                requiresSetup = requiresSetup,
                label = primaryActionLabel,
                enabled = true,
                onClick = {
                    if (requiresSetup) {
                        isAddSheetOpen = true
                    } else {
                        onToggleClick()
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            LocationSelectorScreen(
                onRefreshClick = { targetIds ->
                    refreshHttpPings(targetIds)
                },
                onAddSubscriptionClick = {
                    isAddSheetOpen = true
                },
                locations = locations,
                selectedLocationId = locationViewModel.selectedLocationId,
                pingsState = pingsState,
                onLocationSelected = { id ->
                    locationViewModel.selectLocation(id) {
                        viewModel.loadCurrentConfig()
                        viewModel.restartVpnIfRunning()
                    }
                },
                onLocationSettingsClick = { id ->
                    onOpenLocationSettings(id)
                },
                onAddLocationClick = {
                    onAddLocation()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (isLogsSheetOpen) {
            val logs by viewModel.logs.collectAsState()

            LogsSheet(
                logs = logs,
                onSaveClick = {
                    onSaveLogsRequested(
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onShareClick = {
                    viewModel.onShareLogs(
                        onShared = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        onError = { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onDismiss = {
                    isLogsSheetOpen = false
                }
            )
        }

        if (isAddSheetOpen) {
            AddConfigurationSheet(
                canScanQr = canScanQr,
                hasSubscriptions = hasSubscriptions,
                onDismiss = {
                    isAddSheetOpen = false
                },
                onScanQrClick = {
                    isAddSheetOpen = false
                    onScanQrRequested()
                },
                onPasteLinkClick = {
                    isAddSheetOpen = false
                    onImportFromClipboardRequested(
                        {
                            scope.launch {
                                snackbarHostState.showSnackbar(importedFromClipboardText)
                            }
                        },
                        { message ->
                            scope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                },
                onImportFileClick = {
                    isAddSheetOpen = false
                    onImportFileRequested()
                },
                onUpdateSubscriptionsClick = {
                    isAddSheetOpen = false
                    refreshSubscriptions()
                },
                onAddCustomLocationClick = {
                    isAddSheetOpen = false
                    onAddLocation()
                }
            )
        }
    }
}
