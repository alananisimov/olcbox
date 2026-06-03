package org.olcbox.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {

    @Test
    fun fromPreference_returnsKnownValue() {
        assertEquals(AppLanguage.System, AppLanguage.fromPreference("system"))
        assertEquals(AppLanguage.English, AppLanguage.fromPreference("en"))
        assertEquals(AppLanguage.Russian, AppLanguage.fromPreference("ru"))
    }

    @Test
    fun fromPreference_fallsBackToSystem() {
        assertEquals(AppLanguage.System, AppLanguage.fromPreference(null))
        assertEquals(AppLanguage.System, AppLanguage.fromPreference(""))
        assertEquals(AppLanguage.System, AppLanguage.fromPreference("de"))
    }
}
