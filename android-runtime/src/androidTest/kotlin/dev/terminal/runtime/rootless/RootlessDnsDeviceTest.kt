package dev.terminal.runtime.rootless

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootlessDnsDeviceTest {
    @Test fun allDistributionsReadTheSamePolicyAndAndroidUsesTheLiveNetworkServers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RootlessDnsStore(context)
        val original = store.policy()
        val root = File(context.cacheDir, "dns-test-${java.util.UUID.randomUUID()}")
        try {
            store.save(RootlessDnsPolicy.parse("custom", "192.0.2.53\n2001:db8::53"))
            RootlessDistribution.entries.forEach { distribution ->
                val layout = RootlessDebianLayout(File(root, distribution.directory), distribution)
                val resolver = RootlessDnsStore(context).resolverFile(layout)
                assertEquals("# Managed by TERM Userspace Linux\nnameserver 192.0.2.53\nnameserver 2001:db8::53\n", resolver.readText())
                assertFalse(layout.rootfs.exists())
            }
            store.save(RootlessDnsPolicy.DEFAULT)
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val servers = connectivity.activeNetwork?.let { connectivity.getLinkProperties(it) }?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
            val resolver = store.resolverFile(RootlessDebianLayout(File(root, "android")))
            assertEquals(RootlessDnsPolicy.DEFAULT.resolverConfig(servers), resolver.readText())
        } finally {
            store.save(original)
            root.deleteRecursively()
        }
    }
}
