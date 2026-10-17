package dev.terminal.runtime.avf.system

/** ART reports blocked non-SDK methods as missing methods, just like ABI mismatches. */
internal inline fun <T> requireAvfPlatformApi(block: () -> T): T = try {
    block()
} catch (failure: LinkageError) {
    throw IllegalStateException(
        "AVF 맞춤 이미지 API가 기기와 호환되지 않거나 Android의 숨김 API 접근 정책에 의해 차단되었습니다. " +
            "ADB로 두 AVF 권한을 부여해도 숨김 API 접근은 별도로 허용되어야 합니다. " +
            "호환 AVF API와 플랫폼 접근이 허용된 제품 또는 개발 기기 설정이 필요합니다.\n" +
            "원래 오류: ${failure.message ?: failure.javaClass.simpleName}",
        failure,
    )
}

/** Storage export has its own optional ABI; basic custom-image support does not establish it. */
internal inline fun <T> requireAvfSharedDirectoryApi(block: () -> T): T = try {
    block()
} catch (failure: LinkageError) {
    throw IllegalStateException(
        "AVF 내부저장소·공유 디렉토리 연결에 필요한 SharedPath API를 사용할 수 없습니다. " +
            "기기의 공유 API 버전 또는 해당 API의 숨김 API 접근 정책을 확인해야 합니다.\n" +
            "원래 오류: ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
        failure,
    )
}
