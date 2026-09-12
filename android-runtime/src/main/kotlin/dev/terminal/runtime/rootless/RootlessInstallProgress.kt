package dev.terminal.runtime.rootless

/** Progress is local to one distribution and one preparation attempt, never an estimated total. */
data class RootlessProgressUpdate(
    val phase: RootlessPhase,
    val detail: String,
    val fraction: Float? = null,
    val completedBytes: Long? = null,
    val totalBytes: Long? = null,
)

data class RootlessInstallProgress(
    val update: RootlessProgressUpdate,
    val startedAtElapsedMs: Long,
    val phaseStartedAtElapsedMs: Long,
    val updatedAtElapsedMs: Long,
    val finishedAtElapsedMs: Long? = null,
)

/** Retain every update for reconnects; limit IPC without dropping stage transitions or completion. */
internal class RootlessProgressTracker(private val now: () -> Long) {
    @Volatile var latest: RootlessInstallProgress? = null
        private set
    private var lastEmittedAt = Long.MIN_VALUE

    @Synchronized fun reset() { latest = null; lastEmittedAt = Long.MIN_VALUE }

    @Synchronized fun record(update: RootlessProgressUpdate): Boolean {
        val time = now()
        val old = latest
        val safe = update.copy(
            detail = update.detail.filter { !it.isISOControl() }.take(240),
            fraction = update.fraction?.takeIf { it.isFinite() && it in 0f..1f },
        )
        latest = RootlessInstallProgress(safe, old?.startedAtElapsedMs ?: time,
            if (old?.update?.phase == safe.phase) old.phaseStartedAtElapsedMs else time, time)
        val emit = old == null || old.update.phase != safe.phase || (safe.fraction == 1f && old.update.fraction != 1f) || time - lastEmittedAt >= 250
        if (emit) lastEmittedAt = time
        return emit
    }

    @Synchronized fun finish() { latest = latest?.copy(finishedAtElapsedMs = now()) }
}

/** Bounded line framing for pipe output, including carriage-return progress and hostile long lines. */
internal class RootlessOutputLines(private val onLine: (String) -> Unit) {
    private val pending = StringBuilder()
    private var overflow = false
    fun accept(chars: CharArray, count: Int) {
        for (index in 0 until count) {
            val char = chars[index]
            if (char == '\n' || char == '\r') flush()
            else if (pending.length < 1024) pending.append(char) else overflow = true
        }
    }
    fun flush() {
        if (!overflow && pending.isNotEmpty()) onLine(pending.toString())
        pending.setLength(0)
        overflow = false
    }
}

/** Only recognized package-manager status is shown. Raw host paths and arbitrary script output are not UI. */
internal class RootlessPackageProgress(
    initialPhase: RootlessPhase,
    private val reporter: RootlessProgressReporter,
) {
    private var phase = initialPhase
    private var operation = initialPhase
    val currentPhase: RootlessPhase get() = phase

    private fun emit(next: RootlessPhase, detail: String, fraction: Float? = null) {
        phase = next
        reporter.update(RootlessProgressUpdate(next, detail, fraction))
    }

    fun line(raw: String) {
        val line = raw.trim()
        if (line.startsWith("TERMINAL_SETUP_PHASE:")) {
            val next = runCatching { RootlessPhase.valueOf(line.substringAfter(':')) }.getOrNull() ?: return
            if (next !in scriptPhases) return
            operation = next
            emit(next, next.displayName)
            return
        }
        // apt's documented APT::Status-Fd format: kind:package/count:percent:description.
        if (line.startsWith("pmstatus:") || line.startsWith("dlstatus:")) {
            val match = aptStatus.matchEntire(line) ?: return
            val fraction = match.groupValues[3].toFloatOrNull()?.div(100)?.takeIf { it.isFinite() && it in 0f..1f }
            if (match.groupValues[1] == "dlstatus") {
                val stage = if (operation == RootlessPhase.PACKAGE_INDEX) operation else RootlessPhase.PACKAGE_DOWNLOAD
                emit(stage, if (stage == RootlessPhase.PACKAGE_INDEX) "저장소 목록을 내려받고 있습니다" else "패키지를 내려받고 있습니다", fraction)
            } else {
                val pkg = match.groupValues[2].takeIf { packageName.matches(it) }
                val stage = if (operation == RootlessPhase.PACKAGE_UPGRADE) operation else RootlessPhase.PACKAGE_INSTALL
                val action = when {
                    match.groupValues[4].startsWith("Unpacking") -> "압축 해제"
                    match.groupValues[4].startsWith("Configuring") -> "설정 적용"
                    match.groupValues[4].startsWith("Installed") -> "설치 완료"
                    else -> "설치 처리"
                }
                emit(stage, if (pkg == null) stage.displayName else "$pkg · $action", fraction)
            }
            return
        }
        when {
            line.contains("Waiting for cache lock") || line.contains("Could not get lock") ->
                emit(phase, "다른 패키지 작업의 잠금 해제를 기다리고 있습니다")
            line.startsWith(":: Synchronizing package databases") -> emit(RootlessPhase.PACKAGE_INDEX, "저장소 목록을 갱신하고 있습니다")
            line.startsWith(":: Starting full system upgrade") -> emit(RootlessPhase.PACKAGE_PLAN, "전체 업그레이드 대상과 의존성을 확인하고 있습니다")
            line.startsWith(":: Retrieving packages") -> emit(RootlessPhase.PACKAGE_DOWNLOAD, "패키지를 내려받고 있습니다")
            line.startsWith("checking keys in keyring") || line.startsWith("checking package integrity") ||
                line.startsWith("loading package files") || line.startsWith("checking for file conflicts") ->
                emit(RootlessPhase.PACKAGE_CHECK, "패키지 서명·무결성·파일 충돌을 확인하고 있습니다")
            line.startsWith(":: Processing package changes") -> emit(RootlessPhase.PACKAGE_INSTALL, "패키지를 설치하고 있습니다")
            line.startsWith(":: Running post-transaction hooks") || line.startsWith("Processing triggers for ") ->
                emit(RootlessPhase.PACKAGE_HOOKS, "패키지 후처리를 실행하고 있습니다")
            else -> {
                if (operation == RootlessPhase.PACKAGE_RECOVERY) dpkgRecovery.matchEntire(line)?.let { match ->
                    emit(operation, "${match.groupValues[1]} · 이전 설치 설정 복구")
                }
                pacmanPackage.matchEntire(line)?.let { match ->
                    emit(RootlessPhase.PACKAGE_INSTALL, "${match.groupValues[2]} · ${if (match.groupValues[1] == "upgrading") "업그레이드" else "설치"}")
                }
                pacmanDownload.matchEntire(line)?.let { match ->
                    emit(phase, "${match.groupValues[1]} · 다운로드 중")
                }
            }
        }
    }

    companion object {
        private val aptStatus = Regex("^(pmstatus|dlstatus):(.+?):([0-9.]+):(.*)$")
        private val packageName = Regex("[a-zA-Z0-9][a-zA-Z0-9+_.:-]{0,127}")
        private val pacmanPackage = Regex("^(upgrading|installing|reinstalling) ([a-zA-Z0-9][a-zA-Z0-9+_.-]{0,127})\\.\\.\\.$")
        private val pacmanDownload = Regex("^([a-zA-Z0-9][a-zA-Z0-9+_.:-]{0,127}) downloading\\.\\.\\.$")
        private val dpkgRecovery = Regex("^(?:Setting up|Unpacking) ([a-zA-Z0-9][a-zA-Z0-9+_.:-]{0,127})(?: .*)?$")
        private val scriptPhases = setOf(RootlessPhase.PACKAGE_INDEX, RootlessPhase.PACKAGE_RECOVERY,
            RootlessPhase.PACKAGE_PLAN, RootlessPhase.PACKAGE_INSTALL, RootlessPhase.PACKAGE_UPGRADE,
            RootlessPhase.PACKAGE_CHECK, RootlessPhase.LOCALE, RootlessPhase.TIMEZONE,
            RootlessPhase.VERIFY_PACKAGES, RootlessPhase.VERIFY_COMMANDS, RootlessPhase.VERIFY_LOCALE)
    }
}
