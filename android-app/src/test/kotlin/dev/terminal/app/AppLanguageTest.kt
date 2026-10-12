package dev.terminal.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun `English tag selects English`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag("en"))
    }

    @Test fun `Korean and unsupported tags select Korean`() {
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromLanguageTag("ko"))
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromLanguageTag("fr"))
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromLanguageTag(null))
    }
}
