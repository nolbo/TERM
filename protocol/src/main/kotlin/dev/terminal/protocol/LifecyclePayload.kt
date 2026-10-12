package dev.terminal.protocol

/** Guest-reported lifecycle hints; the host still owns wake-lock policy. */
enum class GuestWorkEvent { WORK_ACTIVE, WORK_IDLE }

object LifecyclePayloadCodec {
    private const val ACTIVE: Byte = 1
    private const val IDLE: Byte = 2

    fun encode(event: GuestWorkEvent): ByteArray = byteArrayOf(
        when (event) {
            GuestWorkEvent.WORK_ACTIVE -> ACTIVE
            GuestWorkEvent.WORK_IDLE -> IDLE
        },
    )

    fun decode(payload: ByteArray): GuestWorkEvent {
        require(payload.size == 1) { "Lifecycle event must contain exactly one byte" }
        return when (payload[0]) {
            ACTIVE -> GuestWorkEvent.WORK_ACTIVE
            IDLE -> GuestWorkEvent.WORK_IDLE
            else -> throw IllegalArgumentException("Unknown lifecycle event")
        }
    }
}
