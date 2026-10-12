package dev.terminal.runtime.usb

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import dev.terminal.runtime.usb.TrustedIphoneToolPolicy.Phase
import dev.terminal.runtime.usb.TrustedIphoneToolPolicy.Error

class TrustedIphoneToolPolicyTest {
    @Test fun toolRequestsRejectSecretsDescriptorsAndArbitraryOperations() {
        val fields = setOf("version", "token", "op", "action")
        TrustedIphoneToolPolicy.validateRequest("list", fields)
        TrustedIphoneToolPolicy.validateRequest("check", fields + "device")
        for (forbidden in listOf("password", "twoFactorCode", "pairRecord", "privateKey", "fd", "argv", "path", "port", "url", "tool")) {
            assertTrue(runCatching { TrustedIphoneToolPolicy.validateRequest("check", fields + setOf("device", forbidden)) }.isFailure)
        }
        for (operation in listOf("install", "exec", "usbmuxd", "ReadPairRecord", "mux-owner")) {
            assertTrue(runCatching { TrustedIphoneToolPolicy.validateRequest(operation, fields) }.isFailure)
        }
        assertTrue(runCatching { TrustedIphoneToolPolicy.validateRequest("check", fields) }.isFailure)
    }
    private val phases = listOf(Phase.WAITING_FOR_USB_PERMISSION, Phase.CONNECTING, Phase.RUNNING, Phase.COMPLETED)
    @Test fun cancellationAndDetachAtEveryStageAndTerminalStateIsImmutable() {
        for (index in phases.indices) {
            for (error in listOf(Error.CANCELED, Error.USB_DETACHED)) {
                val state = TrustedIphoneToolStateMachine()
                phases.take(index).forEach { state.transition(it) }
                assertEquals(if (index == 0) Phase.IDLE else phases[index - 1], state.phase)
                state.stop(error)
                assertEquals(if (error == Error.CANCELED) Phase.CANCELED else Phase.FAILED, state.phase)
                assertTrue(runCatching { state.transition(Phase.COMPLETED) }.isFailure)
            }
        }
        val state = TrustedIphoneToolStateMachine()
        assertTrue(runCatching { state.transition(Phase.RUNNING) }.isFailure)
        phases.forEach { state.transition(it) }; state.stop(Error.CANCELED)
        assertEquals(Phase.COMPLETED, state.phase)
    }
    @Test fun boundedWorkerLinesFailClosedOnTruncationAndOversize() {
        assertNull(TrustedIphoneToolHost.line(ByteArrayInputStream(byteArrayOf())))
        assertEquals("{}", TrustedIphoneToolHost.line(ByteArrayInputStream("{}\n".toByteArray())))
        listOf("truncated", "x".repeat(8193) + "\n").forEach {
            assertTrue(runCatching { TrustedIphoneToolHost.line(ByteArrayInputStream(it.toByteArray())) }.isFailure)
        }
    }
    @Test fun noFollowCleanupNeverDeletesUnrelatedHostData() {
        val root = Files.createTempDirectory("iphone-tools-cleanup").toFile()
        val outside = Files.createTempDirectory("iphone-tools-outside").toFile()
        try {
            val secret = java.io.File(outside, "key").apply { writeText("secret") }
            val operation = java.io.File(root, "operation").apply { mkdir() }
            Files.createSymbolicLink(java.io.File(operation, "escape").toPath(), outside.toPath())
            TrustedIphoneToolPolicy.deleteTemporary(root, operation)
            assertTrue(secret.isFile); assertFalse(operation.exists())
            assertTrue(runCatching { TrustedIphoneToolPolicy.deleteTemporary(root, root) }.isFailure)
            assertTrue(runCatching { TrustedIphoneToolPolicy.deleteTemporary(root, outside) }.isFailure)
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
}
