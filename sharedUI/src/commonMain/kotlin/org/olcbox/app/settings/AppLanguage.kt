package org.olcbox.app.settings

enum class AppLanguage(
    val preferenceValue: String,
    val languageTag: String?
) {
    System(preferenceValue = "system", languageTag = null),
    English(preferenceValue = "en", languageTag = "en"),
    Russian(preferenceValue = "ru", languageTag = "ru");

    companion object {
        fun fromPreference(value: String?): AppLanguage {
            return entries.firstOrNull { it.preferenceValue == value } ?: System
        }
    }
}
