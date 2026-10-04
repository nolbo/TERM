package dev.terminal.runtime.rootless

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** The runtime process is the only writer; the UI reads and saves through Binder. */
internal class RootlessDnsStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("userspace-linux", Context.MODE_PRIVATE)

    fun policy(): RootlessDnsPolicy {
        val saved = preferences.all
        return RootlessDnsPolicy.parse(saved["dns-mode"] as? String ?: RootlessDnsPolicy.ANDROID,
            saved["dns-servers"] as? String ?: "")
    }

    fun save(policy: RootlessDnsPolicy) {
        check(preferences.edit().putString("dns-mode", policy.mode)
            .putString("dns-servers", policy.servers.joinToString("\n")).commit()) { "DNS policy could not be saved" }
    }

    fun resolverFile(layout: RootlessDebianLayout): File {
        val policy = policy()
        val androidServers = if (policy.mode == RootlessDnsPolicy.ANDROID) {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity.activeNetwork?.let { connectivity.getLinkProperties(it) }?.dnsServers
                .orEmpty().mapNotNull { it.hostAddress }
        } else emptyList()
        return writeRootlessResolverFile(layout.tmp, policy.resolverConfig(androidServers))
    }
}

/** A bind overlays guest resolv.conf without rewriting the installed rootfs or following its links. */
internal fun writeRootlessResolverFile(directory: File, content: String): File {
    Files.createDirectories(directory.toPath())
    val target = File(directory, "term-resolv.conf")
    val temporary = Files.createTempFile(directory.toPath(), ".resolv-", ".tmp")
    try {
        Files.newBufferedWriter(temporary).use { it.write(content) }
        Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
    return target
}
