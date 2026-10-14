package com.termux.localgames.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class CustomRuntimeComponentTest {

    private static final String SHA256 =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void glibcOriginWineAllowsNullTranslator() {
        CustomRuntimeComponent component = new CustomRuntimeComponent(
            "custom-wine-my-build", CustomRuntimeComponent.Kind.WINE, null, "My Build", 1L, SHA256);

        assertNull(component.getTranslator());
        assertEquals("custom-wine-my-build", component.getId());
    }

    @Test
    public void rootfsOriginWineStillAcceptsHangoverOrBox64() {
        CustomRuntimeComponent hangover = new CustomRuntimeComponent(
            "custom-wine-my-build", CustomRuntimeComponent.Kind.WINE, RuntimeTranslator.HANGOVER,
            "My Build", 1L, SHA256);
        assertEquals(RuntimeTranslator.HANGOVER, hangover.getTranslator());

        CustomRuntimeComponent box64 = new CustomRuntimeComponent(
            "custom-wine-my-build", CustomRuntimeComponent.Kind.WINE, RuntimeTranslator.BOX64,
            "My Build", 1L, SHA256);
        assertEquals(RuntimeTranslator.BOX64, box64.getTranslator());
    }

    @Test
    public void wineRejectsFexTranslatorRegardlessOfOrigin() {
        assertThrows(IllegalArgumentException.class, () -> new CustomRuntimeComponent(
            "custom-wine-my-build", CustomRuntimeComponent.Kind.WINE, RuntimeTranslator.FEX,
            "My Build", 1L, SHA256));
    }

    @Test
    public void box64KindRejectsAnyTranslator() {
        assertThrows(IllegalArgumentException.class, () -> new CustomRuntimeComponent(
            "custom-box64-my-build", CustomRuntimeComponent.Kind.BOX64, RuntimeTranslator.BOX64,
            "My Build", 1L, SHA256));
    }

    @Test
    public void box64KindAllowsNullTranslator() {
        CustomRuntimeComponent component = new CustomRuntimeComponent(
            "custom-box64-my-build", CustomRuntimeComponent.Kind.BOX64, null, "My Build", 1L,
            SHA256);
        assertNull(component.getTranslator());
    }
}
