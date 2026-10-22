package com.termux.localgames.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GameInputProfileChoicesTest {

    private static final String OFF = "Off";
    private static final String UNNAMED = "Controller %d";

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static GameInputProfileChoices.Entry entry(int id, String name) {
        return new GameInputProfileChoices.Entry(id, name);
    }

    @Test
    public void composeKeepsTheMapperPrefix() {
        assertEquals("xinput:3", GameInputProfileChoices.compose("xinput", 3));
        assertEquals("dinput:3", GameInputProfileChoices.compose("dinput", 3));
        assertEquals("dinput:7", GameInputProfileChoices.compose("dinput:3", 7));
        assertEquals("xinput:999999", GameInputProfileChoices.compose("xinput:1", 999_999));
        assertEquals("xinput:1", GameInputProfileChoices.compose("xinput", 1));
    }

    @Test
    public void composeOffYieldsTheBarePrefix() {
        assertEquals("xinput", GameInputProfileChoices.compose("xinput", GameInputProfileChoices.OFF));
        assertEquals("dinput", GameInputProfileChoices.compose("dinput:12", GameInputProfileChoices.OFF));
    }

    @Test
    public void composeRejectsIllegalProfileIds() {
        for (int id : new int[] {-1, -100, 1_000_000, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            try {
                GameInputProfileChoices.compose("xinput", id);
                throw new AssertionError("accepted id " + id);
            } catch (IllegalArgumentException expected) {
                assertEquals("invalid_input_profile_id", expected.getMessage());
            }
        }
    }

    @Test
    public void composeRejectsIllegalCurrentSelections() {
        for (String current : new String[] {null, "", "xinput:", "xinput:0", "winput", "XINPUT",
            "xinput:1000000", "xinput:01", "xinput:1:2", "xinput "}) {
            try {
                GameInputProfileChoices.compose(current, 1);
                throw new AssertionError("accepted " + current);
            } catch (IllegalArgumentException expected) {
                assertEquals("invalid_input_profile_selection", expected.getMessage());
            }
        }
    }

    @Test
    public void everyComposedValueSatisfiesTheLaunchSpecEncoding() {
        String regex = "(?:xinput|dinput)(?::[1-9][0-9]{0,5})?";
        for (String mapper : new String[] {"xinput", "dinput", "xinput:5", "dinput:5"}) {
            for (int id : new int[] {0, 1, 9, 10, 99_999, 100_000, 999_999}) {
                assertTrue(GameInputProfileChoices.compose(mapper, id).matches(regex));
            }
        }
    }

    @Test
    public void parsingSplitsMapperAndProfileId() {
        assertEquals("xinput", GameInputProfileChoices.mapperOf("xinput"));
        assertEquals("dinput", GameInputProfileChoices.mapperOf("dinput:42"));
        assertNull(GameInputProfileChoices.mapperOf("garbage"));
        assertNull(GameInputProfileChoices.mapperOf(null));

        assertEquals(0, GameInputProfileChoices.profileIdOf("xinput"));
        assertEquals(42, GameInputProfileChoices.profileIdOf("dinput:42"));
        assertEquals(999_999, GameInputProfileChoices.profileIdOf("xinput:999999"));
        assertEquals(0, GameInputProfileChoices.profileIdOf("xinput:0"));
        assertEquals(0, GameInputProfileChoices.profileIdOf("xinput:1000000"));
        assertEquals(0, GameInputProfileChoices.profileIdOf(null));
        assertTrue(GameInputProfileChoices.isValidSelection("dinput:1"));
        assertFalse(GameInputProfileChoices.isValidSelection("dinput:-1"));
    }

    @Test
    public void composeAndParseRoundTrip() {
        String composed = GameInputProfileChoices.compose("dinput", 17);
        assertEquals("dinput", GameInputProfileChoices.mapperOf(composed));
        assertEquals(17, GameInputProfileChoices.profileIdOf(composed));
    }

    @Test
    public void selectableIdsAreThoseTheEncodingCanCarry() {
        assertFalse(GameInputProfileChoices.isSelectableId(0));
        assertTrue(GameInputProfileChoices.isSelectableId(1));
        assertTrue(GameInputProfileChoices.isSelectableId(999_999));
        assertFalse(GameInputProfileChoices.isSelectableId(1_000_000));
        assertFalse(GameInputProfileChoices.isSelectableId(-3));
    }

    @Test
    public void choicesListOffFirstThenProfilesInOrder() {
        List<GameInputProfileChoices.Choice> choices = GameInputProfileChoices.build(
            Arrays.asList(entry(2, "FPS"), entry(1, "RTS"), entry(3, "Racing")), 0, OFF, UNNAMED);

        assertEquals(4, choices.size());
        assertEquals(GameInputProfileChoices.OFF, choices.get(0).profileId);
        assertEquals(OFF, choices.get(0).label);
        assertTrue(choices.get(0).checked);
        assertEquals(Arrays.asList("FPS", "RTS", "Racing"),
            Arrays.asList(choices.get(1).label, choices.get(2).label, choices.get(3).label));
        assertEquals(0, GameInputProfileChoices.checkedIndex(choices));
        assertEquals(OFF, GameInputProfileChoices.summary(choices, OFF));
    }

    @Test
    public void orientationTagsAreAppendedToEveryProfileLabel() {
        List<GameInputProfileChoices.Choice> choices = GameInputProfileChoices.build(
            Arrays.asList(new GameInputProfileChoices.Entry(1, "RTS", false),
                new GameInputProfileChoices.Entry(2, "FPS", true),
                new GameInputProfileChoices.Entry(3, "  ", true)),
            2, OFF, UNNAMED, "Landscape", "Portrait");

        assertEquals(OFF, choices.get(0).label);
        assertEquals("RTS \u00b7 Landscape", choices.get(1).label);
        assertEquals("FPS \u00b7 Portrait", choices.get(2).label);
        assertEquals("Controller 3 \u00b7 Portrait", choices.get(3).label);
        assertEquals("FPS \u00b7 Portrait", GameInputProfileChoices.summary(choices, OFF));
    }

    @Test
    public void labelsStayPlainWithoutOrientationTags() {
        List<GameInputProfileChoices.Choice> choices = GameInputProfileChoices.build(
            Collections.singletonList(new GameInputProfileChoices.Entry(1, "RTS", true)), 0, OFF, UNNAMED);

        assertEquals("RTS", choices.get(1).label);
    }

    @Test
    public void activeProfileIsTheOnlyCheckedChoice() {
        List<GameInputProfileChoices.Choice> choices = GameInputProfileChoices.build(
            Arrays.asList(entry(1, "RTS"), entry(2, "FPS")), 2, OFF, UNNAMED);

        assertFalse(choices.get(0).checked);
        assertFalse(choices.get(1).checked);
        assertTrue(choices.get(2).checked);
        assertEquals(2, GameInputProfileChoices.checkedIndex(choices));
        assertEquals("FPS", GameInputProfileChoices.summary(choices, OFF));
    }

    @Test
    public void unknownActiveProfileFallsBackToOff() {
        List<GameInputProfileChoices.Choice> choices = GameInputProfileChoices.build(
            Collections.singletonList(entry(1, "RTS")), 9, OFF, UNNAMED);

        assertTrue(choices.get(0).checked);
        assertFalse(choices.get(1).checked);
        assertEquals(OFF, GameInputProfileChoices.summary(choices, OFF));
    }

    @Test
    public void unencodableAndDuplicateEntriesAreNotOffered() {
        List<GameInputProfileChoices.Choice> choices = GameInputProfileChoices.build(
            Arrays.asList(entry(0, "zero"), entry(-4, "negative"), entry(1_000_000, "huge"),
                entry(5, "Keep"), entry(5, "Duplicate"), null, entry(6, "Also keep")),
            0, OFF, UNNAMED);

        assertEquals(3, choices.size());
        assertEquals("Keep", choices.get(1).label);
        assertEquals("Also keep", choices.get(2).label);
    }

    @Test
    public void blankOrMissingNamesUseTheFallbackLabel() {
        List<GameInputProfileChoices.Choice> choices = GameInputProfileChoices.build(
            Arrays.asList(entry(4, ""), entry(8, "   "), entry(9, null)), 0, OFF, UNNAMED);

        assertEquals("Controller 4", choices.get(1).label);
        assertEquals("Controller 8", choices.get(2).label);
        assertEquals("Controller 9", choices.get(3).label);
    }

    @Test
    public void emptyOrNullProfileListStillOffersOff() {
        assertEquals(1, GameInputProfileChoices.build(null, 3, OFF, UNNAMED).size());
        assertEquals(1, GameInputProfileChoices.build(new ArrayList<>(), 0, OFF, UNNAMED).size());
    }

    @Test
    public void persistWritesTheSelectionIntoTheGameRuntimeProfile() throws Exception {
        File directory = temporaryFolder.newFolder("profiles");
        FileRuntimeProfileRepository repository = new FileRuntimeProfileRepository(directory);
        RuntimeProfile original = RuntimeProfilePresets.create("game-1", RuntimeProfilePreset.RECOMMENDED);
        repository.save(original);

        assertTrue(GameInputProfileChoices.persist(repository, "game-1", "dinput:3"));
        RuntimeProfile stored = repository.find("game-1").get();
        assertEquals("dinput:3", stored.getInputProfileId());
        assertEquals(original.getWinePackage(), stored.getWinePackage());

        assertTrue(GameInputProfileChoices.persist(repository, "game-1", "dinput"));
        assertEquals("dinput", repository.find("game-1").get().getInputProfileId());
    }

    @Test
    public void persistedValueSatisfiesTheLaunchSpecEncoding() throws Exception {
        FileRuntimeProfileRepository repository =
            new FileRuntimeProfileRepository(temporaryFolder.newFolder("profiles"));
        repository.save(RuntimeProfilePresets.create("game-1", RuntimeProfilePreset.RECOMMENDED));
        String selection = GameInputProfileChoices.compose(
            repository.find("game-1").get().getInputProfileId(), 2);

        assertTrue(GameInputProfileChoices.persist(repository, "game-1", selection));
        String stored = repository.find("game-1").get().getInputProfileId();
        assertEquals(2, GameInputProfileChoices.profileIdOf(stored));
        assertTrue(stored.matches("(?:xinput|dinput)(?::[1-9][0-9]{0,5})?"));
    }

    @Test
    public void persistRejectsInvalidSelectionsAndMissingProfilesWithoutWriting() throws Exception {
        File directory = temporaryFolder.newFolder("profiles");
        FileRuntimeProfileRepository repository = new FileRuntimeProfileRepository(directory);
        repository.save(RuntimeProfilePresets.create("game-1", RuntimeProfilePreset.RECOMMENDED));
        String before = repository.find("game-1").get().getInputProfileId();

        assertFalse(GameInputProfileChoices.persist(repository, "game-1", "xinput:0"));
        assertFalse(GameInputProfileChoices.persist(repository, "game-1", null));
        assertFalse(GameInputProfileChoices.persist(repository, "missing-game", "xinput:1"));
        assertFalse(GameInputProfileChoices.persist(repository, "bad/id", "xinput:1"));
        assertEquals(before, repository.find("game-1").get().getInputProfileId());
        assertFalse(new File(directory, "missing-game.properties").exists());
    }
}
