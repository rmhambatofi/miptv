package fr.manitra.miptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ThemeTest {
    @Test
    fun aStoredChoiceGivesItsTheme() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey("light"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("system"))
        // No choice, or an unknown one: the dark theme the app always had.
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("sepia"))
    }

    @Test
    fun onlyTheSystemThemeFollowsTheDevice() {
        assertSame(DarkColors, ThemeMode.SYSTEM.colors(deviceDark = true))
        assertSame(LightColors, ThemeMode.SYSTEM.colors(deviceDark = false))
        assertSame(LightColors, ThemeMode.LIGHT.colors(deviceDark = true))
        assertSame(DarkColors, ThemeMode.DARK.colors(deviceDark = false))
    }

    @Test
    fun theVideoStaysDarkInTheLightTheme() {
        val initial = Palette.colors
        try {
            Palette.colors = LightColors
            assertEquals(LightColors.ink, Palette.Ink)
            assertSame(DarkColors, Palette.Video)
        } finally {
            Palette.colors = initial
        }
    }
}
