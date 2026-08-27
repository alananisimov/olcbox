package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondRedirect
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.formatSubscriptionRefreshInterval
import org.olcbox.app.data.model.parseSubscriptionRefreshIntervalMs
import org.olcbox.app.data.repository.LocationImportFailureKind
import org.olcbox.app.data.repository.LocationImportResult
import org.olcbox.app.data.share.ConfigShareService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
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
    fun jitsiVp8ImportRemainsVp8AndIsMarkedExperimental() = runTest {
        val source = FakeLocationsDataSource()
        val config = LocationConfig(
            name = "Jitsi VP8",
            id = "https://meet.example.test/room",
            key = "e".repeat(64),
            bypassProvider = LocationConfig.PROVIDER_JITSI,
            transport = LocationConfig.TRANSPORT_VP8CHANNEL,
            vp8Fps = 30,
            vp8Batch = 16
        )

        LocationsRepositoryImpl(source).importText(ConfigShareService.olcRtcUri(config))

        val imported = assertNotNull(source.stored).locations.single().location
        assertEquals(LocationConfig.TRANSPORT_VP8CHANNEL, imported.transport)
        assertEquals(30, imported.vp8Fps)
        assertEquals(16, imported.vp8Batch)
        assertEquals("VP8 (Experimental)", imported.transportName())
    }

    @Test
    fun jitsiConfigWithoutTransportKeepsDataChannelDefault() = runTest {
        val source = FakeLocationsDataSource()
        LocationsRepositoryImpl(source).importText(
            """
                {
                  "version": 5,
                  "locations": [
                    {
                      "storage_id": "jitsi",
                      "name": "Jitsi",
                      "endpoint": {
                        "room_id": "https://meet.example.test/room",
                        "key": "${"a".repeat(64)}"
                      },
                      "auth_provider": "jitsi"
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals(
            LocationConfig.TRANSPORT_DATACHANNEL,
            source.stored?.locations?.single()?.location?.transport
        )
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

        val importResult = LocationsRepositoryImpl(source).importTextDetailed(input)
        assertIs<LocationImportResult.Success>(importResult, importResult.toString())

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
        assertEquals(10L * 60L * 1_000L, subscriptionMetadata.effectiveUpdateIntervalMs())
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
            listOf(
                LocationConfig.TRANSPORT_DATACHANNEL,
                LocationConfig.TRANSPORT_VP8CHANNEL,
                LocationConfig.TRANSPORT_SEICHANNEL
            ),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_JITSI)
        )
        assertEquals(
            LocationConfig.TRANSPORT_VP8CHANNEL,
            LocationConfig.normalizeTransport(LocationConfig.TRANSPORT_VP8CHANNEL, LocationConfig.PROVIDER_JITSI)
        )
        assertEquals(
            LocationConfig.TRANSPORT_DATACHANNEL,
            LocationConfig.normalizeTransport("", LocationConfig.PROVIDER_JITSI)
        )
        assertEquals(
            "VP8 (Experimental)",
            LocationConfig.transportDisplayName(
                LocationConfig.TRANSPORT_VP8CHANNEL,
                LocationConfig.PROVIDER_JITSI
            )
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
        assertEquals(
            6L * SubscriptionMetadata.HOUR_MS,
            imported.locations.single().metadata?.subscription?.effectiveUpdateIntervalMs()
        )
        assertEquals("https://example.test/sub.txt", imported.locations.single().subscriptionUrl)
    }

    @Test
    fun subscriptionImportFollowsHttpRedirect() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests += 1
            if (request.url.encodedPath == "/start") {
                respondRedirect("https://example.test/final")
            } else {
                respond("olcrtc://wbstream?vp8channel@room#${"a".repeat(64)}${'$'}Redirected")
            }
        }
        val source = FakeLocationsDataSource()

        val result = LocationsRepositoryImpl(source, HttpClient(engine))
            .importTextDetailed("https://example.test/start")

        assertIs<LocationImportResult.Success>(result)
        assertEquals(2, requests)
        assertEquals("room", source.stored?.locations?.single()?.location?.id)
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
        ).importText("https://example.test/sub.txt")

        val bundle = source.stored
        assertTrue(imported)
        assertNotNull(bundle)
        assertEquals(2, userAgents.size)
        assertEquals(CurrentAppInfo.userAgent, userAgents[0])
        assertEquals("hwid-test", hwids[0])
        assertTrue(userAgents[1]?.startsWith("Mozilla/5.0") == true)
        assertNull(hwids[1])
        assertEquals("room", bundle.locations.single().location.id)
        assertEquals(
            12L * SubscriptionMetadata.HOUR_MS,
            bundle.locations.single().metadata?.subscription?.effectiveUpdateIntervalMs()
        )
        assertEquals("https://example.test/sub.txt", bundle.locations.single().subscriptionUrl)
    }

    @Test
    fun insecureSubscriptionSettingIsPersistedAndUsedForRefresh() = runTest {
        val url = "http://example.test/sub.txt"
        val insecureClientRequests = mutableListOf<Boolean>()
        val source = FakeLocationsDataSource()
        val repository = LocationsRepositoryImpl(
            dataSource = source,
            subscriptionHttpClientFactory = { _, allowInsecureRequests ->
                insecureClientRequests += allowInsecureRequests
                HttpClient(
                    MockEngine {
                        respond("olcrtc://wbstream?vp8channel@room#${"e".repeat(64)}${'$'}Insecure")
                    }
                )
            }
        )

        val blocked = repository.importTextDetailed(url)
        assertIs<LocationImportResult.Failure>(blocked)
        assertEquals(LocationImportFailureKind.InvalidUrl, blocked.kind)

        val imported = repository.importTextDetailed(
            text = url,
            allowInsecureRequests = true
        )
        assertIs<LocationImportResult.Success>(imported)
        assertTrue(
            source.stored?.locations?.single()
                ?.metadata?.subscription?.allowInsecureRequests == true
        )

        assertEquals(1, repository.refreshSubscription(url))
        assertEquals(listOf(true, true), insecureClientRequests)
        assertTrue(
            source.stored?.locations?.single()
                ?.metadata?.subscription?.allowInsecureRequests == true
        )
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
    fun subscriptionRefreshPreservesLocalDnsOverride() = runTest {
        val url = "https://example.test/sub"
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "sub",
                locations = listOf(
                    LocationEntry.from(
                        "sub",
                        LocationConfig(
                            "Sub",
                            "room",
                            "a".repeat(64),
                            dnsServer = "9.9.9.9:53"
                        ),
                        subscriptionUrl = url
                    )
                )
            )
        )
        val engine = MockEngine {
            respond("olcrtc://wbstream?vp8channel@room#${"a".repeat(64)}${'$'}Sub")
        }

        LocationsRepositoryImpl(source, HttpClient(engine)).refreshSubscription(url)

        assertEquals("sub", source.stored?.locations?.single()?.storageId)
        assertEquals("9.9.9.9:53", source.stored?.locations?.single()?.location?.dnsServer)
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
        assertEquals(1, bundle.locations.single().metadata?.subscription?.consecutiveRefreshFailures)
    }

    @Test
    fun subscriptionHeaderTakesPrecedenceOverRefreshDirective() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    #refresh: 10m
                    olcrtc://wbstream?vp8channel@room#${"c".repeat(64)}${'$'}Sub
                """.trimIndent(),
                headers = headersOf("profile-update-interval", "6")
            )
        }
        val source = FakeLocationsDataSource()

        LocationsRepositoryImpl(source, HttpClient(engine)).importText("https://example.test/sub")

        assertEquals(
            6L * SubscriptionMetadata.HOUR_MS,
            source.stored?.locations?.single()?.metadata?.subscription?.effectiveUpdateIntervalMs()
        )
    }

    @Test
    fun detailedImportReportsHttpAndUnsupportedInput() = runTest {
        val engine = MockEngine {
            respond("failure", status = HttpStatusCode.BadGateway)
        }
        val repository = LocationsRepositoryImpl(FakeLocationsDataSource(), HttpClient(engine))

        val httpFailure = assertIs<LocationImportResult.Failure>(
            repository.importTextDetailed("https://example.test/sub")
        )
        assertEquals(LocationImportFailureKind.Http, httpFailure.kind)
        assertTrue("502" in httpFailure.message)

        val formatFailure = assertIs<LocationImportResult.Failure>(
            repository.importTextDetailed("this is not a configuration")
        )
        assertEquals(LocationImportFailureKind.UnsupportedFormat, formatFailure.kind)

        val schemeFailure = assertIs<LocationImportResult.Failure>(
            repository.importTextDetailed("ftp://example.test/sub")
        )
        assertEquals(LocationImportFailureKind.InvalidUrl, schemeFailure.kind)

        val invalidUrlFailure = assertIs<LocationImportResult.Failure>(
            repository.importTextDetailed("https://")
        )
        assertEquals(LocationImportFailureKind.InvalidUrl, invalidUrlFailure.kind)
    }

    @Test
    fun detailedImportReportsTlsTimeoutAndEmptyResponse() = runTest {
        suspend fun failureFrom(engine: MockEngine): LocationImportResult.Failure {
            val repository = LocationsRepositoryImpl(
                FakeLocationsDataSource(),
                HttpClient(engine)
            )
            return assertIs<LocationImportResult.Failure>(
                repository.importTextDetailed("https://example.test/sub")
            )
        }

        assertEquals(
            LocationImportFailureKind.Tls,
            failureFrom(MockEngine { error("SSL certificate validation failed") }).kind
        )
        assertEquals(
            LocationImportFailureKind.Timeout,
            failureFrom(MockEngine { error("request timed out") }).kind
        )
        assertEquals(
            LocationImportFailureKind.EmptyResponse,
            failureFrom(MockEngine { respond("") }).kind
        )
    }

    @Test
    fun subscriptionRetryBackoffAndManualIntervalAreApplied() {
        val failed = SubscriptionMetadata(
            updateIntervalMs = 10L * 60L * 1_000L,
            manualUpdateIntervalMs = 2L * SubscriptionMetadata.HOUR_MS,
            lastRefreshAttemptAtEpochMs = 1_000L,
            consecutiveRefreshFailures = 2
        )

        assertEquals(2L * SubscriptionMetadata.HOUR_MS, failed.effectiveUpdateIntervalMs())
        assertEquals(
            1_000L + SubscriptionMetadata.retryDelayMs(2),
            failed.nextRefreshAtEpochMs()
        )
    }

    @Test
    fun subscriptionManualOverrideCanBeSetAndCleared() = runTest {
        val url = "https://example.test/sub"
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "sub",
                locations = listOf(
                    LocationEntry.from(
                        storageId = "sub",
                        location = LocationConfig("Sub", "room", "f".repeat(64)),
                        subscriptionUrl = url,
                        metadata = LocationMetadata(
                            subscription = SubscriptionMetadata(updateIntervalMs = 10L * 60L * 1_000L)
                        )
                    )
                )
            )
        )
        val repository = LocationsRepositoryImpl(source)

        repository.setSubscriptionUpdateInterval(url, 2L * SubscriptionMetadata.HOUR_MS)
        var subscription = source.stored?.locations?.single()?.metadata?.subscription
        assertEquals(2L * SubscriptionMetadata.HOUR_MS, subscription?.effectiveUpdateIntervalMs())

        repository.setSubscriptionUpdateInterval(url, null)
        subscription = source.stored?.locations?.single()?.metadata?.subscription
        assertNull(subscription?.manualUpdateIntervalMs)
        assertEquals(10L * 60L * 1_000L, subscription?.effectiveUpdateIntervalMs())
    }

    @Test
    fun reimportingSameSubscriptionUpdatesGroupWithoutDuplicates() = runTest {
        val url = "https://example.test/sub"
        var requestCount = 0
        val engine = MockEngine {
            requestCount += 1
            val body = if (requestCount == 1) {
                """
                    #refresh: 10m
                    olcrtc://wbstream?vp8channel@room-a#${"a".repeat(64)}${'$'}Alpha
                    olcrtc://wbstream?vp8channel@room-b#${"b".repeat(64)}${'$'}Beta
                """.trimIndent()
            } else {
                """
                    #refresh: 20m
                    olcrtc://wbstream?vp8channel@room-a#${"a".repeat(64)}${'$'}Alpha updated
                    olcrtc://wbstream?vp8channel@room-c#${"c".repeat(64)}${'$'}Gamma
                """.trimIndent()
            }
            respond(body)
        }
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "custom",
                locations = listOf(
                    LocationEntry.from(
                        "custom",
                        LocationConfig("Custom", "custom-room", "d".repeat(64))
                    )
                )
            )
        )
        val repository = LocationsRepositoryImpl(source, HttpClient(engine))

        repository.importTextDetailed(url)
        repository.setSubscriptionUpdateInterval(url, 2L * SubscriptionMetadata.HOUR_MS)
        val alphaStorageId = source.stored
            ?.locations
            ?.first { it.location.id == "room-a" }
            ?.storageId

        repository.importTextDetailed(url)

        val bundle = assertNotNull(source.stored)
        assertEquals(3, bundle.locations.size)
        assertEquals(2, bundle.locations.count { it.subscriptionUrl == url })
        assertEquals(listOf("custom-room", "room-a", "room-c"), bundle.locations.map { it.location.id })
        assertEquals(
            alphaStorageId,
            bundle.locations.first { it.location.id == "room-a" }.storageId
        )
        assertEquals(
            2L * SubscriptionMetadata.HOUR_MS,
            bundle.locations.first { it.location.id == "room-a" }
                .metadata?.subscription?.manualUpdateIntervalMs
        )
        assertEquals(
            "room-a",
            bundle.locations.first { it.storageId == bundle.activeLocationId }.location.id
        )
    }

    @Test
    fun unsupportedMobileTransportFallsBackToProviderSafeDefault() {
        val mobileTransports = listOf(
            LocationConfig.TRANSPORT_DATACHANNEL,
            LocationConfig.TRANSPORT_VP8CHANNEL
        )

        assertEquals(
            LocationConfig.TRANSPORT_VP8CHANNEL,
            LocationConfig.fallbackTransportForProvider(
                LocationConfig.PROVIDER_WB_STREAM,
                mobileTransports
            )
        )
        assertEquals(
            LocationConfig.TRANSPORT_VP8CHANNEL,
            LocationConfig.fallbackTransportForProvider(
                LocationConfig.PROVIDER_TELEMOST,
                mobileTransports
            )
        )
        assertEquals(
            LocationConfig.TRANSPORT_DATACHANNEL,
            LocationConfig.fallbackTransportForProvider(
                LocationConfig.PROVIDER_JITSI,
                mobileTransports
            )
        )
    }

    @Test
    fun subscriptionRefreshInputUsesSupportedRange() {
        assertEquals(10L * 60L * 1_000L, parseSubscriptionRefreshIntervalMs("10m"))
        assertEquals(6L * SubscriptionMetadata.HOUR_MS, parseSubscriptionRefreshIntervalMs("6h"))
        assertEquals("1d", formatSubscriptionRefreshInterval(24L * SubscriptionMetadata.HOUR_MS))
        assertNull(parseSubscriptionRefreshIntervalMs("4m"))
        assertNull(parseSubscriptionRefreshIntervalMs("31d"))
    }

    @Test
    fun deletingSubscriptionRemovesAllItsLocationsAndReselectsActiveLocation() = runTest {
        val deletedUrl = "https://example.test/deleted"
        val remainingUrl = "https://example.test/remaining"
        val source = FakeLocationsDataSource(
            stored = LocationBundleV4(
                activeLocationId = "deleted-2",
                locations = listOf(
                    LocationEntry.from(
                        "deleted-1",
                        LocationConfig("Deleted 1", "room-1", "a".repeat(64)),
                        subscriptionUrl = deletedUrl
                    ),
                    LocationEntry.from(
                        "deleted-2",
                        LocationConfig("Deleted 2", "room-2", "b".repeat(64)),
                        subscriptionUrl = deletedUrl
                    ),
                    LocationEntry.from(
                        "remaining",
                        LocationConfig("Remaining", "room-3", "c".repeat(64)),
                        subscriptionUrl = remainingUrl
                    ),
                    LocationEntry.from(
                        "custom",
                        LocationConfig("Custom", "room-4", "d".repeat(64))
                    )
                )
            )
        )

        val removed = LocationsRepositoryImpl(source).deleteSubscription(deletedUrl)

        assertEquals(2, removed)
        assertEquals(listOf("remaining", "custom"), source.stored?.locations?.map { it.storageId })
        assertEquals("remaining", source.stored?.activeLocationId)
    }

    @Test
    fun customDnsIsValidatedAndImportedFromLegacyJson() = runTest {
        assertTrue(LocationConfig.isValidDnsServer(""))
        assertTrue(LocationConfig.isValidDnsServer("1.1.1.1:53"))
        assertTrue(LocationConfig.isValidDnsServer("dns.example.test:5353"))
        assertTrue(LocationConfig.isValidDnsServer("[2001:4860:4860::8888]:53"))
        assertTrue(!LocationConfig.isValidDnsServer("2001:4860:4860::8888:53"))
        assertTrue(!LocationConfig.isValidDnsServer("https://dns.example.test:53"))

        val source = FakeLocationsDataSource()
        val repository = LocationsRepositoryImpl(source)
        repository.importText(
            """
                {
                  "name": "DNS",
                  "server": "room",
                  "password": "${"d".repeat(64)}",
                  "provider": "wbstream",
                  "dns": "9.9.9.9:53"
                }
            """.trimIndent()
        )

        assertEquals("9.9.9.9:53", source.stored?.locations?.single()?.location?.dnsServer)

        val restoredSource = FakeLocationsDataSource()
        LocationsRepositoryImpl(restoredSource).importText(repository.exportBundle())
        assertEquals(
            "9.9.9.9:53",
            restoredSource.stored?.locations?.single()?.location?.dnsServer
        )
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
