package com.termux.ui.activity;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.termux.ui.R;
import com.termux.ui.platform.IdePreference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises real Material inflation and measurement with the optional sixth destination. */
@RunWith(AndroidJUnit4.class)
public class IdeNavigationTest {
    @Test public void optionalSixthDestinationCanBeSelectedMeasuredAndHidden() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_TermuxLocalGames_DayNight_NoActionBar);
            View root = LayoutInflater.from(context).inflate(R.layout.view_ui_navigation, null);
            RootNavigationView navigation = root.findViewById(R.id.local_games_navigation);
            // Landscape inflation must not create an unused Material menu or label views.
            assertEquals(0, navigation.getMenu().size());
            navigation.prepareMenu(false, R.id.local_games_navigation_library);
            assertEquals(6, navigation.getMenu().size());
            assertFalse(navigation.getMenu().findItem(R.id.local_games_navigation_ide).isVisible());
            navigation.prepareMenu(true, R.id.local_games_navigation_ide);
            navigation.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(72, View.MeasureSpec.EXACTLY));
            navigation.layout(0, 0, 360, 72);
            assertEquals(R.id.local_games_navigation_ide, navigation.getSelectedItemId());
            View item = navigation.findViewById(R.id.local_games_navigation_ide);
            navigation.prepareMenu(true, R.id.local_games_navigation_ide);
            assertSame(item, navigation.findViewById(R.id.local_games_navigation_ide));
            navigation.prepareMenu(false, R.id.local_games_navigation_settings);
            assertFalse(navigation.getMenu().findItem(R.id.local_games_navigation_ide).isVisible());
            navigation.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(72, View.MeasureSpec.EXACTLY));
            assertEquals(R.id.local_games_navigation_settings, navigation.getSelectedItemId());
        });
    }

    @Test public void preferenceIsOffByDefaultAndSurvivesNewContext() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences preferences = context.getSharedPreferences(
            "local_games_settings", Context.MODE_PRIVATE);
        boolean existed = preferences.contains("ide_enabled");
        boolean previous = IdePreference.isEnabled(context);
        try {
            preferences.edit().remove("ide_enabled").commit();
            assertFalse(IdePreference.isEnabled(context));
            IdePreference.setEnabled(context, true);
            assertTrue(IdePreference.isEnabled(context.createConfigurationContext(
                context.getResources().getConfiguration())));
            IdePreference.setEnabled(context, false);
            assertFalse(IdePreference.isEnabled(context));
        } finally {
            if (existed) IdePreference.setEnabled(context, previous);
            else preferences.edit().remove("ide_enabled").commit();
        }
    }
}
