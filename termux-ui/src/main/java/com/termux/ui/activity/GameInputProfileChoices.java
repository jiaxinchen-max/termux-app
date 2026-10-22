package com.termux.ui.activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.ui.data.RuntimeProfileRepository;
import com.termux.ui.domain.RuntimeProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure logic behind the in-game "Controller" row: composing and parsing the per-game
 * {@code inputProfileId} and turning the available touch profiles into a display model.
 *
 * <p>Encoding (identical to the {@code LaunchSpec} constraint
 * {@code (xinput|dinput)(:[1-9][0-9]{0,5})?}): the mapper prefix {@code xinput|dinput} alone means
 * "controller off"; {@code <prefix>:<id>} selects the touch profile whose numeric
 * {@code InputControlsManager} id is {@code <id>}. The id is the manager's own integer id, so no
 * mapping table is needed; only profiles whose id lies in {@code 1..999999} can be expressed and
 * every other id is simply not offered.</p>
 */
final class GameInputProfileChoices {

    /** Largest profile id the encoding can carry (six digits). */
    static final int MAX_PROFILE_ID = 999_999;
    /** Selection meaning "no controller". */
    static final int OFF = 0;

    private static final String ORIENTATION_SEPARATOR = " \u00b7 ";

    private static final Pattern SELECTION =
        Pattern.compile("(xinput|dinput)(?::([1-9][0-9]{0,5}))?");

    private GameInputProfileChoices() {}

    /** One touch profile as reported by the controller manager. */
    static final class Entry {
        final int id;
        @NonNull final String name;
        /** True for a portrait layout, false for a landscape one. */
        final boolean portrait;

        Entry(int id, @Nullable String name) {
            this(id, name, false);
        }

        Entry(int id, @Nullable String name, boolean portrait) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.portrait = portrait;
        }
    }

    /** One selectable line of the dialog; {@code profileId == OFF} is the "Off" line. */
    static final class Choice {
        final int profileId;
        @NonNull final String label;
        final boolean checked;

        Choice(int profileId, @NonNull String label, boolean checked) {
            this.profileId = profileId;
            this.label = label;
            this.checked = checked;
        }
    }

    static boolean isValidSelection(@Nullable String selection) {
        return selection != null && SELECTION.matcher(selection).matches();
    }

    /** {@code xinput} or {@code dinput}, or null when {@code selection} is not a valid encoding. */
    @Nullable
    static String mapperOf(@Nullable String selection) {
        if (selection == null) return null;
        Matcher matcher = SELECTION.matcher(selection);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Selected profile id, or {@link #OFF} for "off" and for any invalid encoding. */
    static int profileIdOf(@Nullable String selection) {
        if (selection == null) return OFF;
        Matcher matcher = SELECTION.matcher(selection);
        if (!matcher.matches() || matcher.group(2) == null) return OFF;
        return Integer.parseInt(matcher.group(2));
    }

    /** True when {@code profileId} can be stored in an {@code inputProfileId}. */
    static boolean isSelectableId(int profileId) {
        return profileId >= 1 && profileId <= MAX_PROFILE_ID;
    }

    /**
     * New {@code inputProfileId} that keeps the mapper prefix of {@code currentSelection}.
     * {@code profileId == OFF} yields the bare prefix, otherwise {@code <prefix>:<id>}.
     *
     * @throws IllegalArgumentException when {@code currentSelection} is not a valid encoding or
     *         {@code profileId} is neither {@link #OFF} nor within {@code 1..MAX_PROFILE_ID}
     */
    @NonNull
    static String compose(@Nullable String currentSelection, int profileId) {
        String mapper = mapperOf(currentSelection);
        if (mapper == null) throw new IllegalArgumentException("invalid_input_profile_selection");
        if (profileId == OFF) return mapper;
        if (!isSelectableId(profileId)) throw new IllegalArgumentException("invalid_input_profile_id");
        return mapper + ":" + profileId;
    }

    /**
     * The profiles that belong in the list for a screen: only layouts designed for the screen's
     * orientation. The layout that is active right now stays listed whatever its orientation, so the
     * list never claims the controller is off while a hidden layout is still in use.
     */
    @NonNull
    static List<Entry> forScreenOrientation(@Nullable List<Entry> profiles, boolean portraitScreen,
                                            int activeProfileId) {
        List<Entry> result = new ArrayList<>();
        if (profiles == null) return result;
        for (Entry entry : profiles) {
            if (entry == null) continue;
            if (entry.portrait == portraitScreen || entry.id == activeProfileId) result.add(entry);
        }
        return result;
    }

    /**
     * Display model: "Off" first, then every distinct selectable profile in the given order. Entries
     * whose id cannot be encoded are dropped; blank names fall back to {@code unnamedFormat}
     * (a {@code %d} format). An active id that is not offered (or {@link #OFF}) checks "Off".
     */
    @NonNull
    static List<Choice> build(@Nullable List<Entry> profiles, int activeProfileId,
                              @NonNull String offLabel, @NonNull String unnamedFormat) {
        return build(profiles, activeProfileId, offLabel, unnamedFormat, null, null);
    }

    /**
     * Like {@link #build(List, int, String, String)}; when both tags are given, each profile label
     * ends with its orientation tag ({@code "<label> · <tag>"}), so landscape and portrait layouts
     * can be told apart in the list.
     */
    @NonNull
    static List<Choice> build(@Nullable List<Entry> profiles, int activeProfileId,
                              @NonNull String offLabel, @NonNull String unnamedFormat,
                              @Nullable String landscapeTag, @Nullable String portraitTag) {
        Set<Integer> seen = new HashSet<>();
        List<Entry> offered = new ArrayList<>();
        if (profiles != null) {
            for (Entry entry : profiles) {
                if (entry == null || !isSelectableId(entry.id) || !seen.add(entry.id)) continue;
                offered.add(entry);
            }
        }
        boolean activeOffered = seen.contains(activeProfileId);
        List<Choice> result = new ArrayList<>();
        result.add(new Choice(OFF, offLabel, !activeOffered));
        for (Entry entry : offered) {
            String name = entry.name.trim();
            String label = name.isEmpty() ? String.format(Locale.ROOT, unnamedFormat, entry.id) : name;
            if (landscapeTag != null && portraitTag != null) {
                label += ORIENTATION_SEPARATOR + (entry.portrait ? portraitTag : landscapeTag);
            }
            result.add(new Choice(entry.id, label, entry.id == activeProfileId));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Remembers {@code selection} in the runtime profile of {@code gameId} (the profile id equals the
     * game id) so the next launch restores it. Nothing is written when the stored value already
     * equals it. Returns false when the selection is invalid, the profile is missing or storage fails.
     */
    static boolean persist(@NonNull RuntimeProfileRepository repository, @NonNull String gameId,
                           @Nullable String selection) {
        if (!isValidSelection(selection)) return false;
        try {
            Optional<RuntimeProfile> found = repository.find(gameId);
            if (!found.isPresent()) return false;
            RuntimeProfile current = found.get();
            if (!selection.equals(current.getInputProfileId())) {
                repository.save(current.withInputProfileId(selection));
            }
            return true;
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    /** Index of the checked choice ({@code 0}, the "Off" line, when none is checked). */
    static int checkedIndex(@NonNull List<Choice> choices) {
        for (int index = 0; index < choices.size(); index++) {
            if (choices.get(index).checked) return index;
        }
        return 0;
    }

    /** Label of the checked choice, used as the row summary. */
    @NonNull
    static String summary(@NonNull List<Choice> choices, @NonNull String offLabel) {
        return choices.isEmpty() ? offLabel : choices.get(checkedIndex(choices)).label;
    }
}
