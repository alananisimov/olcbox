package org.olcbox.daemon.ipc

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpcSecretTest {

    private lateinit var root: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ipc-secret-test")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::delete)
    }

    @Test
    fun getOrCreateSecretGeneratesOwnerOnlyKeyFile() {
        val dir = root.resolve(IpcSecret.USER_FOLDER_NAME)

        val keyFile = IpcSecret.getOrCreateSecret(dir)

        assertTrue(Files.exists(keyFile))
        assertEquals(IpcSecret.KEY_FILE_NAME, keyFile.fileName.toString())
        assertTrue(IpcSecret.isOwnerOnly(keyFile))
    }

    @Test
    fun getOrCreateSecretIsIdempotent() {
        val dir = root.resolve(IpcSecret.USER_FOLDER_NAME)

        val first = IpcSecret.readSecret(IpcSecret.getOrCreateSecret(dir))
        val second = IpcSecret.readSecret(IpcSecret.getOrCreateSecret(dir))

        assertEquals(first, second)
    }

    @Test
    fun hasExpectedStructureAcceptsCanonicalLayout() {
        val dir = root.resolve(IpcSecret.USER_FOLDER_NAME)
        val keyFile = IpcSecret.getOrCreateSecret(dir)

        assertTrue(IpcSecret.hasExpectedStructure(keyFile))
    }

    @Test
    fun hasExpectedStructureRejectsWrongFileName() {
        val dir = root.resolve(IpcSecret.USER_FOLDER_NAME)
        Files.createDirectories(dir)
        val decoy = dir.resolve("not-the-key")
        Files.writeString(decoy, "secret")

        assertFalse(IpcSecret.hasExpectedStructure(decoy))
    }

    @Test
    fun hasExpectedStructureRejectsWrongParentDir() {
        val dir = root.resolve("not-dot-olcbox")
        Files.createDirectories(dir)
        val decoy = dir.resolve(IpcSecret.KEY_FILE_NAME)
        Files.writeString(decoy, "secret")

        assertFalse(IpcSecret.hasExpectedStructure(decoy))
    }

    @Test
    fun isOwnerOnlyRejectsWorldReadableFile() {
        val dir = root.resolve(IpcSecret.USER_FOLDER_NAME)
        Files.createDirectories(dir)
        val keyFile = dir.resolve(IpcSecret.KEY_FILE_NAME)
        Files.writeString(keyFile, "secret")
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-r--r--"))

        assertFalse(IpcSecret.isOwnerOnly(keyFile))
    }

    @Test
    fun isOwnerOnlyRejectsMissingFile() {
        val missing = root.resolve(IpcSecret.USER_FOLDER_NAME).resolve(IpcSecret.KEY_FILE_NAME)

        assertFalse(IpcSecret.isOwnerOnly(missing))
    }
}
