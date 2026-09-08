package com.termux.localgames.artwork;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class GameArtworkReferenceTest {

    @Test
    public void roundTripsOnlyValidatedGameIds() {
        String reference = GameArtworkReference.forGame("game-1");
        assertEquals("game-1", GameArtworkReference.gameId(reference).get());
        assertFalse(GameArtworkReference.gameId("file:///private/cover").isPresent());
        assertFalse(GameArtworkReference.gameId("local-games://artwork/../outside").isPresent());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTraversalWhenCreatingReference() {
        GameArtworkReference.forGame("../outside");
    }
}
