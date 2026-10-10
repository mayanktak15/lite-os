package com.litetv.app.cache

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiskCacheTest {

    private lateinit var tempDir: File
    private lateinit var diskCache: DiskCache

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("litetv_test_cache").toFile()
        diskCache = DiskCache(ContextWrapper(null), tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun writeAndRead_atomicWritePersistsCorrectly() {
        val key = "test_key"
        val data = "Hello Lite TV World!"

        val writeSuccess = diskCache.write(key, data)
        assertTrue(writeSuccess)
        assertTrue(diskCache.exists(key))

        val readData = diskCache.read(key)
        assertEquals(data, readData)
    }

    @Test
    fun write_preservesBackupForRollback() {
        val key = "test_rollback"
        val firstVersion = "Version 1 Data"
        val secondVersion = "Version 2 Corrupted Or Bad Data"

        diskCache.write(key, firstVersion, preserveBackup = true)
        diskCache.write(key, secondVersion, preserveBackup = true)

        assertEquals(secondVersion, diskCache.read(key))
        assertEquals(firstVersion, diskCache.readBackup(key))

        val rollbackSuccess = diskCache.rollback(key)
        assertTrue(rollbackSuccess)
        assertEquals(firstVersion, diskCache.read(key))
    }

    @Test
    fun metadata_savesAndLoadsCorrectly() {
        val key = "meta_test"
        val metadata = CacheMetadata(
            etag = "\"33a64df551425fcc55e4d42a148795d9f25f89d4\"",
            lastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
            timestamp = 1761031680000L,
            sha256 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        )

        diskCache.saveMetadata(key, metadata)
        val loaded = diskCache.getMetadata(key)

        assertNotNull(loaded)
        assertEquals(metadata.etag, loaded!!.etag)
        assertEquals(metadata.lastModified, loaded.lastModified)
        assertEquals(metadata.timestamp, loaded.timestamp)
        assertEquals(metadata.sha256, loaded.sha256)
    }

    @Test
    fun computeSha256_computesConsistentDigest() {
        val input = "Test Playlist Content"
        val hash1 = diskCache.computeSha256(input)
        val hash2 = diskCache.computeSha256(input)

        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }

    @Test
    fun delete_removesFilesAndBackupsAndMetadata() {
        val key = "delete_test"
        diskCache.write(key, "Initial", preserveBackup = true)
        diskCache.write(key, "Updated", preserveBackup = true)
        diskCache.saveMetadata(key, CacheMetadata(etag = "123"))

        assertTrue(diskCache.exists(key))
        diskCache.delete(key)

        assertFalse(diskCache.exists(key))
        assertNull(diskCache.read(key))
        assertNull(diskCache.readBackup(key))
        assertNull(diskCache.getMetadata(key))
    }
}
