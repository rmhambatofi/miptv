package fr.manitra.miptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlassTest {
    @Test
    fun theGlassOptionKeepsTheThemeColours() {
        val colors = Palette.colors
        val glass = Palette.glass
        try {
            Palette.colors = LightColors
            Palette.glass = true
            // The theme itself is untouched: same ground, text and accent.
            assertEquals(LightColors.ink, Palette.Ink)
            assertEquals(LightColors.text, Palette.Text)
            assertEquals(LightColors.accent, Palette.Accent)
            // Only the selection becomes a translucent accent, so the glass shows through.
            assertEquals(LightColors.accent.copy(alpha = 0.12f), Palette.AccentSoft)
            Palette.glass = false
            assertEquals(LightColors.accentSoft, Palette.AccentSoft)
        } finally {
            Palette.colors = colors
            Palette.glass = glass
        }
    }

    @Test
    fun glassFollowsTheThemeExceptOverTheVideo() {
        assertNotEquals(glassLook(Glass.REGULAR, DarkColors).fill, glassLook(Glass.REGULAR, LightColors).fill)
        // Over a picture, the glass is the dark one whatever the theme.
        assertEquals(glassLook(Glass.MEDIA, DarkColors).fill, glassLook(Glass.MEDIA, LightColors).fill)
        // A selection takes the accent of its theme.
        assertEquals(LightColors.accent.copy(alpha = 0.14f), glassLook(Glass.TINT, LightColors).fill)
        assertEquals(DarkColors.accent.copy(alpha = 0.22f), glassLook(Glass.TINT, DarkColors).fill)
    }
}
