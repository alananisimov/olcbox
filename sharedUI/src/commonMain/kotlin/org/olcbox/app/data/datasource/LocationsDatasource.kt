package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.parseSubscriptionRefreshIntervalMs
import org.olcbox.app.data.repository.LocationImportFailureKind
import org.olcbox.app.data.repository.LocationImportResult
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy

interface LocationsDataSource {
    suspend fun loadLocationBundle(): LocationBundleV4?
    suspend fun saveLocationBundle(bundle: LocationBundleV4)
    suspend fun loadLegacyLocations(): List<Pair<String, String>>
    suspend fun loadLegacyActiveLocationId(): String?
    suspend fun loadDeviceIdentity(): String? = null
    suspend fun saveDeviceIdentity(value: String) = Unit
}

internal expect fun createProxyHttpClient(
    subscriptionProxy: SubscriptionFetchProxy? = null,
    connectTimeoutMs: Long = 3_000,
    requestTimeoutMs: Long = 8_000,
    socketTimeoutMs: Long = 8_000,
    allowInsecureRequests: Boolean = false
): HttpClient

internal expect suspend fun <T> withProxyAuthentication(
    subscriptionProxy: SubscriptionFetchProxy?,
    block: suspend () -> T
): T

class LocationsRepositoryImpl(
    private val dataSource: LocationsDataSource,
    private val httpClient: HttpClient = createProxyHttpClient(),
    private val deviceIdentityProvider: DeviceIdentityProvider = PersistentDeviceIdentityProvider(dataSource),
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val subscriptionHttpClientFactory: (SubscriptionFetchProxy?, Boolean) -> HttpClient =
        { proxy, allowInsecureRequests ->
            createProxyHttpClient(
                subscriptionProxy = proxy,
                allowInsecureRequests = allowInsecureRequests
            )
        }
) : LocationsRepository {
    private data class ImportSource(
        val content: String,
        val subscriptionUrl: String? = null,
        val updateIntervalMs: Long? = null,
        val requestMode: SubscriptionRequestMode = SubscriptionRequestMode.Identity,
        val allowInsecureRequests: Boolean = false
    )

    private data class DownloadedSubscription(
        val content: String,
        val updateIntervalMs: Long?
    )

    private data class ParsedImport(
        val bundle: LocationBundleV4,
        val mode: ImportMode
    )

    private data class ResolvedImport(
        val source: ImportSource,
        val parsed: ParsedImport
    )

    private sealed interface ResolvedImportResult {
        data class Success(val value: ResolvedImport) : ResolvedImportResult

        data class Failure(
            val kind: LocationImportFailureKind,
            val message: String
        ) : ResolvedImportResult
    }

    private sealed interface ImportSourceResult {
        data class Success(val value: ImportSource) : ImportSourceResult

        data class Failure(
            val kind: LocationImportFailureKind,
            val message: String
        ) : ImportSourceResult
    }

    private sealed interface DownloadSubscriptionResult {
        data class Success(val value: DownloadedSubscription) : DownloadSubscriptionResult

        data class Failure(
            val kind: LocationImportFailureKind,
            val message: String
        ) : DownloadSubscriptionResult
    }

    private data class ParsedOlcRtcUri(
        val location: LocationConfig,
        val mimo: String? = null
    )

    private enum class ImportMode {
        Additive,
        Restore
    }

    private enum class SubscriptionRequestMode {
        Identity,
        Compatibility
    }

    private val mutationMutex = Mutex()
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    override suspend fun getBundle(): LocationBundleV4 {
        return mutationMutex.withLock {
            getBundleUnlocked()
        }
    }

    private suspend fun getBundleUnlocked(): LocationBundleV4 {
        val stored = dataSource.loadLocationBundle()?.normalized()
        if (stored != null && stored.locations.isNotEmpty()) return stored

        val legacy = migrateLegacyBundle()
        if (legacy.locations.isNotEmpty()) {
            dataSource.saveLocationBundle(legacy)
        }
        return legacy
    }

    override suspend fun saveBundle(bundle: LocationBundleV4) {
        mutationMutex.withLock {
            saveBundleUnlocked(bundle)
        }
    }

    private suspend fun saveBundleUnlocked(bundle: LocationBundleV4) {
        dataSource.saveLocationBundle(bundle.normalized())
        _changes.value = _changes.value + 1
    }

    override suspend fun exportBundle(): String {
        return json.encodeToString(LocationBundleV4.serializer(), getBundle())
    }

    override suspend fun importTextDetailed(
        text: String,
        subscriptionProxy: SubscriptionFetchProxy?,
        allowInsecureRequests: Boolean
    ): LocationImportResult {
        val resolved = when (
            val result = resolveParsedImportDetailed(
                text = text,
                subscriptionProxy = subscriptionProxy,
                allowInsecureRequests = allowInsecureRequests
            )
        ) {
            is ResolvedImportResult.Success -> result.value
            is ResolvedImportResult.Failure -> {
                return LocationImportResult.Failure(result.kind, result.message)
            }
        }

        val importedBundle = if (resolved.source.subscriptionUrl != null) {
            val importedAt = nowEpochMs()
            resolved.parsed.bundle.copy(
                locations = resolved.parsed.bundle.locations.map { entry ->
                    val subscription = entry.metadata?.subscription
                    entry.copy(
                        metadata = entry.metadata.withSubscriptionRefreshState(
                            updateIntervalMs = subscription?.effectiveUpdateIntervalMs()
                                ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_MS,
                            manualUpdateIntervalMs = subscription?.manualUpdateIntervalMs,
                            lastRefreshAttemptAtEpochMs = importedAt,
                            lastRefreshAtEpochMs = importedAt,
                            consecutiveRefreshFailures = 0
                        )
                    ).normalized()
                }
            ).normalized()
        } else {
            resolved.parsed.bundle
        }

        mutationMutex.withLock {
            val current = getBundleUnlocked()
            val subscriptionUrl = resolved.source.subscriptionUrl
            val merged = if (
                subscriptionUrl != null &&
                current.locations.any { it.subscriptionUrl?.trim() == subscriptionUrl.trim() }
            ) {
                mergeImportedSubscription(
                    current = current,
                    imported = importedBundle.normalized(),
                    subscriptionUrl = subscriptionUrl
                )
            } else {
                mergeImportedBundle(
                    current = current,
                    imported = importedBundle.normalized(),
                    replaceMatchingStorageIds = resolved.parsed.mode == ImportMode.Restore
                )
            }
            saveBundleUnlocked(merged)
        }
        return LocationImportResult.Success(
            importedLocations = importedBundle.locations.size,
            subscriptionUrl = resolved.source.subscriptionUrl
        )
    }

    override suspend fun refreshSubscriptions(subscriptionProxy: SubscriptionFetchProxy?): Int {
        return mutationMutex.withLock {
            refreshSubscriptionsUnlocked(
                onlyUrls = null,
                subscriptionProxy = subscriptionProxy
            )
        }
    }

    override suspend fun refreshSubscription(
        subscriptionUrl: String,
        subscriptionProxy: SubscriptionFetchProxy?
    ): Int {
        val normalizedUrl = subscriptionUrl.trim()
        if (normalizedUrl.isBlank()) return 0
        return mutationMutex.withLock {
            refreshSubscriptionsUnlocked(
                onlyUrls = setOf(normalizedUrl),
                subscriptionProxy = subscriptionProxy
            )
        }
    }

    private suspend fun refreshSubscriptionsUnlocked(
        onlyUrls: Set<String>?,
        subscriptionProxy: SubscriptionFetchProxy?
    ): Int {
        val bundle = getBundleUnlocked()
        if (bundle.locations.isEmpty()) return 0

        val groupedByUrl = bundle.locations
            .mapNotNull { entry -> entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }?.let { it to entry } }
            .groupBy({ it.first }, { it.second })
            .filterKeys { url -> onlyUrls == null || url in onlyUrls }
        if (groupedByUrl.isEmpty()) return 0

        val targetUrls = groupedByUrl.keys
        val refreshedLocations = bundle.locations
            .filter { entry ->
                val url = entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
                url == null || (onlyUrls != null && url !in targetUrls)
            }
            .toMutableList()
        val usedStorageIds = refreshedLocations.mapTo(mutableSetOf()) { it.storageId }
        val activeBefore = bundle.activeLocationId
        var activeAfter = activeBefore
        var successfulRefreshes = 0
        var attemptedRefreshes = 0

        fun preservePreviousEntries(entries: List<LocationEntry>, failedAtEpochMs: Long? = null) {
            entries.forEach { entry ->
                if (usedStorageIds.add(entry.storageId)) {
                    refreshedLocations += if (failedAtEpochMs == null) {
                        entry
                    } else {
                        entry.copy(
                            metadata = entry.metadata.withSubscriptionRefreshFailure(failedAtEpochMs)
                        ).normalized()
                    }
                }
            }
        }

        groupedByUrl.forEach { (url, previousEntries) ->
            attemptedRefreshes += 1
            val refreshTimestamp = nowEpochMs()
            val previousInterval = previousEntries.subscriptionUpdateIntervalMs()
            val allowInsecureRequests = previousEntries.any {
                it.metadata?.subscription?.allowInsecureRequests == true
            } ||
                url.startsWith("http://", ignoreCase = true)
            val resolved = resolveParsedImport(
                text = url,
                fallbackSubscriptionInterval = previousInterval,
                subscriptionProxy = subscriptionProxy,
                allowInsecureRequests = allowInsecureRequests
            ) ?: run {
                preservePreviousEntries(previousEntries, refreshTimestamp)
                return@forEach
            }
            val source = resolved.source
            val refreshed = resolved.parsed.bundle.locations
            val updateInterval = source.updateIntervalMs
                ?: refreshed.firstNotNullOfOrNull {
                    it.metadata?.subscription?.updateIntervalMs
                }
                ?: previousInterval
                ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_MS
            val manualInterval = previousEntries.firstNotNullOfOrNull {
                it.metadata?.subscription?.manualUpdateIntervalMs
            }
            if (refreshed.isEmpty()) {
                preservePreviousEntries(previousEntries, refreshTimestamp)
                return@forEach
            }

            val reusedBySignature = previousEntries
                .groupBy { subscriptionSignature(it.location) }
                .mapValues { (_, entries) -> entries.toMutableList() }

            val reassigned = refreshed.mapIndexed { index, entry ->
                val signature = subscriptionSignature(entry.location)
                val reusedPool = reusedBySignature[signature]
                val reusedEntry = if (reusedPool.isNullOrEmpty()) null else reusedPool.removeAt(0)
                val storageId = reusedEntry?.storageId ?: uniqueStorageId(
                    base = "imported_${entry.location.storageSlug().ifBlank { "location_${index + 1}" }}",
                    used = usedStorageIds
                )
                if (activeBefore == reusedEntry?.storageId) {
                    activeAfter = storageId
                }
                entry.copy(
                    storageId = storageId,
                    subscriptionUrl = url,
                    dnsServer = entry.location.dnsServer
                        .ifBlank { reusedEntry?.location?.dnsServer.orEmpty() }
                        .takeIf { it.isNotBlank() },
                    metadata = entry.metadata.withSubscriptionRefreshState(
                        updateIntervalMs = updateInterval,
                        manualUpdateIntervalMs = manualInterval,
                        lastRefreshAttemptAtEpochMs = refreshTimestamp,
                        lastRefreshAtEpochMs = refreshTimestamp,
                        consecutiveRefreshFailures = 0
                    )
                ).normalized()
            }

            if (activeBefore != null &&
                activeAfter == activeBefore &&
                previousEntries.any { it.storageId == activeBefore }
            ) {
                activeAfter = reassigned.firstOrNull()?.storageId
            }
            refreshedLocations += reassigned
            successfulRefreshes += 1
        }

        if (attemptedRefreshes == 0) return 0

        saveBundleUnlocked(
            bundle.copy(
                activeLocationId = activeAfter,
                locations = refreshedLocations
            )
        )
        return successfulRefreshes
    }

    override suspend fun refreshDueSubscriptions(subscriptionProxy: SubscriptionFetchProxy?): Int {
        return mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            val now = nowEpochMs()
            val dueUrls = bundle.locations
                .mapNotNull { entry ->
                    val url = entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val nextRefreshAt = entry.metadata?.subscription?.nextRefreshAtEpochMs() ?: 0L
                    url.takeIf { nextRefreshAt <= now }
                }
                .toSet()

            if (dueUrls.isEmpty()) {
                0
            } else {
                refreshSubscriptionsUnlocked(
                    onlyUrls = dueUrls,
                    subscriptionProxy = subscriptionProxy
                )
            }
        }
    }

    override suspend fun nextSubscriptionRefreshAtEpochMs(): Long? {
        return mutationMutex.withLock {
            getBundleUnlocked().locations
                .mapNotNull { entry ->
                    entry.subscriptionUrl?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    entry.metadata?.subscription?.nextRefreshAtEpochMs() ?: 0L
                }
                .minOrNull()
        }
    }

    override suspend fun setSubscriptionUpdateInterval(subscriptionUrl: String, intervalMs: Long?) {
        val normalizedUrl = subscriptionUrl.trim()
        if (normalizedUrl.isBlank()) return

        mutationMutex.withLock {
            val interval = intervalMs?.coerceIn(
                SubscriptionMetadata.MIN_UPDATE_INTERVAL_MS,
                SubscriptionMetadata.MAX_UPDATE_INTERVAL_MS
            )
            val bundle = getBundleUnlocked()
            val updated = bundle.locations.map { entry ->
                if (entry.subscriptionUrl?.trim() != normalizedUrl) {
                    entry
                } else {
                    entry.copy(
                        metadata = entry.metadata.withManualSubscriptionInterval(interval)
                    ).normalized()
                }
            }

            saveBundleUnlocked(bundle.copy(locations = updated))
        }
    }

    override suspend fun deleteSubscription(subscriptionUrl: String): Int {
        val normalizedUrl = subscriptionUrl.trim()
        if (normalizedUrl.isBlank()) return 0

        return mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            val removed = bundle.locations.filter {
                it.subscriptionUrl?.trim() == normalizedUrl
            }
            if (removed.isEmpty()) return@withLock 0

            val remaining = bundle.locations.filterNot {
                it.subscriptionUrl?.trim() == normalizedUrl
            }
            val activeLocationId = bundle.activeLocationId
                ?.takeIf { activeId -> remaining.any { it.storageId == activeId } }
                ?: remaining.firstOrNull()?.storageId

            saveBundleUnlocked(
                bundle.copy(
                    activeLocationId = activeLocationId,
                    locations = remaining
                )
            )
            removed.size
        }
    }

    override suspend fun saveLocation(storageId: String, location: LocationConfig) {
        mutationMutex.withLock {
            val normalizedId = storageId.ifBlank { location.storageSlug() }
            val bundle = getBundleUnlocked()
            val current = bundle.locations.firstOrNull { it.storageId == normalizedId }
            val entry = LocationEntry.from(
                storageId = normalizedId,
                location = location,
                subscriptionUrl = current?.subscriptionUrl,
                metadata = current?.metadata
            )
            val locations = bundle.locations
                .filterNot { it.storageId == entry.storageId } + entry

            saveBundleUnlocked(
                bundle.copy(
                    activeLocationId = entry.storageId,
                    locations = locations
                )
            )
        }
    }

    override suspend fun loadLocation(storageId: String): LocationConfig? {
        return mutationMutex.withLock {
            getBundleUnlocked().locations.firstOrNull { it.storageId == storageId }?.location
        }
    }

    override suspend fun deleteLocation(storageId: String) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            saveBundleUnlocked(
                bundle.copy(
                    activeLocationId = bundle.activeLocationId?.takeUnless { it == storageId },
                    locations = bundle.locations.filterNot { it.storageId == storageId }
                )
            )
        }
    }

    override suspend fun getAllLocations(): List<LocationEntry> {
        return mutationMutex.withLock {
            getBundleUnlocked().locations
        }
    }

    override suspend fun getActiveLocationId(): String? {
        return mutationMutex.withLock {
            getBundleUnlocked().activeLocationId
        }
    }

    override suspend fun setActiveLocationId(storageId: String?) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            val nextActive = storageId?.takeIf { id -> bundle.locations.any { it.storageId == id } }
            saveBundleUnlocked(bundle.copy(activeLocationId = nextActive))
        }
    }

    override suspend fun getActiveLocation(): LocationEntry? {
        return mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            bundle.locations.firstOrNull { it.storageId == bundle.activeLocationId }
        }
    }

    override suspend fun getDeviceIdentity(): String {
        return deviceIdentityProvider.hwid()
    }

    private suspend fun resolveParsedImport(
        text: String,
        fallbackSubscriptionInterval: Long? = null,
        subscriptionProxy: SubscriptionFetchProxy? = null,
        allowInsecureRequests: Boolean = false
    ): ResolvedImport? {
        return when (
            val result = resolveParsedImportDetailed(
                text = text,
                fallbackSubscriptionInterval = fallbackSubscriptionInterval,
                subscriptionProxy = subscriptionProxy,
                allowInsecureRequests = allowInsecureRequests
            )
        ) {
            is ResolvedImportResult.Success -> result.value
            is ResolvedImportResult.Failure -> null
        }
    }

    private suspend fun resolveParsedImportDetailed(
        text: String,
        fallbackSubscriptionInterval: Long? = null,
        subscriptionProxy: SubscriptionFetchProxy? = null,
        allowInsecureRequests: Boolean = false
    ): ResolvedImportResult {
        val input = text.normalizedImportText()
        if (input.isBlank()) {
            return ResolvedImportResult.Failure(
                LocationImportFailureKind.EmptyInput,
                "Enter a subscription URL, olcrtc URI, or configuration text"
            )
        }
        if (input.looksLikeUnsupportedUrl()) {
            return ResolvedImportResult.Failure(
                LocationImportFailureKind.InvalidUrl,
                "Only HTTP, HTTPS, and olcrtc URIs are supported"
            )
        }
        if (input.isHttpUrl() && !input.isValidHttpUrl()) {
            return ResolvedImportResult.Failure(
                LocationImportFailureKind.InvalidUrl,
                "Enter a valid HTTP or HTTPS subscription URL"
            )
        }
        if (input.startsWith("http://", ignoreCase = true) && !allowInsecureRequests) {
            return ResolvedImportResult.Failure(
                LocationImportFailureKind.InvalidUrl,
                "Enable Allow insecure requests to import an HTTP subscription"
            )
        }
        var sourceResult = resolveImportSourceDetailed(
            text = input,
            requestMode = SubscriptionRequestMode.Identity,
            subscriptionProxy = subscriptionProxy,
            allowInsecureRequests = allowInsecureRequests
        )
        var source = (sourceResult as? ImportSourceResult.Success)?.value
        var lastFailure = sourceResult as? ImportSourceResult.Failure

        if (source == null && input.isHttpUrl()) {
            sourceResult = resolveImportSourceDetailed(
                text = input,
                requestMode = SubscriptionRequestMode.Compatibility,
                subscriptionProxy = subscriptionProxy,
                allowInsecureRequests = allowInsecureRequests
            )
            source = (sourceResult as? ImportSourceResult.Success)?.value
            lastFailure = sourceResult as? ImportSourceResult.Failure ?: lastFailure
        }

        if (source == null) {
            return lastFailure?.toResolvedFailure()
                ?: ResolvedImportResult.Failure(
                    LocationImportFailureKind.Network,
                    "Could not download the subscription"
                )
        }

        var parsed = parseImportSource(source, fallbackSubscriptionInterval)
        if (parsed == null && input.isHttpUrl() && source.requestMode != SubscriptionRequestMode.Compatibility) {
            when (
                val fallbackSource = resolveImportSourceDetailed(
                    text = input,
                    requestMode = SubscriptionRequestMode.Compatibility,
                    subscriptionProxy = subscriptionProxy,
                    allowInsecureRequests = allowInsecureRequests
                )
            ) {
                is ImportSourceResult.Success -> {
                    source = fallbackSource.value
                    parsed = parseImportSource(source, fallbackSubscriptionInterval)
                }
                is ImportSourceResult.Failure -> lastFailure = fallbackSource
            }
        }

        if (parsed == null) {
            return lastFailure?.toResolvedFailure()
                ?: ResolvedImportResult.Failure(
                    LocationImportFailureKind.UnsupportedFormat,
                    if (input.isHttpUrl()) {
                        "The server responded, but the body is not a supported Olcbox configuration"
                    } else {
                        "The text is not a supported Olcbox configuration"
                    }
                )
        }

        return ResolvedImportResult.Success(ResolvedImport(source, parsed))
    }

    private fun parseImportSource(
        source: ImportSource,
        fallbackSubscriptionInterval: Long? = null
    ): ParsedImport? {
        val parsed = parseImport(
            source.content.normalizedImportText(),
            source.subscriptionUrl,
            source.updateIntervalMs
        ) ?: return null
        if (source.subscriptionUrl == null) return parsed

        val bodyInterval = parsed.bundle.locations.firstNotNullOfOrNull {
            it.metadata?.subscription?.updateIntervalMs
        }
        val resolvedInterval = source.updateIntervalMs
            ?: bodyInterval
            ?: fallbackSubscriptionInterval
            ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_MS
        return parsed.copy(
            bundle = parsed.bundle.copy(
                locations = parsed.bundle.locations.map { entry ->
                    entry.copy(
                        metadata = entry.metadata
                            .withSubscriptionInterval(resolvedInterval)
                            .withSubscriptionRequestSecurity(source.allowInsecureRequests)
                    ).normalized()
                }
            ).normalized()
        )
    }

    private suspend fun resolveImportSourceDetailed(
        text: String,
        requestMode: SubscriptionRequestMode,
        subscriptionProxy: SubscriptionFetchProxy?,
        allowInsecureRequests: Boolean
    ): ImportSourceResult {
        if (text.isBlank()) {
            return ImportSourceResult.Failure(
                LocationImportFailureKind.EmptyInput,
                "No configuration text found"
            )
        }

        if (!text.isHttpUrl()) {
            return ImportSourceResult.Success(
                ImportSource(content = text.normalizedImportText())
            )
        }

        return when (
            val downloaded = downloadTextFromUrl(
                url = text,
                requestMode = requestMode,
                subscriptionProxy = subscriptionProxy,
                allowInsecureRequests = allowInsecureRequests
            )
        ) {
            is DownloadSubscriptionResult.Success -> {
                ImportSourceResult.Success(
                    ImportSource(
                        content = downloaded.value.content.normalizedImportText(),
                        subscriptionUrl = text.trim(),
                        updateIntervalMs = downloaded.value.updateIntervalMs,
                        requestMode = requestMode,
                        allowInsecureRequests = allowInsecureRequests
                    )
                )
            }
            is DownloadSubscriptionResult.Failure -> {
                ImportSourceResult.Failure(downloaded.kind, downloaded.message)
            }
        }
    }

    private suspend fun downloadTextFromUrl(
        url: String,
        requestMode: SubscriptionRequestMode,
        subscriptionProxy: SubscriptionFetchProxy?,
        allowInsecureRequests: Boolean
    ): DownloadSubscriptionResult {
        val hwid = if (requestMode == SubscriptionRequestMode.Identity) {
            deviceIdentityProvider.hwid()
        } else {
            null
        }
        val usesDedicatedClient = subscriptionProxy != null || allowInsecureRequests
        val client = if (usesDedicatedClient) {
            subscriptionHttpClientFactory(subscriptionProxy, allowInsecureRequests)
        } else {
            httpClient
        }

        return try {
            withProxyAuthentication(subscriptionProxy) {
                val response = try {
                    client.get(url) {
                        headers {
                            append(
                                HttpHeaders.Accept,
                                "text/plain, text/markdown, application/octet-stream, */*"
                            )
                            if (requestMode == SubscriptionRequestMode.Identity) {
                                append(HttpHeaders.UserAgent, CurrentAppInfo.userAgent)
                                append("x-hwid", hwid.orEmpty())
                            } else {
                                append(
                                    HttpHeaders.UserAgent,
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
                                )
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    return@withProxyAuthentication error.toDownloadFailure()
                }

                if (response.status.value !in 200..299) {
                    return@withProxyAuthentication DownloadSubscriptionResult.Failure(
                        LocationImportFailureKind.Http,
                        "Subscription server returned HTTP ${response.status.value}"
                    )
                }

                val content = try {
                    response.bodyAsText()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    return@withProxyAuthentication error.toDownloadFailure()
                }
                if (content.isBlank()) {
                    return@withProxyAuthentication DownloadSubscriptionResult.Failure(
                        LocationImportFailureKind.EmptyResponse,
                        "Subscription server returned an empty response"
                    )
                }

                DownloadSubscriptionResult.Success(
                    DownloadedSubscription(
                        content = content,
                        updateIntervalMs = response.profileUpdateIntervalMs()
                    )
                )
            }
        } finally {
            if (usesDedicatedClient) {
                client.close()
            }
        }
    }

    private fun String.isHttpUrl(): Boolean {
        val value = trim().lowercase()
        return value.startsWith("http://") || value.startsWith("https://")
    }

    private fun String.isValidHttpUrl(): Boolean {
        val input = trim()
        if ('\n' in input || '\r' in input || input.any { it.isWhitespace() }) return false
        val authority = input.substringAfter("://", missingDelimiterValue = "")
            .takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isBlank()) return false
        return runCatching {
            val parsed = Url(input)
            parsed.host.isNotBlank() &&
                (parsed.protocol.name == "http" || parsed.protocol.name == "https")
        }.getOrDefault(false)
    }

    private fun String.looksLikeUnsupportedUrl(): Boolean {
        val value = trim()
        val startsWithScheme = URL_SCHEME.containsMatchIn(value)
        return startsWithScheme &&
                !value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true) &&
                !value.startsWith(OLCRTC_URI_PREFIX, ignoreCase = true)
    }

    private fun ImportSourceResult.Failure.toResolvedFailure(): ResolvedImportResult.Failure {
        return ResolvedImportResult.Failure(kind, message)
    }

    private fun Throwable.toDownloadFailure(): DownloadSubscriptionResult.Failure {
        val diagnostic = generateSequence(this) { it.cause }
            .joinToString(" ") { error ->
                "${error::class.simpleName.orEmpty()} ${error.message.orEmpty()}"
            }
            .lowercase()
        return when {
            listOf("certificate", "certpath", "ssl", "tls", "trust anchor", "hostname").any {
                it in diagnostic
            } -> DownloadSubscriptionResult.Failure(
                LocationImportFailureKind.Tls,
                "TLS certificate validation failed"
            )
            "timeout" in diagnostic || "timed out" in diagnostic -> {
                DownloadSubscriptionResult.Failure(
                    LocationImportFailureKind.Timeout,
                    "Subscription request timed out"
                )
            }
            else -> DownloadSubscriptionResult.Failure(
                LocationImportFailureKind.Network,
                "Could not connect to the subscription server"
            )
        }
    }

    private fun String.normalizedImportText(): String {
        return trim().removePrefix(UTF8_BOM).trim()
    }

    private suspend fun migrateLegacyBundle(): LocationBundleV4 {
        val legacy = dataSource.loadLegacyLocations().mapNotNull { (storageId, text) ->
            parseSingleLocation(text, storageId)
        }

        val active = dataSource.loadLegacyActiveLocationId()?.takeIf { id ->
            legacy.any { it.storageId == id }
        }

        return LocationBundleV4(
            activeLocationId = active,
            locations = legacy
        ).normalized()
    }

    private fun parseImport(
        text: String,
        subscriptionUrl: String? = null,
        updateIntervalMs: Long? = null
    ): ParsedImport? {
        parseOlcRtcText(text, subscriptionUrl, updateIntervalMs)?.let {
            return ParsedImport(it, ImportMode.Additive)
        }

        if (!text.startsWith("{") || !text.endsWith("}")) return null

        val root = runCatching {
            json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null

        parseBundle(root, subscriptionUrl, updateIntervalMs)?.let {
            return ParsedImport(it, ImportMode.Restore)
        }

        return parseSingleLocation(root, null, subscriptionUrl)?.let {
            ParsedImport(
                LocationBundleV4(
                    activeLocationId = it.storageId,
                    locations = listOf(
                        it.copy(
                            metadata = it.metadata.withSubscriptionInterval(updateIntervalMs)
                        ).normalized()
                    )
                ),
                ImportMode.Additive
            )
        }
    }

    private fun mergeImportedBundle(
        current: LocationBundleV4?,
        imported: LocationBundleV4,
        replaceMatchingStorageIds: Boolean
    ): LocationBundleV4 {
        val currentBundle = current?.normalized()
        if (currentBundle == null || currentBundle.locations.isEmpty()) {
            return imported
        }

        val currentStorageIds = currentBundle.locations.mapTo(mutableSetOf()) { it.storageId }
        val existingStorageIds = currentBundle.locations.mapTo(mutableSetOf()) { it.storageId }

        val importedByStorageId = if (replaceMatchingStorageIds) {
            imported.locations.associateBy { it.storageId }
        } else {
            emptyMap()
        }
        val replacedStorageIds = importedByStorageId.keys.intersect(currentStorageIds)

        val mergedLocations = currentBundle.locations
            .map { existing ->
                importedByStorageId[existing.storageId]?.also {
                    existingStorageIds.add(it.storageId)
                } ?: existing
            }
            .toMutableList()

        val importedIdMap = mutableMapOf<String, String>()

        imported.locations.forEach { entry ->
            if (replaceMatchingStorageIds && entry.storageId in replacedStorageIds) return@forEach

            val storageId = uniqueStorageId(entry.storageId, existingStorageIds)
            importedIdMap[entry.storageId] = storageId
            mergedLocations += entry.copy(storageId = storageId).normalized()
        }

        val importedActive = imported.activeLocationId
            ?.let { id -> importedIdMap[id] ?: id }
            ?.takeIf { id -> mergedLocations.any { it.storageId == id } }

        val active = importedActive
            ?: currentBundle.activeLocationId?.takeIf { id -> mergedLocations.any { it.storageId == id } }
            ?: mergedLocations.firstOrNull()?.storageId

        return currentBundle.copy(
            activeLocationId = active,
            locations = mergedLocations
        )
    }

    private fun mergeImportedSubscription(
        current: LocationBundleV4,
        imported: LocationBundleV4,
        subscriptionUrl: String
    ): LocationBundleV4 {
        val normalizedUrl = subscriptionUrl.trim()
        val currentBundle = current.normalized()
        val previousEntries = currentBundle.locations.filter {
            it.subscriptionUrl?.trim() == normalizedUrl
        }
        if (previousEntries.isEmpty()) {
            return mergeImportedBundle(
                current = currentBundle,
                imported = imported,
                replaceMatchingStorageIds = false
            )
        }

        val remainingEntries = currentBundle.locations.filterNot {
            it.subscriptionUrl?.trim() == normalizedUrl
        }
        val usedStorageIds = remainingEntries.mapTo(mutableSetOf()) { it.storageId }
        val previousBySignature = previousEntries
            .groupBy { subscriptionSignature(it.location) }
            .mapValues { (_, entries) -> entries.toMutableList() }
        val manualInterval = previousEntries.firstNotNullOfOrNull {
            it.metadata?.subscription?.manualUpdateIntervalMs
        }

        val updatedEntries = imported.locations.mapIndexed { index, entry ->
            val previousPool = previousBySignature[subscriptionSignature(entry.location)]
            val previousEntry = if (previousPool.isNullOrEmpty()) {
                null
            } else {
                previousPool.removeAt(0)
            }
            val storageId = previousEntry?.storageId ?: uniqueStorageId(
                base = entry.storageId.ifBlank {
                    "imported_${entry.location.storageSlug().ifBlank { "location_${index + 1}" }}"
                },
                used = usedStorageIds
            )
            usedStorageIds.add(storageId)

            entry.copy(
                storageId = storageId,
                subscriptionUrl = normalizedUrl,
                dnsServer = entry.location.dnsServer
                    .ifBlank { previousEntry?.location?.dnsServer.orEmpty() }
                    .takeIf { it.isNotBlank() },
                metadata = entry.metadata.withManualSubscriptionInterval(manualInterval)
            ).normalized()
        }

        val mergedLocations = remainingEntries + updatedEntries
        val previousIds = previousEntries.mapTo(mutableSetOf()) { it.storageId }
        val activeLocationId = currentBundle.activeLocationId
            ?.takeIf { activeId -> activeId !in previousIds || updatedEntries.any { it.storageId == activeId } }
            ?: updatedEntries.firstOrNull()?.storageId
            ?: remainingEntries.firstOrNull()?.storageId

        return currentBundle.copy(
            activeLocationId = activeLocationId,
            locations = mergedLocations
        )
    }

    private fun parseBundle(
        root: JsonObject,
        subscriptionUrl: String? = null,
        updateIntervalMs: Long? = null
    ): LocationBundleV4? {
        val locationsElement = root["locations"] ?: return null

        val locations = runCatching {
            locationsElement.jsonArray
        }.getOrNull()?.mapNotNull { element ->
            val item = element.jsonObjectOrNull() ?: return@mapNotNull null

            decodeLocationEntry(item, subscriptionUrl)?.let {
                return@mapNotNull it.copy(
                    metadata = it.metadata.withSubscriptionInterval(updateIntervalMs)
                ).normalized()
            }

            val storageId = item.string("storage_id")
                ?: item.string("storageId")
                ?: item.string("id")?.let { "imported_${it.storageSlug()}" }

            parseSingleLocation(item, storageId, subscriptionUrl)?.let { entry ->
                entry.copy(
                    metadata = entry.metadata.withSubscriptionInterval(updateIntervalMs)
                ).normalized()
            }
        } ?: return null

        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 3
        if (version < 3 && locations.isEmpty()) return null

        return LocationBundleV4(
            activeLocationId = root.string("active_location_id")
                ?: root.string("activeLocationId"),
            locations = locations
        )
    }

    private fun parseSingleLocation(
        text: String,
        fallbackStorageId: String?,
        subscriptionUrl: String? = null
    ): LocationEntry? {
        val root = runCatching {
            json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null

        parseBundle(root, subscriptionUrl)?.let { bundle ->
            return bundle.normalized().locations.firstOrNull()
        }

        return parseSingleLocation(root, fallbackStorageId, subscriptionUrl)
    }

    private fun parseSingleLocation(
        root: JsonObject,
        fallbackStorageId: String?,
        subscriptionUrl: String? = null
    ): LocationEntry? {
        decodeLocationEntry(root, subscriptionUrl)?.let { return it }

        val source = root["location"]?.jsonObjectOrNull()
            ?: root["hysteria"]?.jsonObjectOrNull()
            ?: root

        val provider = firstNotBlank(
            source.string("auth_provider"),
            source.string("authProvider"),
            source.string("bypass_provider"),
            source.string("bypassProvider"),
            source.string("provider"),
            root["turn"]?.jsonObjectOrNull()?.string("type"),
            root.string("auth_provider"),
            root.string("authProvider"),
            root.string("bypass_provider"),
            root.string("bypassProvider"),
            root.string("provider")
        )

        val transportArgs = firstNotBlank(
            source.string("transport_args"),
            source.string("transportArgs"),
            source.string("args"),
            root.string("transport_args"),
            root.string("transportArgs"),
            root.string("args")
        )

        val vp8Fps = firstInt(
            source.int("vp8_fps"),
            source.int("vp8Fps"),
            root.int("vp8_fps"),
            root.int("vp8Fps"),
            transportArgInt(transportArgs, "-vp8-fps")
        ) ?: LocationConfig.DEFAULT_VP8_FPS

        val vp8Batch = firstInt(
            source.int("vp8_batch"),
            source.int("vp8Batch"),
            root.int("vp8_batch"),
            root.int("vp8Batch"),
            transportArgInt(transportArgs, "-vp8-batch")
        ) ?: LocationConfig.DEFAULT_VP8_BATCH

        val location = LocationConfig(
            name = firstNotBlank(source.string("name"), root.string("name")),
            id = firstNotBlank(
                source.string("id"),
                source.string("room_id"),
                source.string("server"),
                root.string("id")
            ),
            key = firstNotBlank(
                source.string("key"),
                source.string("encryption_key"),
                source.string("password"),
                root.string("key")
            ),
            bypassProvider = provider,
            transport = firstNotBlank(
                source.string("transport"),
                root.string("transport"),
                if (transportArgs.isNotBlank()) LocationConfig.TRANSPORT_VP8CHANNEL else null,
                LocationConfig.defaultTransportForProvider(provider)
            ),
            vp8Fps = vp8Fps,
            vp8Batch = vp8Batch,
            dnsServer = firstNotBlank(
                source.string("dns_server"),
                source.string("dnsServer"),
                source.string("dns"),
                root.string("dns_server"),
                root.string("dnsServer"),
                root.string("dns")
            )
        ).normalized()

        if (!location.isComplete()) return null

        val storageId = firstNotBlank(
            fallbackStorageId,
            root.string("storage_id"),
            root.string("storageId"),
            source.string("storage_id"),
            source.string("storageId"),
            "imported_${location.storageSlug()}"
        )

        return LocationEntry.from(storageId, location, subscriptionUrl = subscriptionUrl)
    }

    private fun parseOlcRtcText(
        text: String,
        subscriptionUrl: String? = null,
        updateIntervalMs: Long? = null
    ): LocationBundleV4? {
        if (!text.contains(OLCRTC_URI_PREFIX)) return null

        val subscriptionFields = linkedMapOf<String, String>()
        val locations = mutableListOf<Pair<ParsedOlcRtcUri, MutableMap<String, String>>>()
        var localFields: MutableMap<String, String>? = null

        text.lineSequence()
            .map { it.normalizedImportText() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                when {
                    line.startsWith(OLCRTC_URI_PREFIX) -> {
                        parseOlcRtcUri(line)?.let { parsed ->
                            val fields = linkedMapOf<String, String>()
                            locations += parsed to fields
                            localFields = fields
                        }
                    }

                    line.startsWith("##") && locations.isNotEmpty() -> {
                        val (key, value) = parseSubscriptionField(
                            line.removePrefix("##")
                        ) ?: return@forEach

                        localFields?.set(key, value)
                    }

                    line.startsWith("#") -> {
                        val (key, value) = parseSubscriptionField(
                            line.removePrefix("#")
                        ) ?: return@forEach

                        subscriptionFields[key] = value
                    }
                }
            }

        if (locations.isEmpty()) return null

        val subscriptionMetadata = buildSubscriptionMetadata(subscriptionFields)
            .withSubscriptionInterval(updateIntervalMs)
        val usedStorageIds = mutableSetOf<String>()

        val entries = locations.mapIndexed { index, (parsed, fields) ->
            val metadata = buildLocationMetadata(
                fields = fields,
                mimo = parsed.mimo,
                subscription = subscriptionMetadata
            )
            val location = parsed.location.copy(
                name = firstNotBlank(
                    metadata?.name,
                    parsed.mimo,
                    parsed.location.name
                ),
                failoverRoomIds = parseFailoverRooms(fields["rooms"])
            ).normalized()
            val base = location.storageSlug().ifBlank { "location_${index + 1}" }
            val storageId = uniqueStorageId("imported_$base", usedStorageIds)
            LocationEntry.from(
                storageId = storageId,
                location = location,
                subscriptionUrl = subscriptionUrl,
                metadata = metadata
            )
        }

        return LocationBundleV4(
            activeLocationId = entries.firstOrNull()?.storageId,
            locations = entries
        )
    }

    private fun parseOlcRtcUri(line: String): ParsedOlcRtcUri? {
        val payload = line.removePrefix(OLCRTC_URI_PREFIX)

        val transportMarker = payload.indexOf('?')
        val roomMarker = payload.indexOf('@', startIndex = transportMarker + 1)
        val keyMarker = payload.indexOf('#', startIndex = roomMarker + 1)

        if (transportMarker <= 0 || roomMarker <= transportMarker || keyMarker <= roomMarker) {
            return null
        }

        val clientMarker = payload
            .indexOf('%', startIndex = keyMarker + 1)
            .takeIf { it >= 0 }

        val mimoMarker = payload
            .indexOf('$', startIndex = keyMarker + 1)
            .takeIf { it >= 0 }

        val keyEnd = listOfNotNull(clientMarker, mimoMarker).minOrNull() ?: payload.length

        val provider = payload.substring(0, transportMarker).trim()
        val transportToken = payload.substring(transportMarker + 1, roomMarker).trim()
        val (transport, transportOptions) = parseTransportToken(transportToken)
        val roomId = payload.substring(roomMarker + 1, keyMarker).trim()
        val key = payload.substring(keyMarker + 1, keyEnd).trim()

        val mimo = mimoMarker
            ?.let { payload.substring(it + 1) }
            .orEmpty()
            .trim()

        val location = LocationConfig(
            name = mimo.ifBlank { roomId },
            id = roomId,
            key = key,
            bypassProvider = provider,
            transport = transport,
            vp8Fps = transportOptions["vp8-fps"]
                ?: transportOptions["fps"]
                ?: LocationConfig.DEFAULT_VP8_FPS,
            vp8Batch = transportOptions["vp8-batch"]
                ?: transportOptions["batch"]
                ?: LocationConfig.DEFAULT_VP8_BATCH
        ).normalized()

        return location
            .takeIf { it.isComplete() }
            ?.let { ParsedOlcRtcUri(it, mimo.takeIf { value -> value.isNotBlank() }) }
    }

    private fun buildSubscriptionMetadata(fields: Map<String, String>): SubscriptionMetadata? {
        return SubscriptionMetadata(
            name = fields["name"],
            update = fields["update"],
            refresh = fields["refresh"],
            color = fields["color"],
            icon = fields["icon"],
            used = fields["used"],
            available = fields["available"],
            updateIntervalMs = parseRefreshDurationMs(fields["refresh"])
        ).normalized().takeUnless { it.isEmpty() }
    }

    private fun buildLocationMetadata(
        fields: Map<String, String>,
        mimo: String?,
        subscription: SubscriptionMetadata?
    ): LocationMetadata? {
        return LocationMetadata(
            name = fields["name"],
            color = fields["color"],
            icon = fields["icon"],
            used = fields["used"],
            available = fields["available"],
            ip = fields["ip"],
            comment = fields["comment"],
            mimo = mimo,
            subscription = subscription
        ).normalized().takeUnless { it.isEmpty() }
    }

    // Extra failover room ids delivered via a `##rooms:` header (comma/space
    // separated). They share the location's key/provider/transport.
    private fun parseFailoverRooms(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',', ' ', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun parseTransportToken(token: String): Pair<String, Map<String, Int>> {
        val optionsStart = token.indexOf('<')
        val optionsEnd = token.lastIndexOf('>')
        if (optionsStart < 0 || optionsEnd <= optionsStart) {
            return token to emptyMap()
        }

        val transport = token.substring(0, optionsStart).trim()
        val options = token.substring(optionsStart + 1, optionsEnd)
            .split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = part.substring(0, separator).trim().lowercase()
                val value = part.substring(separator + 1).trim().toIntOrNull() ?: return@mapNotNull null
                key to value
            }
            .toMap()

        return transport to options
    }

    private fun parseSubscriptionField(value: String): Pair<String, String>? {
        val separator = value.indexOf(':')
        if (separator <= 0) return null

        val key = value.substring(0, separator).trim().lowercase()
        val fieldValue = value.substring(separator + 1).trim()

        return key to fieldValue
    }

    private fun uniqueStorageId(base: String, used: MutableSet<String>): String {
        val normalizedBase = base.storageSlug()
        var candidate = normalizedBase
        var suffix = 2

        while (!used.add(candidate)) {
            candidate = "${normalizedBase}_$suffix"
            suffix += 1
        }

        return candidate
    }

    private fun decodeLocationEntry(root: JsonObject, subscriptionUrl: String? = null): LocationEntry? {
        return runCatching {
            json.decodeFromJsonElement(LocationEntry.serializer(), root)
                .let { entry ->
                    if (entry.subscriptionUrl.isNullOrBlank() && !subscriptionUrl.isNullOrBlank()) {
                        entry.copy(subscriptionUrl = subscriptionUrl)
                    } else {
                        entry
                    }
                }
                .normalized()
                .takeIf { it.location.isComplete() }
        }.getOrNull()
    }

    private fun subscriptionSignature(location: LocationConfig): String {
        val normalized = location.normalized()
        return listOf(
            normalized.bypassProvider,
            normalized.transport,
            normalized.id,
            normalized.key
        ).joinToString("|")
    }

    private fun LocationConfig.storageSlug(): String {
        return displayName().ifBlank { id }.storageSlug()
    }

    private fun String.storageSlug(): String {
        return lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .take(32)
            .ifBlank { "location" }
    }

    private fun JsonObject.string(name: String): String? {
        return (this[name] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.int(name: String): Int? {
        return (this[name] as? JsonPrimitive)?.intOrNull
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? {
        return runCatching { jsonObject }.getOrNull()
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() } ?: ""
    }

    private fun firstInt(vararg values: Int?): Int? {
        return values.firstOrNull { it != null }
    }

    private fun transportArgInt(args: String, name: String): Int? {
        if (args.isBlank()) return null

        val parts = args.split(Regex("\\s+")).filter { it.isNotBlank() }
        val index = parts.indexOf(name)

        return parts.getOrNull(index + 1)?.toIntOrNull()
    }

    private fun HttpResponse.profileUpdateIntervalMs(): Long? {
        return headers["profile-update-interval"]
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(
                SubscriptionMetadata.MIN_UPDATE_INTERVAL_HOURS,
                SubscriptionMetadata.MAX_UPDATE_INTERVAL_HOURS
            )
            ?.toLong()
            ?.times(SubscriptionMetadata.HOUR_MS)
    }

    private fun List<LocationEntry>.subscriptionUpdateIntervalMs(): Long? {
        return firstNotNullOfOrNull { entry ->
            entry.metadata?.subscription?.updateIntervalMs
        }
    }

    private fun SubscriptionMetadata?.withSubscriptionInterval(intervalMs: Long?): SubscriptionMetadata? {
        if (intervalMs == null) return this
        return (this ?: SubscriptionMetadata()).copy(
            updateIntervalMs = intervalMs
        ).normalized()
    }

    private fun LocationMetadata?.withSubscriptionInterval(intervalMs: Long?): LocationMetadata? {
        if (intervalMs == null) return this
        val subscription = this?.subscription ?: SubscriptionMetadata()
        return (this ?: LocationMetadata()).copy(
            subscription = subscription.copy(updateIntervalMs = intervalMs)
        ).normalized()
    }

    private fun LocationMetadata?.withManualSubscriptionInterval(intervalMs: Long?): LocationMetadata {
        val subscription = this?.subscription ?: SubscriptionMetadata()
        return (this ?: LocationMetadata()).copy(
            subscription = subscription.copy(manualUpdateIntervalMs = intervalMs)
        )
            .normalized()
    }

    private fun LocationMetadata?.withSubscriptionRequestSecurity(
        allowInsecureRequests: Boolean
    ): LocationMetadata {
        val subscription = this?.subscription ?: SubscriptionMetadata()
        return (this ?: LocationMetadata()).copy(
            subscription = subscription.copy(
                allowInsecureRequests = allowInsecureRequests
            )
        ).normalized()
    }

    private fun LocationMetadata?.withSubscriptionRefreshState(
        updateIntervalMs: Long,
        manualUpdateIntervalMs: Long?,
        lastRefreshAttemptAtEpochMs: Long,
        lastRefreshAtEpochMs: Long,
        consecutiveRefreshFailures: Int
    ): LocationMetadata {
        val subscription = this?.subscription ?: SubscriptionMetadata()
        return (this ?: LocationMetadata()).copy(
            subscription = subscription.copy(
                updateIntervalMs = updateIntervalMs,
                manualUpdateIntervalMs = manualUpdateIntervalMs,
                lastRefreshAttemptAtEpochMs = lastRefreshAttemptAtEpochMs,
                lastRefreshAtEpochMs = lastRefreshAtEpochMs,
                consecutiveRefreshFailures = consecutiveRefreshFailures
            )
        ).normalized()
    }

    private fun LocationMetadata?.withSubscriptionRefreshFailure(attemptAtEpochMs: Long): LocationMetadata {
        val subscription = this?.subscription ?: SubscriptionMetadata(
            updateIntervalMs = SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_MS
        )
        return (this ?: LocationMetadata()).copy(
            subscription = subscription.copy(
                lastRefreshAttemptAtEpochMs = attemptAtEpochMs,
                consecutiveRefreshFailures = subscription.consecutiveRefreshFailures + 1
            )
        ).normalized()
    }

    private fun parseRefreshDurationMs(value: String?): Long? {
        return parseSubscriptionRefreshIntervalMs(
            value = value.orEmpty(),
            clampToSupportedRange = true
        )
    }

    private companion object {
        const val OLCRTC_URI_PREFIX = "olcrtc://"
        const val UTF8_BOM = "\uFEFF"
        val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    }
}
