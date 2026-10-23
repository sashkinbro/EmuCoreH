package com.sbro.emucoreh.data.igdb

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BundledCatalogInstallerTest {
    @Test fun changedAssetReplacesInstalledDatabaseAndBadCopyPreservesIt() {
        val dir = java.nio.file.Files.createTempDirectory("catalog-install").toFile()
        try {
            val db = File(dir, "games.db").apply { writeText("old data") }
            val bytes = "SQLite format 3\u0000new catalog".toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            installBundledCatalog(db, digest) { ByteArrayInputStream(bytes) }
            assertArrayEquals(bytes, db.readBytes())
            assertTrue(runCatching { installBundledCatalog(db, "0".repeat(64)) { ByteArrayInputStream(bytes) } }.isFailure)
            assertArrayEquals(bytes, db.readBytes())
            installBundledCatalog(db, digest) { error("unchanged asset should not be copied") }
        } finally { dir.deleteRecursively() }
    }
}
