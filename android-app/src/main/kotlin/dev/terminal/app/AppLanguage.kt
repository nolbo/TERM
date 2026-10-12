package dev.terminal.app

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** The app deliberately exposes only the two locales for which UI copy is maintained. */
enum class AppLanguage(val languageTag: String) {
    KOREAN("ko"),
    ENGLISH("en"),
    ;

    fun apply() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }

    companion object {
        fun selected(): AppLanguage = fromLanguageTag(AppCompatDelegate.getApplicationLocales()[0]?.language)

        fun fromLanguageTag(languageTag: String?): AppLanguage =
            if (languageTag == ENGLISH.languageTag) ENGLISH else KOREAN
    }
}
