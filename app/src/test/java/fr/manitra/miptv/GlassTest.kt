package fr.manitra.miptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTest {
    @Test
    fun liquidGlassIsATheme() {
        assertEquals(ThemeMode.GLASS, ThemeMode.fromKey("glass"))
        // It does not follow the device.
        assertSame(GlassColors, ThemeMode.GLASS.colors(deviceDark = true))
        assertSame(GlassColors, ThemeMode.GLASS.colors(deviceDark = false))
    }

    @Test
    fun onlyTheLiquidGlassThemeTurnsToGlass() {
        val initial = Palette.colors
        try {
            Palette.colors = GlassColors
            assertTrue(Palette.glass)
            assertSame(DarkColors, Palette.Video)
            // The dark and light themes stay as they are.
            Palette.colors = DarkColors
            assertFalse(Palette.glass)
            Palette.colors = LightColors
            assertFalse(Palette.glass)
        } finally {
            Palette.colors = initial
        }
    }

    @Test
    fun glassCardsLetTheBackdropShowThrough() {
        assertTrue(GlassColors.panel.alpha < 0.2f)
        assertTrue(GlassColors.raised.alpha < 0.2f)
        assertEquals(GlassColors.accent.copy(alpha = 0.22f), glassLook(Glass.TINT, GlassColors).fill)
    }
}
