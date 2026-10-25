package dev.terminal.runtime.avf.system

enum class AvfCondition(val required: Boolean = true) {
    ANDROID_VERSION, SYSTEM_FEATURE, MANAGE_PERMISSION, CUSTOM_PERMISSION, CUSTOM_IMAGE_API, VM_MANAGER, PLATFORM_DRIVER,
}
enum class AvfCheckStatus { SATISFIED, UNSATISFIED, UNKNOWN }
enum class AvfCheckReason { OBSERVED, ANDROID_TOO_OLD, FEATURE_MISSING, PERMISSION_MISSING, PREREQUISITES_REQUIRED,
    API_ACCESS_OR_ABI, API_DENIED, INSPECTION_FAILED, MANAGER_MISSING, DRIVER_MISSING }

data class AvfCheck(val condition: AvfCondition, val status: AvfCheckStatus, val reason: AvfCheckReason,
    val detail: String? = null)
data class AvfDiagnostics(val checks: List<AvfCheck>) {
    val status: AvfCheckStatus get() {
        val required = checks.filter { it.condition.required }
        return when {
            required.any { it.status == AvfCheckStatus.UNSATISFIED } -> AvfCheckStatus.UNSATISFIED
            required.map { it.condition }.toSet() != AvfCondition.entries.filter { it.required }.toSet() ||
                required.any { it.status == AvfCheckStatus.UNKNOWN } -> AvfCheckStatus.UNKNOWN
            else -> AvfCheckStatus.SATISFIED
        }
    }
}

/** No VM, image, environment or backend operations are exposed to the diagnostic collector. */
internal interface AvfDiagnosticProbe {
    fun sdk(): Int
    fun feature(): Boolean
    fun permission(name: String): Boolean
    fun customImageApi()
    fun manager(): Boolean
    fun driverPackaged(): Boolean
    fun driverCompatible()
}

internal const val AVF_FEATURE = "android.software.virtualization_framework"
internal const val MANAGE_VM_PERMISSION = "android.permission.MANAGE_VIRTUAL_MACHINE"
internal const val CUSTOM_VM_PERMISSION = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"

internal fun collectAvfDiagnostics(probe: AvfDiagnosticProbe): AvfDiagnostics {
    val checks = mutableListOf<AvfCheck>()
    fun check(condition: AvfCondition, missing: AvfCheckReason, block: () -> Boolean): AvfCheck {
        val result = try {
            val passed = block()
            AvfCheck(condition, if (passed) AvfCheckStatus.SATISFIED else AvfCheckStatus.UNSATISFIED,
                if (passed) AvfCheckReason.OBSERVED else missing)
        } catch (failure: Throwable) {
            AvfCheck(condition, AvfCheckStatus.UNKNOWN, AvfCheckReason.INSPECTION_FAILED, failure.description())
        }
        checks += result
        return result
    }
    check(AvfCondition.ANDROID_VERSION, AvfCheckReason.ANDROID_TOO_OLD) { probe.sdk() >= 35 }
    check(AvfCondition.SYSTEM_FEATURE, AvfCheckReason.FEATURE_MISSING, probe::feature)
    check(AvfCondition.MANAGE_PERMISSION, AvfCheckReason.PERMISSION_MISSING) { probe.permission(MANAGE_VM_PERMISSION) }
    check(AvfCondition.CUSTOM_PERMISSION, AvfCheckReason.PERMISSION_MISSING) { probe.permission(CUSTOM_VM_PERMISSION) }
    val prerequisites = checks.all { it.status == AvfCheckStatus.SATISFIED }
    fun api(condition: AvfCondition, missing: AvfCheckReason, block: () -> Boolean) {
        checks += if (!prerequisites) AvfCheck(condition, AvfCheckStatus.UNKNOWN, AvfCheckReason.PREREQUISITES_REQUIRED)
        else try {
            val passed = block()
            AvfCheck(condition, if (passed) AvfCheckStatus.SATISFIED else AvfCheckStatus.UNSATISFIED,
                if (passed) AvfCheckReason.OBSERVED else missing)
        } catch (failure: Throwable) {
            val cause = if (failure is java.lang.reflect.InvocationTargetException) failure.targetException else failure
            val ambiguous = cause is LinkageError || cause is ReflectiveOperationException
            AvfCheck(condition, if (cause is SecurityException) AvfCheckStatus.UNSATISFIED else AvfCheckStatus.UNKNOWN,
                when { ambiguous -> AvfCheckReason.API_ACCESS_OR_ABI
                    cause is SecurityException -> AvfCheckReason.API_DENIED
                    else -> AvfCheckReason.INSPECTION_FAILED }, cause.description())
        }
    }
    api(AvfCondition.CUSTOM_IMAGE_API, AvfCheckReason.API_ACCESS_OR_ABI) { probe.customImageApi(); true }
    api(AvfCondition.VM_MANAGER, AvfCheckReason.MANAGER_MISSING, probe::manager)
    fun result(): AvfDiagnostics {
        return AvfDiagnostics(checks)
    }
    // Driver presence can be inspected without loading the optional Android API implementation.
    val packaged = try { probe.driverPackaged() } catch (failure: Throwable) {
        checks += AvfCheck(AvfCondition.PLATFORM_DRIVER, AvfCheckStatus.UNKNOWN,
            if (failure is LinkageError) AvfCheckReason.API_ACCESS_OR_ABI else AvfCheckReason.INSPECTION_FAILED,
            failure.description())
        return result()
    }
    if (!packaged) checks += AvfCheck(AvfCondition.PLATFORM_DRIVER, AvfCheckStatus.UNSATISFIED, AvfCheckReason.DRIVER_MISSING)
    else if (checks.any { it.status != AvfCheckStatus.SATISFIED }) checks += AvfCheck(AvfCondition.PLATFORM_DRIVER,
        AvfCheckStatus.UNKNOWN, AvfCheckReason.PREREQUISITES_REQUIRED)
    else api(AvfCondition.PLATFORM_DRIVER, AvfCheckReason.API_ACCESS_OR_ABI) { probe.driverCompatible(); true }
    return result()
}

private fun Throwable.description() = "${javaClass.simpleName}: ${message.orEmpty().take(2048)}"
