package dev.terminal.runtime.rootless

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessDnsPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun androidIsTheDefaultAndUsesOnlyTheNetworkServers() {
        assertEquals(RootlessDnsPolicy.DEFAULT, RootlessDnsPolicy.parse("android", "ignored"))
        val config = RootlessDnsPolicy.DEFAULT.resolverConfig(listOf("192.0.2.53", "2001:db8::53", "192.0.2.53"))
        assertEquals("# Managed by TERM Userspace Linux\nnameserver 192.0.2.53\nnameserver 2001:db8::53\n", config)
        assertFalse(RootlessDnsPolicy.DEFAULT.resolverConfig(emptyList()).contains("nameserver"))
    }

    @Test fun customUsesValidatedLiteralsAndIgnoresAndroidServers() {
        val policy = RootlessDnsPolicy.parse("custom", "192.0.2.53,\n2001:db8::53 192.0.2.53")
        assertEquals(listOf("192.0.2.53", "2001:db8::53"), policy.servers)
        assertFalse(policy.resolverConfig(listOf("198.51.100.53")).contains("198.51.100.53"))
    }

    @Test fun invalidCustomInputAndDirectiveInjectionAreRejected() {
        for (input in listOf("", "dns.example.com", "999.1.2.3", "127.1", "001.2.3.4", "2001:::1",
            "1.2.3.4\nsearch example.com", "1.1.1.1 2.2.2.2 3.3.3.3 4.4.4.4", "::1%eth0")) {
            assertTrue(input, runCatching { RootlessDnsPolicy.parse("custom", input) }.isFailure)
        }
        assertTrue(runCatching { RootlessDnsPolicy.parse("environment", "1.1.1.1") }.isFailure)
    }

    @Test fun resolverUpdatesReplaceLinksWithoutWritingTheirTargets() {
        val directory = temporary.newFolder("runtime-tmp")
        val unrelated = temporary.newFile("unrelated").apply { writeText("preserve") }
        Files.createSymbolicLink(File(directory, "term-resolv.conf").toPath(), unrelated.toPath())
        val resolver = writeRootlessResolverFile(directory, "nameserver 192.0.2.53\n")
        assertEquals("preserve", unrelated.readText())
        assertFalse(Files.isSymbolicLink(resolver.toPath()))
        assertEquals("nameserver 192.0.2.53\n", resolver.readText())
        writeRootlessResolverFile(directory, "nameserver 198.51.100.53\n")
        assertEquals("nameserver 198.51.100.53\n", resolver.readText())
    }
}
