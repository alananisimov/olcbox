package org.olcbox.app.data.datasource

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JvmLocationsDataSourceImplTest {

    @Test
    fun storesLocationBundleInProvidedDirectory() = runTest {
        val dir = Files.createTempDirectory("olcbox-locations-test")
        val source = JvmLocationsDataSourceImpl(dir)
        val bundle = LocationBundleV4(
            activeLocationId = "desk",
            locations = listOf(
                LocationEntry.from(
                    "desk",
                    LocationConfig("Desktop", "room", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM)
                )
            )
        )

        source.saveLocationBundle(bundle)

        val loaded = source.loadLocationBundle()
        assertNotNull(loaded)
        assertEquals("desk", loaded.activeLocationId)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, loaded.locations.first().location.bypassProvider)
    }

    @Test
    fun storesDeviceIdentityInProvidedDirectory() = runTest {
        val dir = Files.createTempDirectory("olcbox-device-id-test")
        val source = JvmLocationsDataSourceImpl(dir)

        source.saveDeviceIdentity("install-test")

        assertEquals("install-test", source.loadDeviceIdentity())
    }

    @Test
    fun keepsFailoverRoomsAcrossASaveAndLoad() = runTest {
        val dir = Files.createTempDirectory("olcbox-failover-rooms-test")
        val source = JvmLocationsDataSourceImpl(dir)
        val bundle = LocationBundleV4(
            activeLocationId = "desk",
            locations = listOf(
                LocationEntry.from(
                    "desk",
                    LocationConfig(
                        name = "Desktop",
                        id = "11115586048655",
                        key = "a".repeat(64),
                        bypassProvider = LocationConfig.PROVIDER_TELEMOST,
                        failoverRoomIds = listOf("81055221156696")
                    )
                )
            )
        )

        source.saveLocationBundle(bundle)

        // The standby room has to survive storage: it is the only thing the client
        // can hand itself over to when the server retires the primary room.
        val loaded = source.loadLocationBundle()
        assertNotNull(loaded)
        assertEquals(
            listOf("81055221156696"),
            loaded.locations.first().location.failoverRoomIds
        )
        assertEquals(
            listOf("11115586048655", "81055221156696"),
            loaded.locations.first().location.failoverRooms()
        )
    }
}
