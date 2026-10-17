package dev.terminal.runtime.avf.system

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AvfPlatformApiTest {
    @Test fun missingConstructorExplainsBothPolicyDenialAndAbiMismatchAndKeepsTheCause() {
        val original = NoSuchMethodError("No direct method <init>()V in class Landroid/system/virtualmachine/VirtualMachineCustomImageConfig\$Builder;")
        val failure = runCatching { requireAvfPlatformApi<Unit> { throw original } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertSame(original, failure!!.cause)
        assertTrue(failure.message.orEmpty().contains("호환되지 않거나"))
        assertTrue(failure.message.orEmpty().contains("숨김 API"))
        assertTrue(failure.message.orEmpty().contains("ADB로 두 AVF 권한"))
        assertTrue(failure.message.orEmpty().contains(original.message!!))
    }

    @Test fun absentPlatformImplementationHasTheSameActionableBoundaryError() {
        val original = NoClassDefFoundError("android/system/virtualmachine/VirtualMachineManager")
        val failure = runCatching { requireAvfPlatformApi<Unit> { throw original } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertSame(original, failure!!.cause)
    }

    @Test fun imageOrVmFailuresAreNotMisclassifiedAsPlatformApiFailures() {
        for (original in listOf(IOException("invalid image"), IllegalStateException("VM stopped"))) {
            assertSame(original, runCatching { requireAvfPlatformApi<Unit> { throw original } }.exceptionOrNull())
        }
    }

    @Test fun sharingLinkageFailureNamesTheOptionalApiAndKeepsItsCompleteCause() {
        for (original in listOf(NoSuchMethodError("No direct method <init> in VirtualMachineCustomImageConfig\$SharedPath"),
            IllegalAccessError("SharedPath: linking, denied"))) {
            val failure = runCatching {
                requireAvfPlatformApi { requireAvfSharedDirectoryApi<Unit> { throw original } }
            }.exceptionOrNull()!!
            assertTrue(failure.message.orEmpty().startsWith("AVF 내부저장소·공유 디렉토리"))
            assertTrue(failure.message.orEmpty().contains(original.message!!))
            assertSame(original, failure.cause)
        }
    }

    @Test fun sharingBoundaryPreservesSuccessAndNonLinkageFailures() {
        assertEquals("shared", requireAvfSharedDirectoryApi { "shared" })
        val original = SecurityException("permission denied")
        assertSame(original, runCatching { requireAvfSharedDirectoryApi<Unit> { throw original } }.exceptionOrNull())
    }
}
