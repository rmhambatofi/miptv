package fr.manitra.miptv

import android.content.res.Configuration
import java.util.Date
import java.util.Locale

/** The languages of the interface. Any other device language falls back to [DEFAULT]. */
enum class Language(val tag: String, val texts: Texts) {
    FRENCH("fr", FrenchTexts),
    ENGLISH("en", EnglishTexts),
    SPANISH("es", SpanishTexts);

    companion object {
        val DEFAULT = ENGLISH

        fun of(locale: Locale?): Language = entries.firstOrNull { it.tag == locale?.language } ?: DEFAULT

        /** The first language of the configuration the app offers, so « de, es » gives Spanish rather than English. */
        fun of(configuration: Configuration): Language {
            val locales = configuration.locales
            return (0 until locales.size()).firstNotNullOfOrNull { index -> entries.firstOrNull { it.tag == locales[index].language } } ?: DEFAULT
        }
    }
}

/**
 * The current language of the texts. Follows the device (or the per-app language of Android 13+): set by the
 * application and the activity from their configuration; elsewhere (unit tests) it comes from the default locale.
 */
object I18n {
    @Volatile
    var language: Language = Language.of(Locale.getDefault())
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
