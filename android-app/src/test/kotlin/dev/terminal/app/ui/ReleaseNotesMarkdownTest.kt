package dev.terminal.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesMarkdownTest {
    @Test fun acceptsAbsoluteWebLinks() {
        for (link in listOf("https://github.com/nolbo/TERM", "http://example.com/path?q=1#notes", "HTTPS://example.com")) {
            assertTrue(link, isReleaseNotesWebLink(link))
        }
    }

    @Test fun rejectsOtherSchemesRelativeLinksAndMisleadingAuthorities() {
        for (link in listOf("javascript:alert(1)", "intent://settings#Intent;scheme=app;end",
            "file:///data/user/0/", "content://dev.terminal.app/private", "data:text/html,hello",
            "mailto:user@example.com", "//example.com", "/notes", "#section", "https:example.com",
            "https://trusted.example@other.example", "https://", "https://example.com\n", "")) {
            assertFalse(link, isReleaseNotesWebLink(link))
        }
    }
}
