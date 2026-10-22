package com.termux.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public class ComponentSelectionTest {

    private static final List<ComponentSelection.Choice> CHOICES = ComponentSelection.choices(
        new ComponentSelection.Choice("glibc_wine_9.3-vanilla", "Wine 9.3 Vanilla · 9.3 WoW64"),
        new ComponentSelection.Choice("glibc_wine_8.18-staging", "Wine 8.18 Staging · 8.18 WoW64"));

    @Test
    public void showsCatalogLabelForPersistedTechnicalId() {
        assertEquals("Wine 9.3 Vanilla · 9.3 WoW64",
            ComponentSelection.labelFor(CHOICES, "glibc_wine_9.3-vanilla"));
    }

    @Test
    public void keepsLegacyValueVisibleWhenCatalogNoLongerHasIt() {
        assertEquals("wine-7.0-removed",
            ComponentSelection.labelFor(CHOICES, "wine-7.0-removed"));
    }

    @Test
    public void resolvesLabelAndRawValueBackToPersistedId() {
        assertEquals("glibc_wine_8.18-staging",
            ComponentSelection.valueFor(CHOICES, "Wine 8.18 Staging · 8.18 WoW64"));
        assertEquals("glibc_wine_8.18-staging",
            ComponentSelection.valueFor(CHOICES, "glibc_wine_8.18-staging"));
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
