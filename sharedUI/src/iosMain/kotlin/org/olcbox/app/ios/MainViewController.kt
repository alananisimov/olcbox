package org.olcbox.app.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.launch
import org.olcbox.app.data.datasource.IosLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.IosLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.IosConfigImporter
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.share.ConfigShareService
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.components.AppConnectionModeOption
import org.olcbox.app.ui.components.ApplicationSettingsSheet
import org.olcbox.app.ui.components.ApplicationUpdateOfferSheet
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.update.IosUpdateSettingsStore
import org.olcbox.app.update.identity
import org.olcbox.app.update.isDownloaded
import org.olcbox.app.update.isUpdateCheckDue
import org.olcbox.app.update.shouldShowOffer
import org.olcbox.app.vpn.IosConnectionMode
import org.olcbox.app.vpn.IosVpnManager
import platform.UIKit.UIViewController

class IosAppFactory {
    fun createSession(
        platformBridge: IosPlatformBridge,
        olcRtcBridge: IosOlcRtcBridge,
        tunnelBridge: IosTunnelBridge? = null
    ): IosAppSession {
        return IosAppSession(platformBridge, olcRtcBridge, tunnelBridge)
    }

    fun createViewController(
        platformBridge: IosPlatformBridge,
        olcRtcBridge: IosOlcRtcBridge,
        tunnelBridge: IosTunnelBridge? = null
    ): UIViewController {
        return createSession(platformBridge, olcRtcBridge, tunnelBridge).createViewController()
    }
}

class IosAppSession internal constructor(
    private val platformBridge: IosPlatformBridge,
    olcRtcBridge: IosOlcRtcBridge,
    tunnelBridge: IosTunnelBridge?
) {
    private val dependencies = IosAppDependencies(platformBridge, olcRtcBridge, tunnelBridge)

    fun createViewController(): UIViewController {
        return ComposeUIViewController {
            IosApp(platformBridge, dependencies)
        }
    }

    fun close() {
        dependencies.close()
    }
}

private class IosAppDependencies(
    platformBridge: IosPlatformBridge,
    olcRtcBridge: IosOlcRtcBridge,
    tunnelBridge: IosTunnelBridge?
) {
    private val locationsDataSource = IosLocationsDataSourceImpl()
    val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
    val vpnManager = IosVpnManager(locationsRepository, olcRtcBridge, tunnelBridge)
    val updateService = AppUpdateService(
        deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
    )
    val updateSettingsStore = IosUpdateSettingsStore()
    val homeViewModel = HomeScreenViewModel(
        vpnManager = vpnManager,
        locationsRepository = locationsRepository,
        configImporter = IosConfigImporter(platformBridge),
        logExporter = IosLogExporter(platformBridge)
    )
    val locationViewModel = LocationViewModel(locationsRepository)

    fun close() {
        vpnManager.close()
    }
}

@Composable
private fun IosApp(
    platformBridge: IosPlatformBridge,
    dependencies: IosAppDependencies
) {
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var isAppSettingsOpen by remember { mutableStateOf(false) }
    var updateSettings by remember { mutableStateOf(AppUpdateSettings()) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var updateDownloadProgress by remember { mutableStateOf<Float?>(null) }
    var updateOffer by remember { mutableStateOf<AppUpdateInfo?>(null) }

    fun reloadLocationsAfterImport(onComplete: () -> Unit = {}) {
        dependencies.locationViewModel.loadLocations {
            dependencies.homeViewModel.loadCurrentConfig(onComplete)
        }
    }

    suspend fun saveUpdateSettings(settings: AppUpdateSettings) {
        val normalized = settings.normalized()
        updateSettings = normalized
        dependencies.updateSettingsStore.save(normalized)
    }

    fun checkUpdate(manual: Boolean) {
        scope.launch {
            val previousSettings = updateSettings
            val checkStartedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!manual && !previousSettings.isUpdateCheckDue(checkStartedAt)) return@launch

            updateStatusText = "Checking ${previousSettings.channel.name.lowercase()}..."
            val result = dependencies.updateService.check(previousSettings.channel)
            val checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val checkedSettings = previousSettings.copy(lastCheckAtEpochMs = checkedAt).normalized()
            saveUpdateSettings(checkedSettings)
            result.fold(
                onSuccess = { info ->
                    if (manual || info.shouldShowOffer(previousSettings, checkedAt)) {
                        if (info.isDownloaded(checkedSettings)) {
                            updateOffer = null
                            updateStatusText = "Latest ${info.channel.name.lowercase()} is already downloaded"
                        } else if (info.isUpdateAvailable) {
                            updateOffer = info
                            updateStatusText = "${info.channel.name} update available: ${info.version}"
                        } else {
                            updateOffer = null
                            updateStatusText = "Olcbox is up to date"
                        }
                    } else {
                        updateOffer = null
                        updateStatusText = null
                    }
                },
                onFailure = { error ->
                    updateStatusText = error.message ?: "Update check failed"
                }
            )
        }
    }

    fun laterUpdate(info: AppUpdateInfo) {
        scope.launch {
            saveUpdateSettings(updateSettings.copy(lastSeenUpdateVersion = info.identity()))
            updateOffer = null
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        updateStatusText = "Install ${info.version} from the release page"
        updateOffer = null
    }

    LaunchedEffect(Unit) {
        val loaded = dependencies.updateSettingsStore.load()
        updateSettings = loaded
        dependencies.locationViewModel.loadLocations()
        dependencies.homeViewModel.loadCurrentConfig()
        checkUpdate(manual = false)
    }

    AppTheme {
        val logs by dependencies.homeViewModel.logs.collectAsState()
        val homeState by dependencies.homeViewModel.state.collectAsState()
        val socksProxySettings by dependencies.vpnManager.socksProxySettings.collectAsState()
        val connectionMode by dependencies.vpnManager.connectionMode.collectAsState()
        val isTun = connectionMode == IosConnectionMode.Tun
        val connectionSummary = if (isTun) {
            "VPN · Full tunnel"
        } else {
            "SOCKS5 127.0.0.1:${socksProxySettings.port}"
        }
        val connectionModeValue = if (isTun) "VPN · Full tunnel" else "Proxy · Local SOCKS5"
        val connectionModeOptions = if (dependencies.vpnManager.supportsTunMode) {
            listOf(
                AppConnectionModeOption(
                    id = IosConnectionMode.Proxy.value,
                    title = "Proxy",
                    subtitle = "Local SOCKS5 endpoint",
                    selected = !isTun
                ),
                AppConnectionModeOption(
                    id = IosConnectionMode.Tun.value,
                    title = "VPN",
                    subtitle = "Full device tunnel",
                    selected = isTun
                )
            )
        } else {
            emptyList()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            OlcboxAppContent(
                homeViewModel = dependencies.homeViewModel,
                locationViewModel = dependencies.locationViewModel,
                currentScreen = currentScreen,
                onNavigate = { screen -> currentScreen = screen },
                onToggleClick = {
                    dependencies.homeViewModel.ToggleVpn()
                },
                onImportFileRequested = {
                    platformBridge.pickConfigText(object : IosTextCallback {
                        override fun onSuccess(text: String) {
                            dependencies.homeViewModel.onImportFullConfig(text) {
                                reloadLocationsAfterImport {
                                    platformBridge.showMessage("Config imported")
                                }
                            }
                        }

                        override fun onError(message: String) {
                            platformBridge.showMessage(message)
                        }
                    })
                },
                onImportFromClipboardRequested = { onImported, onError ->
                    dependencies.homeViewModel.onPasteFromClipboard(
                        onComplete = {
                            reloadLocationsAfterImport(onImported)
                        },
                        onError = onError
                    )
                },
                onScanQrRequested = {},
                onCopyConfigRequested = {
                    dependencies.homeViewModel.onCopyFullConfigClicked()
                },
                onShareLocationRequested = { config: LocationConfig ->
                    platformBridge.shareText("Location", ConfigShareService.olcRtcUri(config))
                },
                onSaveLogsRequested = { onSaved, onError ->
                    dependencies.homeViewModel.onSaveLogsToFile(
                        target = dependencies.homeViewModel.suggestedLogsFileName(),
                        onSaved = onSaved,
                        onError = onError
                    )
                },
                showAppSettingsButton = true,
                showSplitTunnelingButton = false,
                canScanQr = false,
                onAppSettingsClick = { isAppSettingsOpen = true },
                onSplitTunnelingClick = {}
            )

            if (isAppSettingsOpen) {
                ApplicationSettingsSheet(
                    updateSettings = updateSettings,
                    updateStatusText = updateStatusText,
                    updateDownloadProgress = updateDownloadProgress,
                    updateOffer = updateOffer,
                    subscriptions = iosSubscriptionItems(dependencies.locationViewModel.locations.toList()),
                    logs = logs,
                    connectionSummary = connectionSummary,
                    connectionDetails = if (isTun) {
                        listOf(
                            "Mode" to "Full device tunnel",
                            "Upstream" to "olcRTC SOCKS5 (in-extension)",
                            "DNS" to "1.1.1.1"
                        )
                    } else {
                        listOf(
                            "Mode" to "Local SOCKS5 proxy",
                            "Host" to "127.0.0.1",
                            "Port" to socksProxySettings.port.toString()
                        )
                    },
                    socksProxySettings = socksProxySettings,
                    isConnectionActive = homeState.isVpnConnected,
                    connectionModeValue = connectionModeValue,
                    connectionModeOptions = connectionModeOptions,
                    onConnectionModeSelected = { id ->
                        dependencies.vpnManager.selectConnectionMode(IosConnectionMode.fromValue(id))
                        if (homeState.isVpnConnected) {
                            dependencies.homeViewModel.restartVpnIfRunning()
                        }
                    },
                    onDismiss = { isAppSettingsOpen = false },
                    onCopyConfigClick = {
                        dependencies.homeViewModel.onCopyFullConfigClicked()
                    },
                    onSaveLogsClick = {
                        dependencies.homeViewModel.onSaveLogsToFile(
                            target = dependencies.homeViewModel.suggestedLogsFileName(),
                            onSaved = platformBridge::showMessage,
                            onError = platformBridge::showMessage
                        )
                    },
                    onShareLogsClick = {
                        dependencies.homeViewModel.onShareLogs(
                            onShared = platformBridge::showMessage,
                            onError = platformBridge::showMessage
                        )
                    },
                    onUpdateIntervalSelected = { hours ->
                        scope.launch {
                            saveUpdateSettings(updateSettings.copy(intervalHours = hours))
                        }
                    },
                    onCheckUpdatesClick = { checkUpdate(manual = true) },
                    onDownloadUpdateClick = ::downloadUpdate,
                    onLaterUpdateClick = ::laterUpdate,
                    onSubscriptionShareClick = { url ->
                        platformBridge.shareText("Subscription", ConfigShareService.subscriptionQrText(url))
                    },
                    onSubscriptionRefreshClick = { url ->
                        dependencies.homeViewModel.refreshSubscription(url) { updatedCount ->
                            reloadLocationsAfterImport {
                                dependencies.homeViewModel.restartVpnIfRunning()
                                platformBridge.showMessage(
                                    if (updatedCount > 0) "Subscription updated" else "Subscription not updated"
                                )
                            }
                        }
                    },
                    onSocksProxySettingsSaved = { username, password, port ->
                        dependencies.vpnManager.updateSocksProxySettings(username, password, port)
                        if (homeState.isVpnConnected) {
                            dependencies.homeViewModel.restartVpnIfRunning()
                        }
                    },
                    onSocksProxyPasswordRegenerated = {
                        dependencies.vpnManager.regenerateSocksProxyPassword()
                        if (homeState.isVpnConnected) {
                            dependencies.homeViewModel.restartVpnIfRunning()
                        }
                    }
                )
            }

            updateOffer?.let { info ->
                ApplicationUpdateOfferSheet(
                    info = info,
                    downloadProgress = updateDownloadProgress,
                    onLater = { laterUpdate(info) },
                    onDownload = { downloadUpdate(info) }
                )
            }
        }
    }
}

private fun iosSubscriptionItems(items: List<LocationItem>): List<SubscriptionShareItem> {
    return items
        .mapNotNull { item ->
            val url = item.subscriptionUrl
                ?.trim()
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@mapNotNull null
            url to item
        }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key }
        .map { (url, locations) ->
            val metadata = locations.firstNotNullOfOrNull { it.metadata?.subscription }
            SubscriptionShareItem(
                url = url,
                name = metadata?.name?.takeIf { it.isNotBlank() }
                    ?: locations.first().fullName,
                updateIntervalHours = metadata?.updateIntervalHours,
                lastRefreshAtEpochMs = metadata?.lastRefreshAtEpochMs,
                locationCount = locations.size
            )
        }
}
