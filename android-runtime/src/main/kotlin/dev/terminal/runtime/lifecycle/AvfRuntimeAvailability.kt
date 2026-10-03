package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.api.RuntimeState
import dev.terminal.runtime.avf.AvfBackend

internal data class AvfRuntimeAvailability(val available: Boolean, val detail: String)

/** Only AVF owners contribute AVF readiness; a Native primary owner is independent. */
internal fun avfRuntimeAvailability(
    primary: RuntimeBackend,
    storageOwners: Iterable<RuntimeBackend>,
): AvfRuntimeAvailability {
    val states = (listOf(primary) + storageOwners).filterIsInstance<AvfBackend>()
        .map { it.observeState().value }
    if (states.any { it is RuntimeState.Ready || it is RuntimeState.Active || it is RuntimeState.Idle }) {
        return AvfRuntimeAvailability(true, "AVF 가상 머신이 준비되었습니다")
    }
    val pending = states.firstOrNull { it is RuntimeState.Preparing || it is RuntimeState.Booting }
    val state = pending ?: states.firstOrNull { it is RuntimeState.Failed } ?: states.firstOrNull()
    val detail = when (state) {
        RuntimeState.Preparing -> "AVF Debian 이미지를 준비하고 있습니다"
        RuntimeState.Booting -> "AVF Linux 가상 머신을 시작하고 있습니다"
        RuntimeState.Suspended -> "AVF 가상 머신이 일시 중단되었습니다"
        RuntimeState.Stopping -> "AVF 가상 머신을 종료하고 있습니다"
        is RuntimeState.Failed -> state.reason
        else -> "AVF 프로필을 선택하면 지원 여부와 권한을 확인하고 세션을 시작합니다"
    }
    return AvfRuntimeAvailability(false, detail)
}
