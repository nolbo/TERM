package dev.terminal.runtime.session

/** One session's input destination, pinned before asynchronous dispatch.
 * owner is an opaque token shared only by sessions on the same registered controller.
 */
internal class SessionInputTarget(
    val owner: Any,
    val sessionId: String,
    private val current: () -> Boolean,
    private val deliver: suspend (ByteArray) -> Result<Int>,
) {
    fun isCurrent(): Boolean = current()

    suspend fun write(bytes: ByteArray): Result<Int> = if (isCurrent()) deliver(bytes)
        else Result.failure(IllegalStateException("세션 연결이 바뀌었거나 종료되었습니다"))
}
