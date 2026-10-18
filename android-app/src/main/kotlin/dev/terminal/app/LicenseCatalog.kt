package dev.terminal.app

import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject

/** Original component/document paths are stable aliases, never physical assets. */
internal class LicenseCatalog private constructor(
    private val documents: Map<String, String>,
    private val openAsset: (String) -> InputStream,
) {
    data class Entry(val path: String, val directory: Boolean)

    val documentPaths: List<String> get() = documents.keys.toList()

    fun isDocument(path: String): Boolean = path in documents

    fun list(path: String): List<Entry> {
        require(isLogicalPath(path) && !isDocument(path)) { "Not a license directory: $path" }
        val prefix = "$path/"
        val children = documents.keys.asSequence().filter { it.startsWith(prefix) }
            .map { prefix + it.removePrefix(prefix).substringBefore('/') }.distinct().sorted()
            .map { Entry(it, it !in documents) }.toList()
        require(children.isNotEmpty()) { "Unknown license directory: $path" }
        return children
    }

    fun readBytes(path: String): ByteArray {
        val digest = requireNotNull(documents[path]) { "Unknown license document: $path" }
        val body = openAsset("licenses/blobs/$digest.txt").use { it.readBytes() }
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(actualDigest == digest) { "Corrupt license document: $path" }
        return body
    }

    fun readText(path: String): String = readBytes(path).toString(Charsets.UTF_8)

    companion object {
        fun isLogicalPath(path: String): Boolean = path == "licenses" ||
            path.startsWith("licenses/") && '\\' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        fun load(openAsset: (String) -> InputStream): LicenseCatalog {
            val index = JSONObject(openAsset("licenses/index.json").bufferedReader().use { it.readText() })
            require(index.getInt("version") == 1) { "Unsupported license catalog version" }
            val entries = index.getJSONArray("documents")
            require(entries.length() > 0) { "Empty license catalog" }
            val documents = sortedMapOf<String, String>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONArray(i)
                require(entry.length() == 2) { "Invalid license catalog entry" }
                val path = entry.getString(0)
                val digest = entry.getString(1)
                require(path != "licenses" && isLogicalPath(path)) { "Invalid license path: $path" }
                require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid license body hash" }
                require(documents.put(path, digest) == null) { "Duplicate license path: $path" }
            }
            for (path in documents.keys) {
                var parent = path.substringBeforeLast('/')
                while (parent != "licenses") {
                    require(parent !in documents) { "License is both file and directory: $parent" }
                    parent = parent.substringBeforeLast('/')
                }
            }
            return LicenseCatalog(documents, openAsset)
        }
    }
}
