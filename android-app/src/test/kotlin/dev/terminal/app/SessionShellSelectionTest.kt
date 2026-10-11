package dev.terminal.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionShellSelectionTest {
    @Test fun newNativeTabsUseRuntimeHomeWithoutChangingGuestDefaults() {
        assertEquals("", sessionInitialDirectory("NATIVE"))
        listOf("ROOTLESS", "ROOTLESS_UBUNTU", "ROOTLESS_ARCH").forEach {
            assertEquals("/root", sessionInitialDirectory(it))
        }
        assertEquals("/home/droid", sessionInitialDirectory("AVF"))
    }

    @Test fun linuxGuestsUseInteractiveBashForTerminalKeyEditing() {
        listOf("ROOTLESS", "ROOTLESS_UBUNTU", "ROOTLESS_ARCH", "AVF").forEach {
            assertEquals("/bin/bash", sessionShell(it))
        }
    }

    @Test fun nativeDefaultsToTheAndroidSystemShell() {
        assertEquals("/system/bin/sh", sessionShell("NATIVE"))
    }
}
