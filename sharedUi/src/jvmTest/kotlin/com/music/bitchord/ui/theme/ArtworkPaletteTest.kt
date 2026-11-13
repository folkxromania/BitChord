package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteTest {
    @Test
    fun grayscaleDoesNotAcquireTheDefaultRedHue() {
        assertEquals(0f, adaptedArtworkSaturation(0f, minimum = 0.20f, maximum = 0.62f), 0f)
        assertEquals(0f, adaptedArtworkSaturation(0f, minimum = 0.55f, maximum = 1f), 0f)
    }

    @Test
    fun nearlyNeutralArtworkKeepsItsSubtleSaturation() {
        assertEquals(0.08f, adaptedArtworkSaturation(0.08f, minimum = 0.20f, maximum = 0.62f), 0f)
    }

    @Test
    fun genuinelyChromaticArtworkIsStillStrengthenedAndCapped() {
        assertEquals(0.55f, adaptedArtworkSaturation(0.25f, minimum = 0.55f, maximum = 1f), 0f)
        assertEquals(0.62f, adaptedArtworkSaturation(0.90f, minimum = 0.20f, maximum = 0.62f), 0f)
    }

    @Test
    fun albumSurfacePreservesBrightnessDifferencesBetweenCovers() {
        val darkCover = artworkSurfaceLightness(0.12f, dark = true)
        val brightCover = artworkSurfaceLightness(0.82f, dark = true)
        assertTrue(brightCover > darkCover + 0.12f)

        val lightDarkCover = artworkSurfaceLightness(0.12f, dark = false)
        val lightBrightCover = artworkSurfaceLightness(0.82f, dark = false)
        assertTrue(lightBrightCover > lightDarkCover + 0.12f)
    }

    @Test
    fun mutedArtworkDoesNotReceiveArtificialSaturation() {
        assertEquals(0.08f, artworkSurfaceSaturation(0.08f), 0f)
        assertEquals(0.24f, artworkSurfaceSaturation(0.24f), 0f)
        assertEquals(0.58f, artworkSurfaceSaturation(0.92f), 0f)
    }

    @Test
    fun selectedTextColourMeetsWcagContrastOnWarmAndCoolAlbumSurfaces() {
        val hues = listOf(24f, 55f, 210f, 285f)
        for (dark in listOf(true, false)) {
            for (hue in hues) {
                val lightness = artworkSurfaceLightness(0.9f, dark)
                val background = Color(ColorUtils.HSLToColor(floatArrayOf(hue, 0.58f, lightness)))
                assertTrue(contrastRatio(readableArtworkTextColor(background), background) >= 4.5f)
            }
        }
    }
}
