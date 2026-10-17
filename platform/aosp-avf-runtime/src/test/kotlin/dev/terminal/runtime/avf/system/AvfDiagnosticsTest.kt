package dev.terminal.runtime.avf.system

import org.junit.Assert.*
import org.junit.Test

class AvfDiagnosticsTest {
    private class Probe : AvfDiagnosticProbe {
        var sdk = 35
        var feature = true
        var manage = true
        var custom = true
        var manager = true
        var driver = true
        var featureFailure: Throwable? = null
        var imageFailure: Throwable? = null
        var managerFailure: Throwable? = null
        var driverFailure: Throwable? = null
        var driverPresenceFailure: Throwable? = null
        var sharingFailure: Throwable? = null
        val calls = mutableListOf<String>()
        override fun sdk() = sdk
        override fun feature(): Boolean { featureFailure?.let { throw it }; return feature }
        override fun permission(name: String) = if (name == MANAGE_VM_PERMISSION) manage else custom
        override fun customImageApi() { calls += "image-api"; imageFailure?.let { throw it } }
        override fun manager(): Boolean { calls += "manager"; managerFailure?.let { throw it }; return manager }
        override fun driverPackaged(): Boolean { driverPresenceFailure?.let { throw it }; return driver }
        override fun driverCompatible() { calls += "driver-abi"; driverFailure?.let { throw it } }
        override fun sharedDirectoryApi() { calls += "sharing-api"; sharingFailure?.let { throw it } }
    }
    private fun AvfDiagnostics.check(condition: AvfCondition) = checks.single { it.condition == condition }

    @Test fun everyPrerequisiteMustPassBeforeAnySystemApiIsCalled() {
        val missing: List<(Probe) -> Unit> = listOf({ it.sdk = 34 }, { it.feature = false }, { it.manage = false }, { it.custom = false })
        for (configure in missing) {
            val probe = Probe().also(configure)
            val result = collectAvfDiagnostics(probe)
            assertEquals(8, result.checks.size)
            assertEquals(AvfCheckStatus.UNSATISFIED, result.status)
            assertTrue(probe.calls.isEmpty())
            for (condition in listOf(AvfCondition.CUSTOM_IMAGE_API, AvfCondition.VM_MANAGER,
                AvfCondition.PLATFORM_DRIVER, AvfCondition.SHARED_DIRECTORY_API))
                assertEquals(AvfCheckStatus.UNKNOWN, result.check(condition).status)
        }
    }
    @Test fun missingPermissionDoesNotImplyUnsupportedSystemFeatureOrApi() {
        val result = collectAvfDiagnostics(Probe().apply { manage = false })
        assertEquals(AvfCheckStatus.SATISFIED, result.check(AvfCondition.SYSTEM_FEATURE).status)
        assertEquals(AvfCheckReason.PERMISSION_MISSING, result.check(AvfCondition.MANAGE_PERMISSION).reason)
        assertEquals(AvfCheckStatus.UNKNOWN, result.check(AvfCondition.CUSTOM_IMAGE_API).status)
    }
    @Test fun ambiguousHiddenApiAndAbiErrorsAreUnknownAndDoNotPreventManagerCheck() {
        for (failure in listOf(NoSuchMethodError("<init>()V"), IllegalAccessError("hidden API"), ClassNotFoundException("optional"))) {
            val probe = Probe().apply { imageFailure = failure }
            val result = collectAvfDiagnostics(probe)
            assertEquals(AvfCheckReason.API_ACCESS_OR_ABI, result.check(AvfCondition.CUSTOM_IMAGE_API).reason)
            assertEquals(AvfCheckStatus.UNKNOWN, result.status)
            assertEquals(AvfCheckStatus.SATISFIED, result.check(AvfCondition.VM_MANAGER).status)
            assertEquals(listOf("image-api", "manager"), probe.calls)
        }
    }
    @Test fun inspectionExceptionsAndMissingManagerNeverCrashOrPass() {
        val failedFeature = collectAvfDiagnostics(Probe().apply { featureFailure = IllegalStateException("service failure") })
        assertEquals(AvfCheckStatus.UNKNOWN, failedFeature.status)
        val denied = collectAvfDiagnostics(Probe().apply { managerFailure = SecurityException("denied") })
        assertEquals(AvfCheckStatus.UNSATISFIED, denied.status)
        assertEquals(AvfCheckReason.API_DENIED, denied.check(AvfCondition.VM_MANAGER).reason)
        val missing = collectAvfDiagnostics(Probe().apply { manager = false })
        assertEquals(AvfCheckReason.MANAGER_MISSING, missing.check(AvfCondition.VM_MANAGER).reason)
        assertEquals(AvfCheckStatus.UNSATISFIED, missing.status)
        val failedDriver = collectAvfDiagnostics(Probe().apply { driverFailure = UnsatisfiedLinkError("ABI") })
        assertEquals(AvfCheckStatus.UNKNOWN, failedDriver.status)
    }
    @Test fun missingPackagedDriverIsASeparateFailureEvenWithoutDeviceSupport() {
        val result = collectAvfDiagnostics(Probe().apply { feature = false; driver = false })
        assertEquals(AvfCheckReason.DRIVER_MISSING, result.check(AvfCondition.PLATFORM_DRIVER).reason)
    }
    @Test fun driverLinkageFailureIsUnknownAndExplainsBothPossibleCauses() {
        val probe = Probe().apply { feature = false; driverPresenceFailure = NoClassDefFoundError("optional API") }
        val result = collectAvfDiagnostics(probe)
        assertEquals(8, result.checks.size)
        assertEquals(AvfCheckStatus.UNKNOWN, result.check(AvfCondition.PLATFORM_DRIVER).status)
        assertEquals(AvfCheckReason.API_ACCESS_OR_ABI, result.check(AvfCondition.PLATFORM_DRIVER).reason)
        assertTrue(probe.calls.isEmpty())
    }
    @Test fun baseConditionsAndOptionalSharingAreReportedSeparately() {
        val probe = Probe()
        val result = collectAvfDiagnostics(probe)
        assertEquals(AvfCheckStatus.SATISFIED, result.status)
        assertEquals(AvfCondition.entries.toSet(), result.checks.map { it.condition }.toSet())
        // The probe contract cannot create/run a VM or alter an image/environment.
        assertEquals(listOf("image-api", "manager", "driver-abi", "sharing-api"), probe.calls)
        assertEquals(AvfCheckStatus.SATISFIED, AvfDiagnostics(result.checks.dropLast(1)).status)
        assertEquals(AvfCheckStatus.UNKNOWN,
            AvfDiagnostics(result.checks.filterNot { it.condition == AvfCondition.PLATFORM_DRIVER }).status)
    }

    @Test fun missingSharingApiDoesNotMisclassifyBasicVmSupport() {
        val detail = "No direct method <init> " + "constructor detail ".repeat(40) + "VirtualMachineCustomImageConfig\$SharedPath"
        val result = collectAvfDiagnostics(Probe().apply { sharingFailure = NoSuchMethodError(detail) })
        assertEquals(AvfCheckStatus.SATISFIED, result.status)
        val sharing = result.check(AvfCondition.SHARED_DIRECTORY_API)
        assertEquals(AvfCheckStatus.UNKNOWN, sharing.status)
        assertEquals(AvfCheckReason.API_ACCESS_OR_ABI, sharing.reason)
        assertEquals("NoSuchMethodError: $detail", sharing.detail)
    }

    @Test fun deniedSharingApiIsExplicitWithoutFailingBasicVmSupport() {
        val result = collectAvfDiagnostics(Probe().apply { sharingFailure = SecurityException("sharing denied") })
        assertEquals(AvfCheckStatus.SATISFIED, result.status)
        val sharing = result.check(AvfCondition.SHARED_DIRECTORY_API)
        assertEquals(AvfCheckStatus.UNSATISFIED, sharing.status)
        assertEquals(AvfCheckReason.API_DENIED, sharing.reason)
    }

    @Test fun failedBaseDriverSkipsTheSharingProbe() {
        val probe = Probe().apply { driverFailure = NoSuchMethodError("driver ABI") }
        val result = collectAvfDiagnostics(probe)
        assertEquals(AvfCheckStatus.UNKNOWN, result.status)
        assertEquals(AvfCheckReason.PREREQUISITES_REQUIRED, result.check(AvfCondition.SHARED_DIRECTORY_API).reason)
        assertFalse(probe.calls.contains("sharing-api"))
    }
}
