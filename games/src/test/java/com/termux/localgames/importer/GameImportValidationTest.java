package com.termux.localgames.importer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GameImportValidationTest {

    @Test
    public void acceptsRootAndNestedRelativePaths() {
        assertTrue(GameImportValidation.isSafeRelativeDirectory("."));
        assertTrue(GameImportValidation.isSafeRelativeDirectory("Binaries/Win64"));
        assertTrue(GameImportValidation.isSafeRelativeFile("Binaries/Win64/Game.exe"));
    }

    @Test
    public void rejectsAbsoluteTraversalAndWindowsDrivePaths() {
        assertFalse(GameImportValidation.isSafeRelativeDirectory("../outside"));
        assertFalse(GameImportValidation.isSafeRelativeDirectory("/absolute"));
        assertFalse(GameImportValidation.isSafeRelativeDirectory("C:\\Games"));
        assertFalse(GameImportValidation.isSafeRelativeFile("."));
        assertFalse(GameImportValidation.isSafeRelativeFile("a//game.exe"));
    }
}
