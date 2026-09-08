package com.termux.localgames.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public class ComponentSelectionTest {

    private static final List<ComponentSelection.Choice> CHOICES = ComponentSelection.choices(
        new ComponentSelection.Choice("wine-9.3-vanilla-wow64", "Wine 9.3 Vanilla · 9.3 WoW64"),
        new ComponentSelection.Choice("wine-8.18-staging-wow64", "Wine 8.18 Staging · 8.18 WoW64"));

    @Test
    public void showsCatalogLabelForPersistedTechnicalId() {
        assertEquals("Wine 9.3 Vanilla · 9.3 WoW64",
            ComponentSelection.labelFor(CHOICES, "wine-9.3-vanilla-wow64"));
    }

    @Test
    public void keepsLegacyValueVisibleWhenCatalogNoLongerHasIt() {
        assertEquals("wine-7.0-removed",
            ComponentSelection.labelFor(CHOICES, "wine-7.0-removed"));
    }

    @Test
    public void resolvesLabelAndRawValueBackToPersistedId() {
        assertEquals("wine-8.18-staging-wow64",
            ComponentSelection.valueFor(CHOICES, "Wine 8.18 Staging · 8.18 WoW64"));
        assertEquals("wine-8.18-staging-wow64",
            ComponentSelection.valueFor(CHOICES, "wine-8.18-staging-wow64"));
    }

    @Test
    public void rejectsUnsupportedSelectionWithoutSilentSubstitution() {
        try {
            ComponentSelection.valueFor(CHOICES, "wine-7.0-removed");
            fail("selection outside the catalog must not be accepted");
        } catch (ComponentSelection.UnsupportedSelection expected) {
            assertEquals("wine-7.0-removed", expected.getSelection());
        }
    }

    @Test
    public void unsupportedSelectionDoesNotLeakInternalTokens() {
        try {
            ComponentSelection.valueFor(CHOICES, "wine-7.0-removed");
            fail("selection outside the catalog must not be accepted");
        } catch (ComponentSelection.UnsupportedSelection expected) {
            String message = expected.getMessage();
            assertEquals("component selection unavailable", message);
        }
    }

    @Test
    public void emptySelectionIsRejectedInsteadOfSavingBlankComponent() {
        try {
            ComponentSelection.valueFor(CHOICES, "   ");
            fail("blank selection must not be accepted");
        } catch (ComponentSelection.UnsupportedSelection expected) {
            assertEquals("", expected.getSelection());
        }
    }
}
