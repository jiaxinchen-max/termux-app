package com.termux.localgames.activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure mapping between persisted component ids and catalog-driven selector labels. */
public final class ComponentSelection {

    /** Selector entry pairing the persisted technical id with its display label. */
    public static final class Choice {
        private final String value;
        private final String label;

        public Choice(@NonNull String value, @NonNull String label) {
            this.value = value;
            this.label = label;
        }

        @NonNull public String getValue() { return value; }
        @NonNull public String getLabel() { return label; }

        @Override
        public String toString() { return label; }
    }

    /** Raised when a shown selection has no catalog entry for the active backend. */
    public static final class UnsupportedSelection extends IllegalArgumentException {
        private final String selection;

        UnsupportedSelection(@NonNull String selection) {
            super("component selection unavailable");
            this.selection = selection;
        }

        @NonNull public String getSelection() { return selection; }
    }

    private ComponentSelection() {
    }

    @NonNull
    public static List<Choice> choices(Choice... values) {
        List<Choice> result = new ArrayList<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableList(result);
    }

    /**
     * Label to show for a persisted value. Values outside the current catalog are kept
     * verbatim so users can still identify what an older profile referenced.
     */
    @NonNull
    public static String labelFor(@NonNull List<Choice> choices, @Nullable String value) {
        String candidate = value == null ? "" : value.trim();
        for (Choice choice : choices) {
            if (choice.value.equals(candidate) || choice.label.equals(candidate)) {
                return choice.label;
            }
        }
        return candidate;
    }

    /**
     * Persisted value for a shown selection. Never substitutes a different component:
     * an entry missing from the catalog raises {@link UnsupportedSelection}.
     */
    @NonNull
    public static String valueFor(@NonNull List<Choice> choices, @Nullable String selected) {
        String candidate = selected == null ? "" : selected.trim();
        for (Choice choice : choices) {
            if (choice.label.equals(candidate) || choice.value.equals(candidate)) {
                return choice.value;
            }
        }
        throw new UnsupportedSelection(candidate);
    }
}
