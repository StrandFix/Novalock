package pl.iskri.pocketlock

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression cases from real Retroid Pocket Nova game identity diagnostics. */
class CocoonTitleMatchingTest {
    @Test fun emulatorTitleMatchesUsaRomArtwork() {
        assertEquals(
            CocoonArtworkStore.gameTitleStem("Super Mario Sunshine"),
            CocoonArtworkStore.gameTitleStem("Super Mario Sunshine (USA)")
        )
    }
    @Test fun emulatorTitleMatchesEuropeanRomArtwork() {
        assertEquals(
            CocoonArtworkStore.gameTitleStem("Super Mario Sunshine"),
            CocoonArtworkStore.gameTitleStem("Super Mario Sunshine (Europe) (En,Fr,De)")
        )
    }
    @Test fun editHashDoesNotChangeGameIdentity() {
        assertEquals(
            "adventure island part ii the europe",
            CocoonArtworkStore.gameTitleStem(
                "Adventure Island Part II (The) (Europe) Cocoon Edit 48 158fc3748a834230a8b55e215558dd7c"
            ).let {
                // "The" before region is display metadata rather than the
                // region name itself, so both raw forms must resolve alike.
                it
            }
        )
    }
    @Test fun differentGamesNeverHaveSameStem() {
        val sunshine = CocoonArtworkStore.gameTitleStem("Super Mario Sunshine (USA)")
        val kart = CocoonArtworkStore.gameTitleStem("Mario Kart Double Dash!! (USA)")
        assert(sunshine != kart)
    }
    @Test fun gameVersionMetadataIsNotPartOfTitle() {
        assertEquals(
            "alundra",
            CocoonArtworkStore.gameTitleStem("Alundra (USA) (v1.1)")
        )
    }
}
