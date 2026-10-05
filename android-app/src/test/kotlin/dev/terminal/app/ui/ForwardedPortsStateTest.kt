package dev.terminal.app.ui

import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test

class ForwardedPortsStateTest {
    @Test fun distinctHostAndGuestPortsKeepTheConnectableAddressAndServiceLabel() {
        val ports = ForwardedPortConnection.parse("""[
            {"hostAddress":"127.0.0.1","hostPort":48217,"guestPort":8080,"label":"Development API"},
            {"hostAddress":"127.0.0.1","hostPort":8443,"guestPort":8443,"label":null}
        ]""")
        assertEquals(2, ports.size)
        assertEquals("127.0.0.1:48217", ports[0].androidAddress)
        assertEquals(8080, ports[0].guestPort)
        assertEquals("Development API", ports[0].label)
        assertNull(ports[1].label)
    }

    @Test fun emptyAndUnlabelledSnapshotsRemainValid() {
        assertTrue(ForwardedPortConnection.parse("[]").isEmpty())
        val ports = ForwardedPortConnection.parse("""[
            {"hostAddress":"127.0.0.1","hostPort":8000,"guestPort":8000},
            {"hostAddress":"127.0.0.1","hostPort":8001,"guestPort":8001,"label":"  "}
        ]""")
        assertTrue(ports.all { it.label == null })
    }

    @Test fun ipv6AddressesAreUnambiguousWhenCopied() {
        assertEquals("[::1]:8080", ForwardedPortConnection("::1", 8080, 80).androidAddress)
    }

    @Test(expected = JSONException::class) fun malformedSnapshotsAreNotPresentedAsEmptyLists() {
        ForwardedPortConnection.parse("""[{"hostAddress":"127.0.0.1","guestPort":8000}]""")
    }

    @Test(expected = IllegalArgumentException::class) fun invalidPortsAreNotPresentedAsConnectableAddresses() {
        ForwardedPortConnection.parse("""[{"hostAddress":"127.0.0.1","hostPort":0,"guestPort":8000}]""")
    }
}
