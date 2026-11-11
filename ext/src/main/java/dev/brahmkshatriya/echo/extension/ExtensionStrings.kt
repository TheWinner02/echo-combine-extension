package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings
import java.util.Locale

/** Optional host text catalog: older Echo versions keep the English fallback. */
internal class ExtensionStrings(private val settings: () -> Settings?) {
    fun get(key: String, fallback: String, vararg arguments: Any): String {
        val global = settings()
        val translated = runCatching { global?.getString("echo.l10n.$key") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
        if (arguments.isEmpty()) return translated
        val tag = runCatching { global?.getString("echo.l10n.locale") }.getOrNull()
        val locale = tag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        return try {
            String.format(locale, translated, *arguments)
        } catch (_: java.util.IllegalFormatException) {
            String.format(locale, fallback, *arguments)
        }
    }
}
