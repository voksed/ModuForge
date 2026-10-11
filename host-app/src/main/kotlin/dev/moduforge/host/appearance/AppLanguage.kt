package dev.moduforge.host.appearance

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The language of the app's own texts: the system's, or one the user picked in the settings.
 * Stored in plain preferences because it has to be known before anything else is created.
 */
object AppLanguage {
    const val SYSTEM = "system"

    /** Languages the app is translated into: code and the name shown in that language. */
    val SUPPORTED = listOf("en" to "English", "ru" to "Русский")

    private const val FILE = "language"
    private const val KEY = "code"

    fun get(context: Context): String {
        // An isolated sandbox process has no preferences at all (and nothing to translate).
        val code = try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM
        } catch (e: IllegalStateException) {
            SYSTEM
        }
        return if (SUPPORTED.any { it.first == code }) code else SYSTEM
    }

    fun set(context: Context, code: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    /** [base] with the chosen language applied; unchanged while the language follows the system. */
    fun wrap(base: Context): Context {
        val code = get(base)
        if (code == SYSTEM) return base
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        return base.createConfigurationContext(configuration)
    }
}
