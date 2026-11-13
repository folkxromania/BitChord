package com.music.bitchord.data.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumLogoRepositoryTest {
    @Test
    fun exactEditionWordsAreRequiredForAutomaticLogoMatching() {
        assertEquals(
            1f,
            AlbumLogoRepository.strictTitleMatchScore("1989 Taylor's Version", "1989 (Taylor's Version)"),
            0f,
        )
        assertEquals(
            0f,
            AlbumLogoRepository.strictTitleMatchScore("1989", "1989 (Taylor's Version)"),
            0f,
        )
        assertEquals(
            0f,
            AlbumLogoRepository.strictTitleMatchScore("Midnights", "Midnights (The Til Dawn Edition)"),
            0f,
        )
    }

    @Test
    fun aLeadingArticleMayBeOmittedButAllMeaningfulTitleWordsMustRemain() {
        assertTrue(
            AlbumLogoRepository.strictTitleMatchScore(
                "Tortured Poets Department",
                "The Tortured Poets Department",
            ) >= 0.95f,
        )
    }

    @Test
    fun unrelatedArtistTextDoesNotTurnAHeaderIntoAnAlbumWordmark() {
        assertEquals(
            0f,
            AlbumLogoRepository.strictTitleMatchScore(
                "The Tortured Poets Department Taylor Swift",
                "The Tortured Poets Department",
            ),
            0f,
        )
    }
}
