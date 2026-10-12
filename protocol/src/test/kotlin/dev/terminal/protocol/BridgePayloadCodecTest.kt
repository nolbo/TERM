package dev.terminal.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgePayloadCodecTest {
    @Test
    fun roundTripsGuestBridgeRequestsAndResponses() {
        val request = BridgeRequest.PostNotification("build", "completed")
        val response = BridgeResponse.Text("clipboard text")

        assertEquals(request, BridgePayloadCodec.decodeRequest(BridgePayloadCodec.encodeRequest(request)))
        assertEquals(response, BridgePayloadCodec.decodeResponse(BridgePayloadCodec.encodeResponse(response)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTrailingBridgeData() {
        BridgePayloadCodec.decodeRequest(byteArrayOf(1, 0))
    }
}
