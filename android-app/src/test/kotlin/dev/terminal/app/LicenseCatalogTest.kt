package dev.terminal.app

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LicenseCatalogTest {
    private val notice = "Copyright Alice\nMIT license\n".toByteArray()
    private val digest = hash(notice)

    private fun hash(body: ByteArray) = MessageDigest.getInstance("SHA-256").digest(body)
        .joinToString("") { "%02x".format(it) }

    private fun index(vararg documents: Pair<String, String>, version: Int = 1): ByteArray =
        JSONObject().put("version", version).put("documents",
            JSONArray(documents.map { JSONArray(listOf(it.first, it.second)) })).toString().toByteArray()

    private fun catalog(index: ByteArray, body: ByteArray = notice): LicenseCatalog = LicenseCatalog.load {
        when (it) {
            "licenses/index.json" -> index.inputStream()
            "licenses/blobs/$digest.txt" -> body.inputStream()
            else -> error("Unexpected physical asset: $it")
        }
    }

    @Test fun originalComponentPathsAndHierarchyRemainDistinctWhenBodiesAreShared() {
        val catalog = catalog(index("licenses/z/LICENSE" to digest, "licenses/a.txt" to digest,
            "licenses/z/NOTICE" to digest, "licenses/java-resources/META-INF/lib/LICENSE.txt" to digest))
        assertEquals(listOf("licenses/a.txt", "licenses/java-resources", "licenses/z"), catalog.list("licenses").map { it.path })
        assertFalse(catalog.list("licenses").first().directory)
        assertTrue(catalog.list("licenses").last().directory)
        assertEquals(listOf("licenses/z/LICENSE", "licenses/z/NOTICE"), catalog.list("licenses/z").map { it.path })
        for (path in catalog.documentPaths) {
            assertTrue(catalog.isDocument(path))
            assertArrayEquals(notice, catalog.readBytes(path))
            assertEquals(notice.toString(Charsets.UTF_8), catalog.readText(path))
        }
        assertFalse(catalog.list("licenses").any { "blobs" in it.path || "index.json" in it.path })
    }

    @Test fun copyrightAndLineEndingDifferencesAreReadFromTheirOwnBodies() {
        val documents = listOf(notice, "Copyright Bob\nMIT license\n".toByteArray(),
            "Copyright Alice\r\nMIT license\r\n".toByteArray())
        val aliases = documents.mapIndexed { i, body -> "licenses/component-$i/LICENSE" to hash(body) }
        val catalog = LicenseCatalog.load { path ->
            if (path == "licenses/index.json") index(*aliases.toTypedArray()).inputStream()
            else documents.single { path == "licenses/blobs/${hash(it)}.txt" }.inputStream()
        }
        aliases.forEachIndexed { i, entry -> assertArrayEquals(documents[i], catalog.readBytes(entry.first)) }
    }

    @Test fun rejectsTraversalUnknownPathsAndPhysicalStoragePaths() {
        val catalog = catalog(index("licenses/a.txt" to digest))
        for (path in listOf("../licenses/a.txt", "licenses/../a", "licenses/./a", "licenses//a", "licenses/a/", "licenses/a\\b")) {
            assertFalse(path, LicenseCatalog.isLogicalPath(path))
            assertThrows(IllegalArgumentException::class.java) { catalog(index(path to digest)) }
        }
        for (path in listOf("licenses/missing", "licenses/index.json", "licenses/blobs/$digest.txt")) {
            assertThrows(IllegalArgumentException::class.java) { catalog.readBytes(path) }
            assertThrows(IllegalArgumentException::class.java) { catalog.list(path) }
        }
    }

    @Test fun rejectsBadVersionsHashesDuplicatePathsAndFileDirectoryConflicts() {
        for (input in listOf(index("licenses/a" to digest, version = 2), index(),
            index("licenses/a" to "../body"), index("licenses/a" to digest, "licenses/a" to digest),
            index("licenses/a" to digest, "licenses/a/b" to digest), index("licenses" to digest))) {
            assertThrows(IllegalArgumentException::class.java) { catalog(input) }
        }
    }

    @Test fun corruptOrMissingBodiesNeverDisplayAnUnrelatedNotice() {
        val catalog = catalog(index("licenses/a.txt" to digest), "different copyright".toByteArray())
        assertThrows(IllegalStateException::class.java) { catalog.readBytes("licenses/a.txt") }
        val missing = LicenseCatalog.load { path ->
            if (path == "licenses/index.json") index("licenses/a.txt" to digest).inputStream()
            else throw java.io.FileNotFoundException(path)
        }
        assertThrows(java.io.FileNotFoundException::class.java) { missing.readText("licenses/a.txt") }
    }
}
