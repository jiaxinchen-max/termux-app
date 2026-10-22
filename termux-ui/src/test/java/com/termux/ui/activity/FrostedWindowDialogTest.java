package com.termux.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;

import org.junit.Test;

public class FrostedWindowDialogTest {

    @Test
    public void lightModeDoesNotUseTheBlackWindowDim() {
        assertEquals(0f, FrostedWindow.dimAmount(31, false), 0f);
        assertEquals(0f, FrostedWindow.dimAmount(30, false), 0f);
    }

    @Test
    public void darkModeKeepsThePreviousDim() {
        assertEquals(FrostedWindow.dimAmount(31), FrostedWindow.dimAmount(31, true), 0f);
        assertEquals(FrostedWindow.dimAmount(30), FrostedWindow.dimAmount(30, true), 0f);
        assertTrue(FrostedWindow.dimAmount(31, true) > 0f);
    }

    @Test
    public void nightModeIsReadFromTheUiModeMask() {
        assertTrue(FrostedWindow.isNight(Configuration.UI_MODE_NIGHT_YES));
        assertTrue(FrostedWindow.isNight(Configuration.UI_MODE_NIGHT_YES | Configuration.UI_MODE_TYPE_NORMAL));
        assertFalse(FrostedWindow.isNight(Configuration.UI_MODE_NIGHT_NO));
        assertFalse(FrostedWindow.isNight(Configuration.UI_MODE_NIGHT_UNDEFINED));
    }

    @Test
    public void panelRadiusScalesTheEightDpTokenWithDensity() {
        assertEquals(8f, FrostedWindow.panelRadiusPx(1f), 0f);
        assertEquals(21f, FrostedWindow.panelRadiusPx(2.625f), 0f);
    }

    @Test
    public void blurDimIsOneValueForMaterialAndSelfDrawnDialogs() {
        // R.dimen.local_games_dialog_dim_amount (applied by the dialog theme) is 0.30 as well.
        assertEquals(0.30f, FrostedWindow.dimAmount(31, true), 0f);
    }
}
