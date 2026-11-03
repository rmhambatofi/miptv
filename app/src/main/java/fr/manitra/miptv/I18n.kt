package fr.manitra.miptv

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Date
import java.util.Locale

/** The languages of the interface. Any other device language falls back to [DEFAULT]. */
enum class Language(val tag: String, val texts: Texts, /** Name in the language itself, as the language picker shows it. */ val nativeName: String) {
    FRENCH("fr", FrenchTexts, "Français"),
    ENGLISH("en", EnglishTexts, "English"),
    SPANISH("es", SpanishTexts, "Español");

    companion object {
        val DEFAULT = ENGLISH

        fun of(locale: Locale?): Language = entries.firstOrNull { it.tag == locale?.language } ?: DEFAULT

        /** The first language of the configuration the app offers, so « de, es » gives Spanish rather than English. */
        fun of(configuration: Configuration): Language {
            val locales = configuration.locales
            return (0 until locales.size()).firstNotNullOfOrNull { index -> entries.firstOrNull { it.tag == locales[index].language } } ?: DEFAULT
        }

        /** The language of a stored tag (« fr », « es-MX »), or null for none or one the app does not offer. */
        fun fromTag(tag: String?): Language? =
            tag?.takeIf { it.isNotBlank() }?.let { value -> entries.firstOrNull { it.tag == Locale.forLanguageTag(value).language } }
    }
}

/**
 * The language chosen in the settings, over the one of the device. On Android 13+ it is the per-app language of the
 * system (the same as in the system settings, which then applies it to the configuration and recreates the activity);
 * before, it is kept in the app's preferences and applied by [I18n.apply].
 */
object LanguageChoice {
    private const val PREFERENCES = "settings"
    private const val KEY = "language"

    /** The chosen language, or null to follow the device. */
    fun get(context: Context): Language? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty() }?.let { Language.fromTag(it[0].toLanguageTag()) }
        } else {
            Language.fromTag(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))
        }

    /** The language of the device, whatever the choice: what « system language » gives. */
    fun device(context: Context): Language =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales?.let { Language.of(Configuration().apply { setLocales(it) }) }
                ?: Language.of(context.resources.configuration)
        } else {
            Language.of(context.resources.configuration)
        }

    /** Chooses [language] (null: the device language) and shows the activity again in it. */
    fun set(activity: Activity, language: Language?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system stores the choice, updates the configuration and recreates the activity.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                language?.let { LocaleList.forLanguageTags(it.tag) } ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, language?.tag).apply()
            I18n.apply(activity, activity.resources.configuration)
            activity.recreate()
        }
    }
}

/**
 * The current language of the texts: the one chosen in the settings ([LanguageChoice]), or else the device's. Set by
 * the application and the activity through [apply]; elsewhere (unit tests) it comes from the default locale.
 */
object I18n {
    @Volatile
    var language: Language = Language.of(Locale.getDefault())

    /** Sets [language] from the choice of the settings, or else from [configuration], the device's. */
    fun apply(context: Context, configuration: Configuration) {
        language = LanguageChoice.get(context) ?: Language.of(configuration)
    }
}

/** Texts in the current language: `tr.home`, `tr.channelCount(12)`. */
val tr: Texts get() = I18n.language.texts

/** English and Spanish plural: one for exactly 1, other otherwise, with the count in front. */
fun plural(count: Int, one: String, other: String): String = "$count ${if (count == 1) one else other}"

/** « 01/01/2025 », « Jan 1, 2025 ». */
fun formatDate(epochMs: Long): String = java.text.SimpleDateFormat(tr.datePattern, tr.locale).format(Date(epochMs))

/** Short date and time in the conventions of the current language. */
fun formatDateTime(epochMs: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, tr.locale).format(Date(epochMs))
