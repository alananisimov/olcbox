package org.olcbox.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class LocationConfig(
    val name: String = "",
    val id: String = "",
    val key: String = "",
    @SerialName("bypass_provider")
    val bypassProvider: String = DEFAULT_BYPASS_PROVIDER,
    val transport: String = DEFAULT_TRANSPORT,
    @SerialName("vp8_fps")
    val vp8Fps: Int = DEFAULT_VP8_FPS,
    @SerialName("vp8_batch")
    val vp8Batch: Int = DEFAULT_VP8_BATCH
) {
    fun normalized(): LocationConfig {
        val provider = normalizeProvider(bypassProvider)
        val normalizedTransport = normalizeTransport(transport, provider)
        return copy(
            name = name.trim(),
            id = id.trim(),
            key = key.trim(),
            bypassProvider = provider,
            transport = normalizedTransport,
            vp8Fps = sanitizeVp8Fps(vp8Fps),
            vp8Batch = sanitizeVp8Batch(vp8Batch)
        )
    }

    fun isComplete(): Boolean = id.isNotBlank() && key.isNotBlank()

    fun displayName(): String = name.ifBlank { id }

    fun providerName(): String = providerDisplayName(bypassProvider)

    fun transportName(): String = transportDisplayName(transport)

    companion object {
        const val PROVIDER_JAZZ = "jazz"
        const val PROVIDER_TELEMOST = "telemost"
        const val PROVIDER_WB_STREAM = "wbstream"
        const val PROVIDER_JITSI = "jitsi"
        const val DEFAULT_BYPASS_PROVIDER = PROVIDER_WB_STREAM

        const val TRANSPORT_DATACHANNEL = "datachannel"
        const val TRANSPORT_VP8CHANNEL = "vp8channel"
        const val TRANSPORT_SEICHANNEL = "seichannel"
        const val DEFAULT_TRANSPORT = TRANSPORT_VP8CHANNEL

        const val DEFAULT_VP8_FPS = 60
        const val DEFAULT_VP8_BATCH = 64

        val supportedBypassProviders = listOf(
            PROVIDER_JAZZ,
            PROVIDER_TELEMOST,
            PROVIDER_WB_STREAM,
            PROVIDER_JITSI
        )

        val supportedTransports = listOf(
            TRANSPORT_DATACHANNEL,
            TRANSPORT_VP8CHANNEL,
            TRANSPORT_SEICHANNEL
        )

        fun supportedTransportsForProvider(provider: String): List<String> {
            return when (normalizeProvider(provider)) {
                PROVIDER_TELEMOST -> listOf(TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL)
                PROVIDER_JITSI -> listOf(TRANSPORT_DATACHANNEL)
                else -> supportedTransports
            }
        }

        fun normalizeProvider(value: String): String {
            return when (value.trim().lowercase()) {
                PROVIDER_JAZZ, "sberjazz", "sber_jazz" -> PROVIDER_JAZZ
                PROVIDER_TELEMOST, "yandex", "yandex_telemost" -> PROVIDER_TELEMOST
                PROVIDER_WB_STREAM, "wbstream", "wb-stream", "wildberries" -> PROVIDER_WB_STREAM
                PROVIDER_JITSI, "jitsi-meet", "jitsi_meet", "meet" -> PROVIDER_JITSI
                else -> DEFAULT_BYPASS_PROVIDER
            }
        }

        fun normalizeTransport(value: String, provider: String = DEFAULT_BYPASS_PROVIDER): String {
            val normalized = when (value.trim().lowercase()) {
                TRANSPORT_DATACHANNEL, "data", "dc" -> TRANSPORT_DATACHANNEL
                TRANSPORT_VP8CHANNEL, "vp8", "video_vp8", "video-vp8" -> TRANSPORT_VP8CHANNEL
                TRANSPORT_SEICHANNEL, "sei", "sei_channel", "sei-channel", "h264_sei" -> TRANSPORT_SEICHANNEL
                else -> DEFAULT_TRANSPORT
            }
            val supported = supportedTransportsForProvider(provider)
            return normalized.takeIf { it in supported }
                ?: supported.firstOrNull()
                ?: DEFAULT_TRANSPORT
        }

        fun providerDisplayName(provider: String): String {
            return when (normalizeProvider(provider)) {
                PROVIDER_JAZZ -> "Jazz"
                PROVIDER_TELEMOST -> "Telemost"
                PROVIDER_WB_STREAM -> "WB Stream"
                PROVIDER_JITSI -> "Jitsi"
                else -> "WB Stream"
            }
        }

        fun transportDisplayName(transport: String): String {
            return when (normalizeTransport(transport)) {
                TRANSPORT_DATACHANNEL -> "DataChannel"
                TRANSPORT_VP8CHANNEL -> "VP8"
                TRANSPORT_SEICHANNEL -> "SEI"
                else -> "VP8"
            }
        }

        fun sanitizeVp8Fps(value: Int): Int = value.coerceIn(1, 120)

        fun sanitizeVp8Batch(value: Int): Int = value.coerceIn(1, 64)
    }
}

@Serializable
data class Vp8TransportConfig(
    val fps: Int = LocationConfig.DEFAULT_VP8_FPS,
    val batch: Int = LocationConfig.DEFAULT_VP8_BATCH
) {
    fun normalized(): Vp8TransportConfig {
        return copy(
            fps = LocationConfig.sanitizeVp8Fps(fps),
            batch = LocationConfig.sanitizeVp8Batch(batch)
        )
    }

    companion object {
        fun from(config: LocationConfig): Vp8TransportConfig {
            return Vp8TransportConfig(config.vp8Fps, config.vp8Batch).normalized()
        }
    }
}

@Serializable(with = LocationTransportConfigSerializer::class)
data class LocationTransportConfig(
    val type: String = LocationConfig.DEFAULT_TRANSPORT,
    val vp8: Vp8TransportConfig? = null
) {
    fun normalized(provider: String): LocationTransportConfig {
        val normalizedType = LocationConfig.normalizeTransport(type, provider)
        return copy(
            type = normalizedType,
            vp8 = if (normalizedType == LocationConfig.TRANSPORT_VP8CHANNEL) {
                (vp8 ?: Vp8TransportConfig()).normalized()
            } else {
                null
            }
        )
    }

    companion object {
        fun from(config: LocationConfig): LocationTransportConfig {
            val normalized = config.normalized()
            return LocationTransportConfig(
                type = normalized.transport,
                vp8 = if (normalized.transport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                    Vp8TransportConfig.from(normalized)
                } else {
                    null
                }
            )
        }
    }
}

@Serializable
private data class LocationTransportConfigSurrogate(
    val type: String = LocationConfig.DEFAULT_TRANSPORT,
    val vp8: Vp8TransportConfig? = null
)

object LocationTransportConfigSerializer : KSerializer<LocationTransportConfig> {
    override val descriptor: SerialDescriptor = LocationTransportConfigSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): LocationTransportConfig {
        val jsonDecoder = decoder as? JsonDecoder ?: return LocationTransportConfig()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> LocationTransportConfig(type = element.contentOrNull.orEmpty())
            is JsonObject -> {
                val surrogate = jsonDecoder.json.decodeFromJsonElement(
                    LocationTransportConfigSurrogate.serializer(),
                    element
                )
                LocationTransportConfig(
                    type = surrogate.type,
                    vp8 = surrogate.vp8
                )
            }
            else -> LocationTransportConfig()
        }
    }

    override fun serialize(encoder: Encoder, value: LocationTransportConfig) {
        val jsonEncoder = encoder as? JsonEncoder
        val surrogate = LocationTransportConfigSurrogate(
            type = value.type,
            vp8 = value.vp8
        )
        if (jsonEncoder != null) {
            jsonEncoder.encodeJsonElement(
                jsonEncoder.json.encodeToJsonElement(
                    LocationTransportConfigSurrogate.serializer(),
                    surrogate
                )
            )
        } else {
            encoder.encodeSerializableValue(LocationTransportConfigSurrogate.serializer(), surrogate)
        }
    }
}

@Serializable
data class LocationEndpointConfig(
    @SerialName("room_id")
    val roomId: String = "",
    val key: String = "",
    @SerialName("client_id")
    val legacyClientId: String? = null
)

@Serializable
data class SubscriptionMetadata(
    val name: String? = null,
    val update: String? = null,
    val refresh: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val used: String? = null,
    val available: String? = null,
    @SerialName("update_interval_hours")
    val updateIntervalHours: Int? = null,
    @SerialName("last_refresh_at_epoch_ms")
    val lastRefreshAtEpochMs: Long? = null
) {
    fun normalized(): SubscriptionMetadata {
        return copy(
            name = name.cleanMetadataValue(),
            update = update.cleanMetadataValue(),
            refresh = refresh.cleanMetadataValue(),
            color = color.cleanMetadataValue(),
            icon = icon.cleanMetadataValue(),
            used = used.cleanMetadataValue(),
            available = available.cleanMetadataValue(),
            updateIntervalHours = updateIntervalHours?.coerceIn(MIN_UPDATE_INTERVAL_HOURS, MAX_UPDATE_INTERVAL_HOURS),
            lastRefreshAtEpochMs = lastRefreshAtEpochMs?.takeIf { it > 0 }
        )
    }

    fun isEmpty(): Boolean {
        return name.isNullOrBlank() &&
                update.isNullOrBlank() &&
                refresh.isNullOrBlank() &&
                color.isNullOrBlank() &&
                icon.isNullOrBlank() &&
                used.isNullOrBlank() &&
                available.isNullOrBlank() &&
                updateIntervalHours == null &&
                lastRefreshAtEpochMs == null
    }

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_HOURS = 24
        const val MIN_UPDATE_INTERVAL_HOURS = 1
        const val MAX_UPDATE_INTERVAL_HOURS = 720
    }
}

@Serializable
data class LocationMetadata(
    val name: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val used: String? = null,
    val available: String? = null,
    val ip: String? = null,
    val comment: String? = null,
    val mimo: String? = null,
    val subscription: SubscriptionMetadata? = null
) {
    fun normalized(): LocationMetadata {
        val normalizedSubscription = subscription
            ?.normalized()
            ?.takeUnless { it.isEmpty() }
        return copy(
            name = name.cleanMetadataValue(),
            color = color.cleanMetadataValue(),
            icon = icon.cleanMetadataValue(),
            used = used.cleanMetadataValue(),
            available = available.cleanMetadataValue(),
            ip = ip.cleanMetadataValue(),
            comment = comment.cleanMetadataValue(),
            mimo = mimo.cleanMetadataValue(),
            subscription = normalizedSubscription
        )
    }

    fun isEmpty(): Boolean {
        return name.isNullOrBlank() &&
                color.isNullOrBlank() &&
                icon.isNullOrBlank() &&
                used.isNullOrBlank() &&
                available.isNullOrBlank() &&
                ip.isNullOrBlank() &&
                comment.isNullOrBlank() &&
                mimo.isNullOrBlank() &&
                (subscription == null || subscription.isEmpty())
    }
}

@Serializable
enum class RoutingSplitTunnelMode {
    @SerialName("full_tunnel")
    FullTunnel,

    @SerialName("proxy_selected")
    ProxySelected,

    @SerialName("bypass_selected")
    BypassSelected
}

@Serializable
enum class RoutingRuleAction {
    @SerialName("proxy")
    Proxy,

    @SerialName("bypass")
    Bypass
}

@Serializable
enum class RoutingRuleType {
    @SerialName("domain")
    Domain,

    @SerialName("domain_suffix")
    DomainSuffix,

    @SerialName("ip_cidr")
    IpCidr,

    @SerialName("geosite")
    GeoSite,

    @SerialName("geoip")
    GeoIp
}

@Serializable
data class RoutingDatListConfig(
    val name: String = "",
    val url: String = "",
    val categories: List<String> = emptyList(),
    val action: RoutingRuleAction = RoutingRuleAction.Proxy
) {
    fun normalized(): RoutingDatListConfig {
        return copy(
            name = name.trim(),
            url = url.trim(),
            categories = categories.cleanedRoutingValues()
        )
    }

    fun isEmpty(): Boolean {
        return name.isBlank() && url.isBlank() && categories.isEmpty()
    }
}

@Serializable
data class RoutingRuleConfig(
    val type: RoutingRuleType = RoutingRuleType.DomainSuffix,
    val value: String = "",
    val action: RoutingRuleAction = RoutingRuleAction.Proxy
) {
    fun normalized(): RoutingRuleConfig {
        return copy(value = value.trim())
    }

    fun isEmpty(): Boolean = value.isBlank()
}

@Serializable
data class RoutingPolicyConfig(
    @SerialName("split_tunnel")
    val splitTunnel: RoutingSplitTunnelMode = RoutingSplitTunnelMode.FullTunnel,
    @SerialName("dat_lists")
    val datLists: List<RoutingDatListConfig> = emptyList(),
    val rules: List<RoutingRuleConfig> = emptyList()
) {
    fun normalized(): RoutingPolicyConfig {
        return copy(
            datLists = datLists
                .map { it.normalized() }
                .filterNot { it.isEmpty() }
                .distinctBy { "${it.name}|${it.url}|${it.categories.joinToString(",")}|${it.action}" },
            rules = rules
                .map { it.normalized() }
                .filterNot { it.isEmpty() }
                .distinctBy { "${it.type}|${it.value}|${it.action}" }
        )
    }

    fun isEmpty(): Boolean {
        return splitTunnel == RoutingSplitTunnelMode.FullTunnel &&
                datLists.isEmpty() &&
                rules.isEmpty()
    }
}

@Serializable
data class LocationEntry(
    @SerialName("storage_id")
    val storageId: String,
    val name: String = "",
    @SerialName("subscription_url")
    val subscriptionUrl: String? = null,
    val endpoint: LocationEndpointConfig? = null,
    @SerialName("auth_provider")
    val authProvider: String? = null,
    @SerialName("carrier")
    val legacyCarrier: String? = null,
    val transport: LocationTransportConfig? = null,
    val metadata: LocationMetadata? = null,
    val routing: RoutingPolicyConfig? = null,
    @SerialName("subscriptionUrl")
    val legacySubscriptionUrl: String? = null,
    @SerialName("id")
    val legacyId: String? = null,
    @SerialName("room_id")
    val legacyRoomId: String? = null,
    @SerialName("server")
    val legacyServer: String? = null,
    @SerialName("client_id")
    val legacyClientId: String? = null,
    @SerialName("clientId")
    val legacyClientIdCamel: String? = null,
    @SerialName("key")
    val legacyKey: String? = null,
    @SerialName("password")
    val legacyPassword: String? = null,
    @SerialName("bypass_provider")
    val legacyBypassProvider: String? = null,
    @SerialName("bypassProvider")
    val legacyBypassProviderCamel: String? = null,
    @SerialName("provider")
    val legacyProvider: String? = null,
    @SerialName("vp8_fps")
    val legacyVp8Fps: Int? = null,
    @SerialName("vp8Fps")
    val legacyVp8FpsCamel: Int? = null,
    @SerialName("vp8_batch")
    val legacyVp8Batch: Int? = null,
    @SerialName("vp8Batch")
    val legacyVp8BatchCamel: Int? = null
) {
    val location: LocationConfig
        get() {
            val provider = firstNotBlank(
                authProvider,
                legacyCarrier,
                legacyBypassProvider,
                legacyBypassProviderCamel,
                legacyProvider
            )
            val transportConfig = transport ?: LocationTransportConfig()
            val vp8Options = transportConfig.vp8
            return LocationConfig(
                name = name,
                id = firstNotBlank(endpoint?.roomId, legacyId, legacyRoomId, legacyServer),
                key = firstNotBlank(endpoint?.key, legacyKey, legacyPassword),
                bypassProvider = provider,
                transport = transportConfig.type,
                vp8Fps = vp8Options?.fps
                    ?: legacyVp8Fps
                    ?: legacyVp8FpsCamel
                    ?: LocationConfig.DEFAULT_VP8_FPS,
                vp8Batch = vp8Options?.batch
                    ?: legacyVp8Batch
                    ?: legacyVp8BatchCamel
                    ?: LocationConfig.DEFAULT_VP8_BATCH
            ).normalized()
        }

    val bypassProvider: String
        get() = location.bypassProvider

    fun normalized(): LocationEntry {
        val config = location
        return LocationEntry(
            storageId = storageId.trim(),
            name = config.name,
            subscriptionUrl = firstNotBlank(subscriptionUrl, legacySubscriptionUrl).ifBlank { null },
            endpoint = LocationEndpointConfig(
                roomId = config.id,
                key = config.key
            ),
            authProvider = config.bypassProvider,
            transport = LocationTransportConfig.from(config),
            metadata = metadata
                ?.normalized()
                ?.takeUnless { it.isEmpty() },
            routing = routing
                ?.normalized()
                ?.takeUnless { it.isEmpty() }
        )
    }

    companion object {
        fun from(
            storageId: String,
            location: LocationConfig,
            subscriptionUrl: String? = null,
            metadata: LocationMetadata? = null,
            routing: RoutingPolicyConfig? = null
        ): LocationEntry {
            val config = location.normalized()
            return LocationEntry(
                storageId = storageId,
                name = config.name,
                subscriptionUrl = subscriptionUrl,
                endpoint = LocationEndpointConfig(
                    roomId = config.id,
                    key = config.key
                ),
                authProvider = config.bypassProvider,
                transport = LocationTransportConfig.from(config),
                metadata = metadata,
                routing = routing
            ).normalized()
        }

        private fun firstNotBlank(vararg values: String?): String {
            return values.firstOrNull { !it.isNullOrBlank() } ?: ""
        }
    }
}

private fun String?.cleanMetadataValue(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}

private fun List<String>.cleanedRoutingValues(): List<String> {
    return mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() } }
        .distinct()
}

@Serializable
data class LocationBundleV4(
    val version: Int = 5,
    @SerialName("active_location_id")
    val activeLocationId: String? = null,
    val locations: List<LocationEntry> = emptyList()
) {
    fun normalized(): LocationBundleV4 {
        val normalizedLocations = locations
            .map { it.normalized() }
            .filter { it.storageId.isNotBlank() && it.location.isComplete() }
            .distinctBy { it.storageId }

        val active = activeLocationId
            ?.takeIf { id -> normalizedLocations.any { it.storageId == id } }
            ?: normalizedLocations.firstOrNull()?.storageId

        return copy(
            version = CURRENT_VERSION,
            activeLocationId = active,
            locations = normalizedLocations
        )
    }

    companion object {
        const val CURRENT_VERSION = 5
    }
}
