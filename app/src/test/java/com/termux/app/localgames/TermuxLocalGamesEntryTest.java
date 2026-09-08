package com.termux.app.localgames;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class TermuxLocalGamesEntryTest {

    @Test
    public void createsExplicitIntentForModuleOwnedActivity() {
        Context context = RuntimeEnvironment.getApplication();

        Intent intent = TermuxLocalGamesEntry.createIntent(context);

        assertNotNull(intent.getComponent());
        assertEquals("com.termux.localgames.activity.LocalGamesActivity",
            intent.getComponent().getClassName());
    }
}
