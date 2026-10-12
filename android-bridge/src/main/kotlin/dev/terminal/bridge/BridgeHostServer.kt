package dev.terminal.bridge

import dev.terminal.protocol.BridgePayloadCodec
import dev.terminal.protocol.BridgeRequest
import dev.terminal.protocol.BridgeResponse
import dev.terminal.protocol.FrameKind
import dev.terminal.protocol.FrameTransport
import dev.terminal.protocol.ProtocolErrorCode
import dev.terminal.protocol.ProtocolErrorCodec
import dev.terminal.protocol.ProtocolErrorPayload
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.protocol.Service
import dev.terminal.protocol.GuestWorkEvent
import dev.terminal.protocol.LifecyclePayloadCodec
import kotlinx.coroutines.flow.collect

/** Dispatches a dedicated, authenticated guest-agent transport into the broker. */
class BridgeHostServer(
    private val broker: AndroidCapabilityBroker,
    private val onGuestWork: (GuestWorkEvent) -> Unit = {},
) {
    suspend fun serve(transport: FrameTransport) {
        transport.incomingFrames().collect { frame ->
            when {
                frame.service == Service.LIFECYCLE && frame.kind == FrameKind.STREAM -> {
                    runCatching { LifecyclePayloadCodec.decode(frame.payload) }.onSuccess(onGuestWork)
                }
                frame.service == Service.BRIDGE && frame.kind == FrameKind.REQUEST -> transport.send(dispatch(frame))
            }
        }
    }

    private fun dispatch(frame: ProtocolFrame): ProtocolFrame = try {
        val result = when (val request = BridgePayloadCodec.decodeRequest(frame.payload)) {
            BridgeRequest.ClipboardGet -> broker.clipboardGet()
            is BridgeRequest.ClipboardSet -> broker.clipboardSet(request.text)
            is BridgeRequest.OpenUrl -> broker.openUrl(request.url)
            is BridgeRequest.PostNotification -> broker.postNotification(request.title, request.text)
            BridgeRequest.BatteryStatus -> broker.batteryStatus()
        }
        when (result) {
            is BridgeResult.Success<*> -> ProtocolFrame(
                service = Service.BRIDGE,
                kind = FrameKind.RESPONSE,
                requestId = frame.requestId,
                payload = BridgePayloadCodec.encodeResponse(
                    when (val value = result.value) {
                        is String -> BridgeResponse.Text(value)
                        else -> BridgeResponse.Acknowledged
                    },
                ),
            )
            is BridgeResult.Denied -> errorFrame(frame, ProtocolErrorCode.CAPABILITY_DENIED, result.reason)
            is BridgeResult.Invalid -> errorFrame(frame, ProtocolErrorCode.INVALID_ARGUMENT, result.reason)
            is BridgeResult.Unavailable -> errorFrame(frame, ProtocolErrorCode.HOST_ERROR, result.reason)
        }
    } catch (error: IllegalArgumentException) {
        errorFrame(frame, ProtocolErrorCode.INVALID_ARGUMENT, error.message ?: "Invalid bridge request")
    } catch (error: Throwable) {
        errorFrame(frame, ProtocolErrorCode.HOST_ERROR, "Host bridge request failed")
    }

    private fun errorFrame(frame: ProtocolFrame, code: ProtocolErrorCode, message: String): ProtocolFrame =
        ProtocolFrame(
            service = Service.BRIDGE,
            kind = FrameKind.ERROR,
            requestId = frame.requestId,
            payload = ProtocolErrorCodec.encode(ProtocolErrorPayload(code, message)),
        )
}
