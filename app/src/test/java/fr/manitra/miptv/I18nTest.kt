package fr.manitra.miptv

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {
    private val initial = I18n.language

    @After
    fun restore() { I18n.language = initial }

    @Test
    fun languageFollowsTheLocaleAndFallsBackToEnglish() {
        assertEquals(Language.FRENCH, Language.of(Locale.FRANCE))
        assertEquals(Language.FRENCH, Language.of(Locale.CANADA_FRENCH))
        assertEquals(Language.ENGLISH, Language.of(Locale.US))
        assertEquals(Language.SPANISH, Language.of(Locale.forLanguageTag("es-MX")))
        assertEquals(Language.ENGLISH, Language.of(Locale.GERMANY))
        assertEquals(Language.ENGLISH, Language.of(null as Locale?))
    }

    @Test
    fun aStoredChoiceGivesItsLanguage() {
        assertEquals(Language.FRENCH, Language.fromTag("fr"))
        assertEquals(Language.SPANISH, Language.fromTag("es-MX"))
        assertEquals(Language.ENGLISH, Language.fromTag("en-GB"))
        // No choice, or a language the app does not offer: the device language applies.
        assertEquals(null, Language.fromTag(null))
        assertEquals(null, Language.fromTag(""))
        assertEquals(null, Language.fromTag("de"))
    }

    @Test
    fun localTestsRunInFrench() {
        assertEquals(Language.FRENCH, I18n.language)
        assertEquals("Accueil", Destination.HOME.label)
    }

    @Test
    fun textsFollowTheCurrentLanguage() {
        I18n.language = Language.ENGLISH
        assertEquals("Home", Destination.HOME.label)
        assertEquals("All", Category.ALL.name)
        assertEquals("Resume at 1h 05", resumeLabel(3_900_000))
        assertEquals("1 channel", tr.channelCount(1))
        assertEquals("Account expired on Jan 1, 2025.", XtreamAccountInfo("Expired", 1_735_732_800_000L).blocker(nowMs = 1_761_734_400_000L))
        assertEquals("7.6", formatRating(7.6))

        I18n.language = Language.SPANISH
        assertEquals("Inicio", Destination.HOME.label)
        assertEquals("Favoritos", Category.FAVORITES.name)
        assertEquals("Reanudar en 42 min", resumeLabel(2_520_000))
        assertEquals("3 canales", tr.channelCount(3))
        assertEquals("Cuenta caducada el 01/01/2025.", XtreamAccountInfo("Expired", 1_735_732_800_000L).blocker(nowMs = 1_761_734_400_000L))
        assertEquals("7,6", formatRating(7.6))
    }

    /** Every text of every language is filled in. */
    @Test
    fun everyLanguageHasEveryText() {
        val getters = Texts::class.java.methods.filter { it.parameterCount == 0 && it.returnType == String::class.java }
        assertTrue(getters.size > 100)
        for (language in Language.entries) {
            for (getter in getters) {
                val text = getter.invoke(language.texts) as String
                assertTrue("${language.name}.${getter.name} is blank", text.isNotBlank())
            }
        }
    }
}
