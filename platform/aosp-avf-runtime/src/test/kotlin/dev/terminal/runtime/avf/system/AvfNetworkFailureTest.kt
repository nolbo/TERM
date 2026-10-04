package dev.terminal.runtime.avf.system

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AvfNetworkFailureTest {
    private val tapDenial = "Failed to create TAP interface: avf_tap_fixed; Failed to ioctl TUNSETIFF; EACCES: Permission denied"

    @Test fun fixedTapDenialExplainsConcurrentNetworkingAndRetainsThePlatformCause() {
        val original = IllegalStateException("Failed to create a TAP interface with suffix 4242", IOException(tapDenial))
        val reported = describeAvfNetworkFailure(original)
        assertSame(original, reported.cause)
        assertTrue(reported.message.orEmpty().contains("고정 TAP"))
        assertTrue(reported.message.orEmpty().contains("두 번째 VM"))
        assertTrue(reported.message.orEmpty().contains("Android 플랫폼"))
        assertTrue(reported.message.orEmpty().contains("두 VM 모두 인터넷"))
        assertTrue(reported.message.orEmpty().contains(original.message!!))
    }

    @Test fun flattenedBinderStatusIsRecognizedWithoutRequiringAndroidExceptionClasses() {
        val original = IllegalStateException(tapDenial)
        assertSame(original, describeAvfNetworkFailure(original).cause)
    }

    @Test fun otherPermissionImageAndGuestErrorsKeepTheirOriginalIdentity() {
        for (original in listOf(
            IOException("Failed to create TAP interface avf_tap_42: TUNSETIFF EACCES"),
            IOException("Failed to create avf_tap_fixed: TUNSETIFF ENOMEM"),
            SecurityException("AVF custom VM access requires permissions"),
            IOException("invalid image"),
            IllegalStateException("Guest exited during boot"),
        )) assertSame(original, describeAvfNetworkFailure(original))
    }
}
