package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
    socketTimeoutMs: Long = 8_000
): HttpClient

internal expect suspend fun <T> withProxyAuthentication(
    subscriptionProxy: SubscriptionFetchProxy?,
    block: suspend () -> T
): T

class LocationsRepositoryImpl(
    private val dataSource: LocationsDataSource,
    private val httpClient: HttpClient = createProxyHttpClient(),
    private val deviceIdentityProvider: DeviceIdentityProvider = PersistentDeviceIdentityProvider(dataSource),
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
) : LocationsRepository {
    private data class ImportSource(
        val content: String,
        val subscriptionUrl: String? = null,
        val updateIntervalHours: Int? = null,
        val requestMode: SubscriptionRequestMode = SubscriptionRequestMode.Identity
    )

    private data class DownloadedSubscription(
        val content: String,
        val updateIntervalHours: Int?
    )

    private data class ParsedImport(
        val bundle: LocationBundleV4,
        val mode: ImportMode
    )

    private data class ResolvedImport(
        val source: ImportSource,
        val parsed: ParsedImport
    )

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

    override suspend fun importText(text: String, subscriptionProxy: SubscriptionFetchProxy?): Boolean {
        val resolved = resolveParsedImport(
            text = text,
            subscriptionProxy = subscriptionProxy
        ) ?: return false

        mutationMutex.withLock {
            val merged = mergeImportedBundle(
                current = getBundleUnlocked(),
                imported = resolved.parsed.bundle.normalized(),
                replaceMatchingStorageIds = resolved.parsed.mode == ImportMode.Restore
            )
            saveBundleUnlocked(merged)
        }
        return true
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

        fun preservePreviousEntries(entries: List<LocationEntry>) {
            entries.forEach { entry ->
                if (usedStorageIds.add(entry.storageId)) {
                    refreshedLocations += entry
                }
            }
        }

        groupedByUrl.forEach { (url, previousEntries) ->
            val previousInterval = previousEntries.subscriptionUpdateIntervalHours()
            val resolved = resolveParsedImport(
                text = url,
                fallbackSubscriptionInterval = previousInterval,
                subscriptionProxy = subscriptionProxy
            ) ?: run {
                preservePreviousEntries(previousEntries)
                return@forEach
            }
            val source = resolved.source
            val updateInterval = source.updateIntervalHours
                ?: previousInterval
                ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS
            val refreshTimestamp = nowEpochMs()
            val refreshed = resolved.parsed.bundle.locations
            if (refreshed.isEmpty()) {
                preservePreviousEntries(previousEntries)
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
                    metadata = entry.metadata.withSubscriptionRefreshState(
                        updateIntervalHours = updateInterval,
                        lastRefreshAtEpochMs = refreshTimestamp
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

        if (successfulRefreshes == 0) return 0

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
                    val metadata = entry.metadata?.subscription
                    val interval = metadata?.updateIntervalHours
                        ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS
                    val lastRefreshAt = metadata?.lastRefreshAtEpochMs ?: 0L
                    val intervalMs = interval.toLong() * 60L * 60L * 1_000L
                    url.takeIf { lastRefreshAt <= 0L || now - lastRefreshAt >= intervalMs }
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

    override suspend fun setSubscriptionUpdateInterval(subscriptionUrl: String, hours: Int) {
        val normalizedUrl = subscriptionUrl.trim()
        if (normalizedUrl.isBlank()) return

        mutationMutex.withLock {
            val interval = hours.coerceIn(
                SubscriptionMetadata.MIN_UPDATE_INTERVAL_HOURS,
                SubscriptionMetadata.MAX_UPDATE_INTERVAL_HOURS
            )
            val bundle = getBundleUnlocked()
            val updated = bundle.locations.map { entry ->
                if (entry.subscriptionUrl?.trim() != normalizedUrl) {
                    entry
                } else {
                    entry.copy(
                        metadata = entry.metadata.withSubscriptionInterval(interval)
                    ).normalized()
                }
            }

            saveBundleUnlocked(bundle.copy(locations = updated))
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
                metadata = current?.metadata,
                routing = current?.routing
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
        fallbackSubscriptionInterval: Int? = null,
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): ResolvedImport? {
        val input = text.normalizedImportText()
        if (input.isBlank()) return null

        var source = resolveImportSource(
            text = input,
            requestMode = SubscriptionRequestMode.Identity,
            subscriptionProxy = subscriptionProxy
        ) ?: run {
            if (input.isHttpUrl()) {
                resolveImportSource(
                    text = input,
                    requestMode = SubscriptionRequestMode.Compatibility,
                    subscriptionProxy = subscriptionProxy
                )
            } else {
                null
            }
        } ?: return null

        var parsed = parseImportSource(source, fallbackSubscriptionInterval)
        if (parsed == null && input.isHttpUrl() && source.requestMode != SubscriptionRequestMode.Compatibility) {
            val fallbackSource = resolveImportSource(
                text = input,
                requestMode = SubscriptionRequestMode.Compatibility,
                subscriptionProxy = subscriptionProxy
            )
            if (fallbackSource != null) {
                source = fallbackSource
                parsed = parseImportSource(fallbackSource, fallbackSubscriptionInterval)
            }
        }

        return parsed?.let { ResolvedImport(source, it) }
    }

    private fun parseImportSource(
        source: ImportSource,
        fallbackSubscriptionInterval: Int? = null
    ): ParsedImport? {
        val initialSubscriptionInterval = source.updateIntervalHours
            ?: fallbackSubscriptionInterval
            ?: source.subscriptionUrl?.let { SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS }

        return parseImport(
            source.content.normalizedImportText(),
            source.subscriptionUrl,
            initialSubscriptionInterval
        )
    }

    private suspend fun resolveImportSource(
        text: String,
        requestMode: SubscriptionRequestMode,
        subscriptionProxy: SubscriptionFetchProxy?
    ): ImportSource? {
        if (text.isBlank()) return null

        if (!text.isHttpUrl()) {
            return ImportSource(content = text.normalizedImportText())
        }

        val downloaded = downloadTextFromUrl(
            url = text,
            requestMode = requestMode,
            subscriptionProxy = subscriptionProxy
        ) ?: return null
        return downloaded.content
            .normalizedImportText()
            .takeIf { it.isNotBlank() }
            ?.let {
                ImportSource(
                    content = it,
                    subscriptionUrl = text.trim(),
                    updateIntervalHours = downloaded.updateIntervalHours,
                    requestMode = requestMode
                )
            }
    }

    private suspend fun downloadTextFromUrl(
        url: String,
        requestMode: SubscriptionRequestMode,
        subscriptionProxy: SubscriptionFetchProxy?
    ): DownloadedSubscription? {
        val hwid = if (requestMode == SubscriptionRequestMode.Identity) {
            deviceIdentityProvider.hwid()
        } else {
            null
        }
        val client = if (subscriptionProxy == null) {
            httpClient
        } else {
            createProxyHttpClient(subscriptionProxy)
        }

        return try {
            withProxyAuthentication(subscriptionProxy) {
                val response = runCatching {
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
                }.getOrNull() ?: return@withProxyAuthentication null

                if (response.status.value !in 200..299) {
                    return@withProxyAuthentication null
                }

                val content = runCatching {
                    response.bodyAsText()
                }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@withProxyAuthentication null

                DownloadedSubscription(
                    content = content,
                    updateIntervalHours = response.profileUpdateIntervalHours()
                )
            }
        } finally {
            if (subscriptionProxy != null) {
                client.close()
            }
        }
    }

    private fun String.isHttpUrl(): Boolean {
        val value = trim().lowercase()
        return value.startsWith("http://") || value.startsWith("https://")
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
        updateIntervalHours: Int? = null
    ): ParsedImport? {
        parseOlcRtcText(text, subscriptionUrl, updateIntervalHours)?.let {
            return ParsedImport(it, ImportMode.Additive)
        }

        if (!text.startsWith("{") || !text.endsWith("}")) return null

        val root = runCatching {
            json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null

        parseBundle(root, subscriptionUrl, updateIntervalHours)?.let {
            return ParsedImport(it, ImportMode.Restore)
        }

        return parseSingleLocation(root, null, subscriptionUrl)?.let {
            ParsedImport(
                LocationBundleV4(
                    activeLocationId = it.storageId,
                    locations = listOf(
                        it.copy(
                            metadata = it.metadata.withSubscriptionInterval(updateIntervalHours)
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

    private fun parseBundle(
        root: JsonObject,
        subscriptionUrl: String? = null,
        updateIntervalHours: Int? = null
    ): LocationBundleV4? {
        val locationsElement = root["locations"] ?: return null

        val locations = runCatching {
            locationsElement.jsonArray
        }.getOrNull()?.mapNotNull { element ->
            val item = element.jsonObjectOrNull() ?: return@mapNotNull null

            decodeLocationEntry(item, subscriptionUrl)?.let {
                return@mapNotNull it.copy(
                    metadata = it.metadata.withSubscriptionInterval(updateIntervalHours)
                ).normalized()
            }

            val storageId = item.string("storage_id")
                ?: item.string("storageId")
                ?: item.string("id")?.let { "imported_${it.storageSlug()}" }

            parseSingleLocation(item, storageId, subscriptionUrl)?.let { entry ->
                entry.copy(
                    metadata = entry.metadata.withSubscriptionInterval(updateIntervalHours)
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
                if (transportArgs.isNotBlank()) LocationConfig.TRANSPORT_VP8CHANNEL else null
            ),
            vp8Fps = vp8Fps,
            vp8Batch = vp8Batch
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
        updateIntervalHours: Int? = null
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
            .withSubscriptionInterval(updateIntervalHours)
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
                )
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
            available = fields["available"]
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

    private fun HttpResponse.profileUpdateIntervalHours(): Int? {
        return headers["profile-update-interval"]
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(
                SubscriptionMetadata.MIN_UPDATE_INTERVAL_HOURS,
                SubscriptionMetadata.MAX_UPDATE_INTERVAL_HOURS
            )
    }

    private fun List<LocationEntry>.subscriptionUpdateIntervalHours(): Int? {
        return firstNotNullOfOrNull { entry ->
            entry.metadata?.subscription?.updateIntervalHours
        }
    }

    private fun SubscriptionMetadata?.withSubscriptionInterval(hours: Int?): SubscriptionMetadata? {
        if (hours == null) return this
        return (this ?: SubscriptionMetadata()).copy(
            updateIntervalHours = hours
        ).normalized()
    }

    private fun LocationMetadata?.withSubscriptionInterval(hours: Int?): LocationMetadata? {
        if (hours == null) return this
        return withSubscriptionRefreshState(
            updateIntervalHours = hours,
            lastRefreshAtEpochMs = this?.subscription?.lastRefreshAtEpochMs
        )
    }

    private fun LocationMetadata?.withSubscriptionRefreshState(
        updateIntervalHours: Int,
        lastRefreshAtEpochMs: Long?
    ): LocationMetadata {
        val subscription = this?.subscription ?: SubscriptionMetadata()
        return (this ?: LocationMetadata()).copy(
            subscription = subscription.copy(
                updateIntervalHours = updateIntervalHours,
                lastRefreshAtEpochMs = lastRefreshAtEpochMs
            )
        ).normalized()
    }

    private companion object {
        const val OLCRTC_URI_PREFIX = "olcrtc://"
        const val UTF8_BOM = "\uFEFF"
    }
}
