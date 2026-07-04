package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.RoutingRuleAction
import org.olcbox.app.data.model.RoutingRuleType
import org.olcbox.app.data.model.RoutingSplitTunnelMode
import org.olcbox.app.data.share.ConfigShareService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationsRepositoryImplTest {

    @Test
    fun exportsAndImportsBundleV5WithActiveLocation() = runTest {
        val first = LocationEntry.from(
            "amsterdam",
            LocationConfig("Amsterdam", "room-a", "key-a", LocationConfig.PROVIDER_JAZZ)
        )
        val second = LocationEntry.from(
            "berlin",
            LocationConfig("Berlin", "room-b", "key-b", LocationConfig.PROVIDER_TELEMOST)
        )
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "berlin",
                locations = listOf(first, second)
            )
        )
        val exported = LocationsRepositoryImpl(source).exportBundle()
        assertTrue("\"version\": 5" in exported)
        assertTrue("\"endpoint\"" in exported)
        assertTrue("\"auth_provider\"" in exported)
        assertTrue("\"client_id\"" !in exported)
        assertTrue("\"bypass_provider\"" !in exported)
        val importedSource = FakeLocationsDataSource()

        LocationsRepositoryImpl(importedSource).importText(exported)

        val imported = importedSource.stored
        assertNotNull(imported)
        assertEquals(5, imported.version)
        assertEquals("berlin", imported.activeLocationId)
        assertEquals(listOf("amsterdam", "berlin"), imported.locations.map { it.storageId })
        assertEquals(
            LocationConfig.PROVIDER_TELEMOST,
            imported.locations[1].location.bypassProvider
        )
    }

    @Test
    fun migratesLegacyLocationsAndPreservesActiveSelection() = runTest {
        val source = FakeLocationsDataSource(
            legacy = listOf(
                "legacy_a" to """{"name":"A","server":"room-a","password":"key-a","provider":"jazz"}""",
                "legacy_b" to """{"name":"B","server":"room-b","password":"key-b","turn":{"type":"wbstream"}}"""
            ),
            legacyActive = "legacy_b"
        )
        val bundle = LocationsRepositoryImpl(source).getBundle()

        assertEquals("legacy_b", bundle.activeLocationId)
        assertEquals(listOf("legacy_a", "legacy_b"), bundle.locations.map { it.storageId })
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, bundle.locations[1].location.bypassProvider)
        assertEquals(bundle, source.stored)
    }

    @Test
    fun normalizesStoredWbStreamAliasToCanonicalProvider() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "wb",
                locations = listOf(
                    LocationEntry(
                        storageId = "wb",
                        name = "WB",
                        legacyId = "room-wb",
                        legacyKey = "key-wb",
                        legacyBypassProvider = "wbstream"
                    )
                )
            )
        )

        val active = LocationsRepositoryImpl(source).getActiveLocation()

        assertNotNull(active)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, active.bypassProvider)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, active.location.bypassProvider)
    }

    @Test
    fun importsWbStreamAliasAsCanonicalProvider() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "version": 3,
              "active_location_id": "wb",
              "locations": [
                {
                  "storage_id": "wb",
                  "name": "WB",
                  "id": "room-wb",
                  "key": "key-wb",
                  "bypass_provider": "wbstream"
                }
              ]
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, imported.locations.first().bypassProvider)
    }

    @Test
    fun importsSingleLegacyLocationWithTurnProvider() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "hysteria": {
                "name": "Paris",
                "server": "room-paris",
                "password": "key-paris"
              },
              "turn": {
                "type": "telemost"
              }
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals("imported_paris", imported.activeLocationId)
        assertEquals(1, imported.locations.size)
        assertEquals("room-paris", imported.locations.first().location.id)
        assertEquals(
            LocationConfig.PROVIDER_TELEMOST,
            imported.locations.first().location.bypassProvider
        )
    }

    @Test
    fun importsLegacyOlcRtcUriWithClientIdAndMimoName() = runTest {
        val source = FakeLocationsDataSource()
        val input = "olcrtc://wbstream?seichannel@room-01#${"a".repeat(64)}%android-01${'$'}RU / olc free sub / IPv6"

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val entry = imported.locations.single()
        val location = entry.location
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_SEICHANNEL, location.transport)
        assertEquals("room-01", location.id)
        assertEquals("RU / olc free sub / IPv6", location.name)
        assertEquals("RU / olc free sub / IPv6", entry.metadata?.mimo)
        assertNull(entry.metadata?.subscription)
    }

    @Test
    fun importsJitsiOlcRtcUriWithRoomUrl() = runTest {
        val source = FakeLocationsDataSource()
        val key = "b".repeat(64)
        val input = "olcrtc://jitsi?datachannel@https://meet.cryptopro.ru/myroom#$key${'$'}Jitsi room"

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val location = imported.locations.single().location
        assertEquals(LocationConfig.PROVIDER_JITSI, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_DATACHANNEL, location.transport)
        assertEquals("https://meet.cryptopro.ru/myroom", location.id)
        assertEquals("Jitsi room", location.name)
    }

    @Test
    fun importsOlcRtcSubscriptionAndAppliesLocalNames() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            #name: Test subscription
            #update: 1778011200
            #refresh: 10m
            #color: #4A90E2
            #icon: flag-ru
            #used: 10mb/10gb
            #available: 9.99gb

            olcrtc://wbstream?seichannel@room-01#${"a".repeat(64)}%android-01${'$'}RU / default name
            ##name: RU-1
            ##color: #4A90E2
            ##icon: node-ru
            ##used: 500mb/10gb
            ##available: 9.5gb
            ##ip: 203.0.113.10
            ##comment: primary

            olcrtc://jazz?datachannel@room-02#${"b".repeat(64)}%android-02${'$'}DE / backup
            ##name: DE-Backup
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("RU-1", "DE-Backup"), imported.locations.map { it.location.name })
        assertEquals(
            listOf(LocationConfig.PROVIDER_WB_STREAM, LocationConfig.PROVIDER_JAZZ),
            imported.locations.map { it.location.bypassProvider }
        )
        assertEquals("imported_ru-1", imported.activeLocationId)

        val firstMetadata = imported.locations[0].metadata
        assertNotNull(firstMetadata)
        assertEquals("RU-1", firstMetadata.name)
        assertEquals("#4A90E2", firstMetadata.color)
        assertEquals("node-ru", firstMetadata.icon)
        assertEquals("500mb/10gb", firstMetadata.used)
        assertEquals("9.5gb", firstMetadata.available)
        assertEquals("203.0.113.10", firstMetadata.ip)
        assertEquals("primary", firstMetadata.comment)
        assertEquals("RU / default name", firstMetadata.mimo)

        val subscriptionMetadata = firstMetadata.subscription
        assertNotNull(subscriptionMetadata)
        assertEquals("Test subscription", subscriptionMetadata.name)
        assertEquals("1778011200", subscriptionMetadata.update)
        assertEquals("10m", subscriptionMetadata.refresh)
        assertEquals("#4A90E2", subscriptionMetadata.color)
        assertEquals("flag-ru", subscriptionMetadata.icon)
        assertEquals("10mb/10gb", subscriptionMetadata.used)
        assertEquals("9.99gb", subscriptionMetadata.available)
        assertEquals(subscriptionMetadata, imported.locations[1].metadata?.subscription)
    }

    fun importUpdatesMatchingStorageIdsAndAppendsNewLocations() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "custom_paris",
                locations = listOf(
                    LocationEntry.from(
                        "custom_paris",
                        LocationConfig(
                            name = "Paris",
                            id = "room-old",
                            key = "a".repeat(64),
                            bypassProvider = LocationConfig.PROVIDER_WB_STREAM
                        )
                    ),
                    LocationEntry.from(
                        "custom_berlin",
                        LocationConfig(
                            name = "Berlin",
                            id = "room-berlin",
                            key = "b".repeat(64),
                            bypassProvider = LocationConfig.PROVIDER_TELEMOST
                        )
                    )
                )
            )
        )
        val input = """
            {
              "version": 4,
              "active_location_id": "sub_wb",
              "locations": [
                {
                  "storage_id": "custom_paris",
                  "name": "Paris updated",
                  "endpoint": {
                    "room_id": "room-new",
                    "key": "${"c".repeat(64)}",
                    "client_id": "phone-1"
                  },
                  "carrier": "wbstream",
                  "transport": {
                    "type": "datachannel"
                  }
                },
                {
                  "storage_id": "sub_wb",
                  "name": "WB sub",
                  "subscription_url": "https://example.com/sub.md",
                  "endpoint": {
                    "room_id": "room-sub",
                    "key": "${"d".repeat(64)}",
                    "client_id": "phone-2"
                  },
                  "carrier": "wbstream",
                  "transport": {
                    "type": "vp8channel"
                  }
                }
              ]
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("custom_paris", "custom_berlin", "sub_wb"), imported.locations.map { it.storageId })
        assertEquals("room-new", imported.locations[0].location.id)
        assertEquals("room-berlin", imported.locations[1].location.id)
        assertEquals("https://example.com/sub.md", imported.locations[2].subscriptionUrl)
        assertEquals("sub_wb", imported.activeLocationId)
    }

    @Test
    fun invalidLocationCannotBecomeActiveLocation() = runTest {
        val source = FakeLocationsDataSource()
        val incomplete = LocationConfig(name = "Broken", id = "room", key = "")

        LocationsRepositoryImpl(source).saveLocation("broken", incomplete)

        val bundle = source.stored
        assertNotNull(bundle)
        assertNull(bundle.activeLocationId)
        assertTrue(bundle.locations.isEmpty())
    }

    @Test
    fun telemostLocationsForceVp8AndOtherProvidersCanUseDatachannel() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "version": 3,
              "locations": [
                {
                  "storage_id": "telemost",
                  "name": "Telemost",
                  "id": "75047680642749",
                  "key": "${"a".repeat(64)}",
                  "bypass_provider": "telemost",
                  "transport": "datachannel"
                },
                {
                  "storage_id": "wb",
                  "name": "WB",
                  "id": "room-wb",
                  "key": "${"b".repeat(64)}",
                  "bypass_provider": "wbstream",
                  "transport": "datachannel"
                }
              ]
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, imported.locations[0].location.transport)
        assertEquals(LocationConfig.TRANSPORT_DATACHANNEL, imported.locations[1].location.transport)
    }

    @Test
    fun importsExportsAndPreservesSubscriptionRoutingPolicy() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "version": 5,
              "active_location_id": "ru",
              "locations": [
                {
                  "storage_id": "ru",
                  "name": "RU",
                  "endpoint": {
                    "room_id": "room-ru",
                    "key": "${"e".repeat(64)}"
                  },
                  "auth_provider": "wbstream",
                  "transport": {
                    "type": "vp8channel"
                  },
                  "routing": {
                    "split_tunnel": "bypass_selected",
                    "dat_lists": [
                      {
                        "name": "ru-sites",
                        "url": "https://example.test/geosite.dat",
                        "categories": ["geosite:ru", " geosite:vk ", "geosite:ru"],
                        "action": "bypass"
                      }
                    ],
                    "rules": [
                      {
                        "type": "domain_suffix",
                        "value": "youtube.com",
                        "action": "proxy"
                      },
                      {
                        "type": "ip_cidr",
                        "value": "10.0.0.0/8",
                        "action": "bypass"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val repository = LocationsRepositoryImpl(source)
        repository.importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val routing = imported.locations.single().routing
        assertNotNull(routing)
        assertEquals(RoutingSplitTunnelMode.BypassSelected, routing.splitTunnel)
        assertEquals(listOf("geosite:ru", "geosite:vk"), routing.datLists.single().categories)
        assertEquals(RoutingRuleAction.Bypass, routing.datLists.single().action)
        assertEquals(RoutingRuleType.DomainSuffix, routing.rules[0].type)
        assertEquals("youtube.com", routing.rules[0].value)
        assertEquals(RoutingRuleType.IpCidr, routing.rules[1].type)

        val exported = repository.exportBundle()
        assertTrue("\"routing\"" in exported)
        assertTrue("\"dat_lists\"" in exported)

        repository.saveLocation(
            storageId = "ru",
            location = LocationConfig(
                name = "RU updated",
                id = "room-ru",
                key = "f".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_WB_STREAM
            )
        )

        val updatedRouting = source.stored?.locations?.single()?.routing
        assertNotNull(updatedRouting)
        assertEquals(RoutingSplitTunnelMode.BypassSelected, updatedRouting.splitTunnel)
        assertEquals("https://example.test/geosite.dat", updatedRouting.datLists.single().url)
    }

    @Test
    fun importsUnsupportedVideochannelAsDefaultTransport() = runTest {
        val source = FakeLocationsDataSource()
        val input = """
            {
              "version": 4,
              "active_location_id": "telemost-video",
              "locations": [
                {
                  "storage_id": "telemost-video",
                  "name": "Telemost Video",
                  "endpoint": {
                    "room_id": "75047680642749",
                    "key": "${"c".repeat(64)}"
                  },
                  "carrier": "telemost",
                  "transport": {
                    "type": "videochannel"
                  }
                }
              ]
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        val location = imported.locations.first().location
        assertEquals(5, imported.version)
        assertEquals(LocationConfig.PROVIDER_TELEMOST, location.bypassProvider)
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, location.transport)
    }

    @Test
    fun exposesAllWorkingProviderTransportPairs() {
        assertEquals(
            listOf(
                LocationConfig.TRANSPORT_VP8CHANNEL,
                LocationConfig.TRANSPORT_SEICHANNEL
            ),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_TELEMOST)
        )
        assertEquals(
            listOf(
                LocationConfig.TRANSPORT_DATACHANNEL,
                LocationConfig.TRANSPORT_VP8CHANNEL,
                LocationConfig.TRANSPORT_SEICHANNEL
            ),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_JAZZ)
        )
        assertEquals(
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_JAZZ),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_WB_STREAM)
        )
        assertEquals(
            listOf(LocationConfig.TRANSPORT_DATACHANNEL),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_JITSI)
        )
        assertEquals(
            LocationConfig.TRANSPORT_DATACHANNEL,
            LocationConfig.normalizeTransport(LocationConfig.TRANSPORT_VP8CHANNEL, LocationConfig.PROVIDER_JITSI)
        )
    }

    @Test
    fun olcRtcSingleProfileImportDoesNotOverwriteExistingStorageId() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "imported_room-01",
                locations = listOf(
                    LocationEntry.from(
                        "imported_room-01",
                        LocationConfig("Old", "room-old", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM)
                    )
                )
            )
        )

        LocationsRepositoryImpl(source).importText(
            "olcrtc://wbstream?seichannel@room-01#${"b".repeat(64)}${'$'}New"
        )

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("imported_room-01", "imported_new"), imported.locations.map { it.storageId })
        assertEquals("room-old", imported.locations[0].location.id)
        assertEquals("room-01", imported.locations[1].location.id)
        assertEquals("imported_new", imported.activeLocationId)
    }

    @Test
    fun bundleRestoreUpdatesMatchingStorageIds() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "same",
                locations = listOf(
                    LocationEntry.from(
                        "same",
                        LocationConfig("Old", "room-old", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM)
                    )
                )
            )
        )

        val input = """
            {
              "version": 4,
              "active_location_id": "same",
              "locations": [
                {
                  "storage_id": "same",
                  "name": "Updated",
                  "endpoint": {
                    "room_id": "room-new",
                    "key": "${"b".repeat(64)}",
                    "client_id": "desktop"
                  },
                  "carrier": "wbstream",
                  "transport": {"type": "datachannel"}
                }
              ]
            }
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(input)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(listOf("same"), imported.locations.map { it.storageId })
        assertEquals("room-new", imported.locations.single().location.id)
    }

    @Test
    fun subscriptionHeaderSetsIntervalAndIdentityHeaders() = runTest {
        var userAgent: String? = null
        var hwid: String? = null
        val engine = MockEngine { request ->
            userAgent = request.headers[HttpHeaders.UserAgent]
            hwid = request.headers["x-hwid"]
            respond(
                content = "olcrtc://wbstream?vp8channel@room#${"c".repeat(64)}${'$'}Sub",
                headers = headersOf("profile-update-interval", "6")
            )
        }
        val source = FakeLocationsDataSource()

        LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).importText("https://example.test/sub.txt")

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(CurrentAppInfo.userAgent, userAgent)
        assertEquals("hwid-test", hwid)
        assertEquals(6, imported.locations.single().metadata?.subscription?.updateIntervalHours)
        assertEquals("https://example.test/sub.txt", imported.locations.single().subscriptionUrl)
    }

    @Test
    fun subscriptionImportFallsBackWhenIdentityResponseIsNotConfig() = runTest {
        val userAgents = mutableListOf<String?>()
        val hwids = mutableListOf<String?>()
        val engine = MockEngine { request ->
            userAgents += request.headers[HttpHeaders.UserAgent]
            hwids += request.headers["x-hwid"]
            if (userAgents.size == 1) {
                respond("<html>blocked</html>")
            } else {
                respond(
                    content = "\uFEFFolcrtc://wbstream?vp8channel@room#${"d".repeat(64)}${'$'}Fallback",
                    headers = headersOf("profile-update-interval", "12")
                )
            }
        }
        val source = FakeLocationsDataSource()

        val imported = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).importText("http://example.test/sub.txt")

        val bundle = source.stored
        assertTrue(imported)
        assertNotNull(bundle)
        assertEquals(2, userAgents.size)
        assertEquals(CurrentAppInfo.userAgent, userAgents[0])
        assertEquals("hwid-test", hwids[0])
        assertTrue(userAgents[1]?.startsWith("Mozilla/5.0") == true)
        assertNull(hwids[1])
        assertEquals("room", bundle.locations.single().location.id)
        assertEquals(12, bundle.locations.single().metadata?.subscription?.updateIntervalHours)
        assertEquals("http://example.test/sub.txt", bundle.locations.single().subscriptionUrl)
    }

    @Test
    fun refreshSingleSubscriptionPreservesOtherSubscriptions() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "beta",
                locations = listOf(
                    LocationEntry.from(
                        "alpha",
                        LocationConfig("Alpha", "room-alpha", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                        subscriptionUrl = "https://example.test/alpha"
                    ),
                    LocationEntry.from(
                        "beta",
                        LocationConfig("Beta", "room-beta", "b".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                        subscriptionUrl = "https://example.test/beta"
                    )
                )
            )
        )
        val engine = MockEngine { request ->
            respond("olcrtc://wbstream?vp8channel@room-alpha-new#${"c".repeat(64)}${'$'}Alpha")
        }

        val updated = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).refreshSubscription("https://example.test/alpha")

        val bundle = source.stored
        assertEquals(1, updated)
        assertNotNull(bundle)
        assertEquals(listOf("beta", "imported_alpha"), bundle.locations.map { it.storageId })
        assertEquals("room-beta", bundle.locations.first { it.storageId == "beta" }.location.id)
        assertEquals("https://example.test/beta", bundle.locations.first { it.storageId == "beta" }.subscriptionUrl)
        assertEquals("room-alpha-new", bundle.locations.first { it.subscriptionUrl == "https://example.test/alpha" }.location.id)
        assertEquals("beta", bundle.activeLocationId)
    }

    @Test
    fun failedSingleSubscriptionRefreshDoesNotDropExistingSubscription() = runTest {
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "alpha",
                locations = listOf(
                    LocationEntry.from(
                        "alpha",
                        LocationConfig("Alpha", "room-alpha", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
                        subscriptionUrl = "https://example.test/alpha"
                    )
                )
            )
        )
        val engine = MockEngine {
            respond("<html>not a config</html>")
        }

        val updated = LocationsRepositoryImpl(
            dataSource = source,
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid-test")
        ).refreshSubscription("https://example.test/alpha")

        val bundle = source.stored
        assertEquals(0, updated)
        assertNotNull(bundle)
        assertEquals(listOf("alpha"), bundle.locations.map { it.storageId })
        assertEquals("room-alpha", bundle.locations.single().location.id)
        assertEquals("alpha", bundle.activeLocationId)
    }

    @Test
    fun configShareRoundTripsTransportOptions() = runTest {
        val source = FakeLocationsDataSource()
        val config = LocationConfig(
            name = "Shared",
            id = "room",
            key = "d".repeat(64),
            bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
            transport = LocationConfig.TRANSPORT_VP8CHANNEL,
            vp8Fps = 48,
            vp8Batch = 32
        )

        val shared = ConfigShareService.olcRtcUri(config)
        assertTrue("%" !in shared)

        LocationsRepositoryImpl(source).importText(shared)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(48, imported.locations.single().location.vp8Fps)
        assertEquals(32, imported.locations.single().location.vp8Batch)
    }

    @Test
    fun subscriptionSharingListsDistinctUrls() {
        val first = LocationEntry.from(
            "first",
            LocationConfig("First", "room-a", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/a"
        )
        val second = LocationEntry.from(
            "second",
            LocationConfig("Second", "room-b", "b".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/b"
        )
        val third = LocationEntry.from(
            "third",
            LocationConfig("Third", "room-c", "c".repeat(64), LocationConfig.PROVIDER_WB_STREAM),
            subscriptionUrl = "https://example.test/a"
        )

        val items = ConfigShareService.subscriptionShareItems(listOf(first, second, third))

        assertEquals(listOf("https://example.test/a", "https://example.test/b"), items.map { it.url })
        assertEquals(2, items.first().locationCount)
        assertEquals("https://example.test/b", ConfigShareService.subscriptionQrText(items[1].url))
    }

    private class FakeLocationsDataSource(
        var stored: LocationBundleV4? = null,
        private val legacy: List<Pair<String, String>> = emptyList(),
        private val legacyActive: String? = null
    ) : LocationsDataSource {

        override suspend fun loadLocationBundle(): LocationBundleV4? = stored

        override suspend fun saveLocationBundle(bundle: LocationBundleV4) {
            stored = bundle
        }

        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = legacy

        override suspend fun loadLegacyActiveLocationId(): String? = legacyActive
    }

    private class StaticIdentityProvider(
        private val value: String
    ) : DeviceIdentityProvider {
        override suspend fun hwid(): String = value
    }
}
