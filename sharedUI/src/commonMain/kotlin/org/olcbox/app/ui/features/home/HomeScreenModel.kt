package org.olcbox.app.ui.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationImportResult
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus

class HomeScreenViewModel(
    private val vpnManager: VpnManager,
    private val locationsRepository: LocationsRepository,
    private val configImporter: ConfigImporter,
    private val logExporter: LogExporter
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeScreenState(
            isVpnConnected = false,
            isVpnLoading = false,
            selectedLocation = null,
            configData = LocationConfig(),
            shouldShowConfigInvalidReminder = false,
            canStartVpn = false,
            startBlockedReason = "Add a location first"
        )
    )
    private val subscriptionRefreshWake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val state get() = _state.asStateFlow()
    val logs get() = vpnManager.logs

    init {
        loadCurrentConfig()
        startSubscriptionAutoRefresh()

        viewModelScope.launch {
            locationsRepository.changes
                .drop(1)
                .collect {
                    loadCurrentConfigNow()
                    subscriptionRefreshWake.tryEmit(Unit)
                }
        }

        viewModelScope.launch {
            var wasConnected = false
            vpnManager.status.collect { status ->
                _state.update {
                    when (status) {
                        VpnStatus.Connected -> it.copy(isVpnConnected = true, isVpnLoading = false)
                        VpnStatus.Connecting -> it.copy(isVpnConnected = false, isVpnLoading = true)
                        VpnStatus.Reconnecting -> it.copy(isVpnConnected = true, isVpnLoading = true)
                        VpnStatus.Stopping -> it.copy(isVpnConnected = false, isVpnLoading = false)
                        VpnStatus.Disconnected -> it.copy(isVpnConnected = false, isVpnLoading = false)
                        is VpnStatus.Error -> it.copy(isVpnConnected = false, isVpnLoading = false)
                    }
                }

                // Tunnel just came up: immediately pull the freshest rooms through it,
                // so the session begins with current rooms (and tops up if we connected
                // on an about-to-expire one). The fetch rides the live tunnel's SOCKS.
                val nowConnected = status is VpnStatus.Connected
                if (nowConnected && !wasConnected) {
                    viewModelScope.launch {
                        runCatching {
                            locationsRepository.refreshSubscriptions(
                                subscriptionProxy = vpnManager.subscriptionFetchProxy()
                            )
                        }
                        loadCurrentConfigNow()
                    }
                }
                wasConnected = nowConnected
            }
        }
    }

    fun loadCurrentConfig(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            loadCurrentConfigNow()
            onComplete()
        }
    }

    private suspend fun loadCurrentConfigNow() {
        val active = locationsRepository.getActiveLocation()
        if (active == null) {
            _state.update {
                it.copy(
                    selectedLocation = null,
                    configData = LocationConfig(),
                    canStartVpn = false,
                    startBlockedReason = "Add a location first"
                )
            }
            return
        }

        val normalized = active.location
        val locationItem = LocationItem(
            storageId = active.storageId,
            fullName = normalized.displayName(),
            config = normalized,
            subscriptionUrl = active.subscriptionUrl,
            metadata = active.metadata
        )

        _state.update {
            it.copy(
                configData = normalized,
                selectedLocation = locationItem,
                canStartVpn = normalized.isComplete(),
                startBlockedReason = if (normalized.isComplete()) null else "Complete active location first"
            )
        }
    }

    suspend fun performPing(): Long? {
        return vpnManager.ping(_state.value.configData)
    }

    suspend fun performPingFor(config: LocationConfig): Long? {
        return vpnManager.ping(config)
    }

    suspend fun checkConnectionFor(config: LocationConfig): Long? {
        return vpnManager.checkConnection(config)
    }

    fun startVpnContinuation() {
        _state.update { it.copy(isVpnLoading = true) }
    }

    fun ToggleVpn() {
        val status = vpnManager.status.value
        if (_state.value.isVpnLoading ||
            status is VpnStatus.Connecting ||
            status is VpnStatus.Reconnecting
        ) {
            viewModelScope.launch {
                vpnManager.stopVpn()
                _state.update { it.copy(isVpnConnected = false, isVpnLoading = false) }
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isVpnLoading = true) }
            try {
                if (_state.value.isVpnConnected || vpnManager.status.value is VpnStatus.Connected) {
                    // Pull the freshest rooms through the still-live tunnel before we
                    // tear it down, so the next start begins current. Bounded so Stop
                    // never hangs if the tunnel is already dying.
                    withTimeoutOrNull(PRE_DISCONNECT_REFRESH_TIMEOUT_MS) {
                        runCatching {
                            locationsRepository.refreshSubscriptions(
                                subscriptionProxy = vpnManager.subscriptionFetchProxy()
                            )
                        }
                    }
                    vpnManager.stopVpn()
                } else {
                    val active = locationsRepository.getActiveLocation()
                    if (active == null || !active.location.isComplete()) {
                        _state.update {
                            it.copy(
                                isVpnLoading = false,
                                canStartVpn = false,
                                startBlockedReason = "Add a valid location first"
                            )
                        }
                        return@launch
                    }
                    vpnManager.startVpn()
                }
            } catch (e: Exception) {
                _state.update { it.copy(isVpnLoading = false) }
            }
        }
    }

    fun restartVpnIfRunning() {
        when (vpnManager.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting -> viewModelScope.launch {
                _state.update { it.copy(isVpnLoading = true) }
                val active = locationsRepository.getActiveLocation()
                if (active == null || !active.location.isComplete()) {
                    vpnManager.stopVpn()
                    loadCurrentConfigNow()
                    _state.update {
                        it.copy(
                            isVpnConnected = false,
                            isVpnLoading = false,
                            canStartVpn = false,
                            startBlockedReason = "Add a valid location first"
                        )
                    }
                } else {
                    vpnManager.startVpn()
                }
            }

            VpnStatus.Disconnected,
            VpnStatus.Stopping,
            is VpnStatus.Error -> Unit
        }
    }
    private fun updateLocationConfig(block: (LocationConfig) -> LocationConfig) {
        _state.update { it.copy(configData = block(it.configData)) }
    }
    fun onCopyFullConfigClicked() {
        viewModelScope.launch {
            configImporter.copyToClipboard(locationsRepository.exportBundle())
        }
    }

    fun suggestedLogsFileName(): String = "olcbox-logs.txt"

    fun onSaveLogsToFile(
        target: Any,
        onSaved: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val content = buildLogsExport(logs.value)
            logExporter.writeLogs(target, content)
                .onSuccess { savedPath ->
                    onSaved(
                        if (savedPath.isBlank() || savedPath == "Logs saved") {
                            "Logs saved"
                        } else {
                            "Logs saved to $savedPath"
                        }
                    )
                }
                .onFailure { error ->
                    onError(error.message ?: "Failed to save logs")
                }
        }
    }

    fun onShareLogs(
        onShared: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val content = buildLogsExport(logs.value)
            logExporter.shareLogs(content)
                .onSuccess { message -> onShared(message) }
                .onFailure { error -> onError(error.message ?: "Failed to share logs") }
        }
    }

    fun onPasteFromClipboard(
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        configImporter.getFromClipboard()?.let { text ->
            onImportFullConfig(
                rawText = text,
                onComplete = onComplete,
                onError = onError
            )
        } ?: onError("No clipboard data found")
    }

    fun readImportTextFromClipboard(
        onText: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        configImporter.getFromClipboard()
            ?.takeIf { it.isNotBlank() }
            ?.let(onText)
            ?: onError("No clipboard data found")
    }

    fun onFileSelected(
        fileSource: Any,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val text = configImporter.readTextFromSource(fileSource)
            if (text == null) {
                onError("Could not read config file")
            } else {
                onImportFullConfig(
                    rawText = text,
                    onComplete = onComplete,
                    onError = onError
                )
            }
        }
    }

    fun onImportFullConfig(
        rawText: String,
        subscriptionRefreshIntervalMs: Long? = null,
        allowInsecureSubscriptionRequests: Boolean = false,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (rawText.isBlank()) {
            onError("No config text found")
            return
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    locationsRepository.importTextDetailed(
                        text = rawText,
                        subscriptionProxy = vpnManager.subscriptionFetchProxy(),
                        allowInsecureRequests = allowInsecureSubscriptionRequests
                    )
                }
                when (result) {
                    is LocationImportResult.Success -> {
                        if (result.subscriptionUrl != null && subscriptionRefreshIntervalMs != null) {
                            locationsRepository.setSubscriptionUpdateInterval(
                                result.subscriptionUrl,
                                subscriptionRefreshIntervalMs
                            )
                        }
                        loadCurrentConfigNow()
                        onComplete()
                    }
                    is LocationImportResult.Failure -> {
                        onError(result.message)
                    }
                }
            } catch (e: Exception) {
                val message = e.message ?: "Import failed"
                onError(message)
            }
        }
    }

    fun setSubscriptionRefreshInterval(
        subscriptionUrl: String,
        intervalMs: Long?,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            locationsRepository.setSubscriptionUpdateInterval(subscriptionUrl, intervalMs)
            onComplete()
        }
    }

    fun deleteSubscription(
        subscriptionUrl: String,
        onComplete: (removedLocations: Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val removedLocations = locationsRepository.deleteSubscription(subscriptionUrl)
            loadCurrentConfigNow()
            onComplete(removedLocations)
        }
    }

    fun refreshSubscriptions(
        onComplete: (updatedCount: Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val updatedCount = locationsRepository.refreshSubscriptions(
                subscriptionProxy = vpnManager.subscriptionFetchProxy()
            )
            loadCurrentConfigNow()
            onComplete(updatedCount)
        }
    }

    fun refreshSubscription(
        subscriptionUrl: String,
        onComplete: (updatedCount: Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val updatedCount = locationsRepository.refreshSubscription(
                subscriptionUrl = subscriptionUrl,
                subscriptionProxy = vpnManager.subscriptionFetchProxy()
            )
            loadCurrentConfigNow()
            onComplete(updatedCount)
        }
    }

    private fun startSubscriptionAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                try {
                    refreshDueSubscriptionsIfNeeded()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Keep the scheduler alive; failed subscriptions retain their last good config.
                }
                val nextRefreshAt = locationsRepository.nextSubscriptionRefreshAtEpochMs()
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val waitMs = nextRefreshAt
                    ?.minus(now)
                    ?.coerceAtLeast(MIN_SUBSCRIPTION_REFRESH_WAIT_MS)
                    ?: IDLE_SUBSCRIPTION_REFRESH_WAIT_MS
                withTimeoutOrNull(waitMs) {
                    subscriptionRefreshWake.first()
                }
            }
        }
    }

    fun onForeground() {
        subscriptionRefreshWake.tryEmit(Unit)
    }

    private suspend fun refreshDueSubscriptionsIfNeeded() {
        val updatedCount = withContext(Dispatchers.IO) {
            locationsRepository.refreshDueSubscriptions(
                subscriptionProxy = vpnManager.subscriptionFetchProxy()
            )
        }
        if (updatedCount > 0) {
            loadCurrentConfigNow()
        }
    }

    private fun buildLogsExport(logs: List<String>): String {
        return buildString {
            appendLine("Olcbox application logs")
            appendLine("Build: ${org.olcbox.app.CurrentAppInfo.diagnosticVersion}")
            appendLine("Entries: ${logs.size}")
            appendLine()
            logs.forEachIndexed { index, line ->
                appendLine("${index + 1}. $line")
            }
        }
    }
}

data class HomeScreenState(
    val isVpnConnected: Boolean,
    val isVpnLoading: Boolean = false,
    val selectedLocation: LocationItem?,
    val configData: LocationConfig,
    val shouldShowConfigInvalidReminder: Boolean,
    val canStartVpn: Boolean,
    val startBlockedReason: String?
)

private const val MIN_SUBSCRIPTION_REFRESH_WAIT_MS = 1_000L
private const val IDLE_SUBSCRIPTION_REFRESH_WAIT_MS = 24L * 60L * 60L * 1_000L
private const val PRE_DISCONNECT_REFRESH_TIMEOUT_MS = 4_000L
