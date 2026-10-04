package dev.terminal.runtime.avf.system

/** Keeps platform failures intact while explaining the fixed-TAP denial seen on some products. */
internal fun describeAvfNetworkFailure(failure: Throwable): Throwable {
    val messages = generateSequence(failure) { it.cause }.take(16)
        .mapNotNull { it.message }.joinToString("\n")
    val fixedTapDenied = messages.contains("avf_tap_fixed") &&
        messages.contains("TUNSETIFF") &&
        (messages.contains("EACCES") || messages.contains("Permission denied", ignoreCase = true))
    if (!fixedTapDenied) return failure
    return IllegalStateException(
        "AVF 네트워크용 고정 TAP 인터페이스(avf_tap_fixed) 연결이 거부되었습니다. " +
            "다른 AVF VM이 네트워크를 사용 중이면 기기의 고정 TAP 구현 때문에 두 번째 VM이 시작되지 않을 수 있습니다. " +
            "두 VM 모두 인터넷을 사용하려면 Android 플랫폼의 VM별 TAP·브리지 지원이 필요합니다.\n" +
            "원래 오류: ${failure.message ?: failure.javaClass.simpleName}",
        failure,
    )
}
