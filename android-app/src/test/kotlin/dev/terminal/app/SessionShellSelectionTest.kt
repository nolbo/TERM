package dev.terminal.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionShellSelectionTest {
    @Test fun newNativeTabsUseRuntimeHomeWithoutChangingGuestDefaults() {
        assertEquals("", sessionInitialDirectory("NATIVE"))
        listOf("ROOTLESS", "ROOTLESS_UBUNTU", "ROOTLESS_ARCH").forEach {
            assertEquals("/root", sessionInitialDirectory(it))
        }
        assertEquals("/home/user", sessionInitialDirectory("AVF"))
    }

    @Test fun rootlessUsesInteractiveBashForTerminalKeyEditing() {
        assertEquals("/bin/bash", sessionShell("ROOTLESS", "/system/bin/sh"))
    }

    @Test fun nativeKeepsTheConfiguredShell() {
        assertEquals("/system/bin/mksh", sessionShell("NATIVE", "/system/bin/mksh"))
    }
}
