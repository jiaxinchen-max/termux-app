package com.termux.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import com.termux.ui.databinding.ViewUiNavigationBinding;

/** Shared navigation chrome for every root UI destination, including the host terminal. */
public final class UiNavigation {
    public static final String EXTRA_DESTINATION = "com.termux.ui.extra.ROOT_DESTINATION";
    private static final String EXTRA_CONTENT_TRANSITION = "com.termux.ui.extra.ROOT_CONTENT_TRANSITION";

    public interface DestinationListener {
        boolean select(int destination);
    }

    private final Activity activity;
    private final ViewUiNavigationBinding binding;
    private boolean updating;
    private boolean visible = true;
    private boolean landscape;
    private int selected;
    private int dispatchedStatusBarInsetPx = -1;
    private boolean dispatchedLandscape;

    public static UiNavigation install(Activity activity, int selected,
                                        @Nullable DestinationListener listener) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content.getChildCount() != 1) throw new IllegalStateException("ui_root_content_required");
        View page = content.getChildAt(0);
        content.removeView(page);
        UiNavigation navigation = new UiNavigation(activity, selected, listener);
        navigation.binding.uiNavigationContent.addView(page,
            new FrameLayout.LayoutParams(-1, -1));
        activity.setContentView(navigation.binding.getRoot());
        ((LifecycleOwner) activity).getLifecycle().addObserver((androidx.lifecycle.LifecycleEventObserver)
            (owner, event) -> {
                // Termux consumes its launch Intent when connecting to the session service.
                Intent intent = activity.getIntent();
                if (event == Lifecycle.Event.ON_RESUME) {
                    navigation.applySystemBars();
                    navigation.binding.getRoot().post(navigation::applySystemBars);
                }
                if (event == Lifecycle.Event.ON_RESUME && intent != null
                    && intent.getBooleanExtra(EXTRA_CONTENT_TRANSITION, false)) {
                    intent.removeExtra(EXTRA_CONTENT_TRANSITION);
                    activity.overridePendingTransition(0, 0);
                    navigation.animateContentChange();
                }
            });
        return navigation;
    }

    private UiNavigation(Activity activity, int selected, @Nullable DestinationListener listener) {
        this.activity = activity;
        ContextThemeWrapper context = new ContextThemeWrapper(activity,
            R.style.Theme_TermuxLocalGames_DayNight_NoActionBar);
        binding = ViewUiNavigationBinding.inflate(LayoutInflater.from(context));
        applySystemBars();
        this.selected = selected;
        setSelected(selected);
        binding.localGamesNavigation.setOnItemSelectedListener(item -> {
            if (updating) return true;
            int destination = item.getItemId();
            if (listener != null) return listener.select(destination);
            if (destination == this.selected) return true;
            openDestination(activity, destination);
            return false;
        });
        int[] ids = destinations();
        View[] tabs = topTabs();
        for (int index = 0; index < ids.length; index++) {
            final int destination = ids[index];
            tabs[index].setOnClickListener(view -> {
                if (listener != null) listener.select(destination);
                else if (destination != this.selected) openDestination(activity, destination);
            });
        }
        WindowInsetsPadding.apply(binding.getRoot(), true, true, true, true);
        // The shell already reserves system bars; Material must not pad the menu a second time.
        ViewCompat.setOnApplyWindowInsetsListener(binding.localGamesNavigation,
            (view, insets) -> insets);
        ViewCompat.setOnApplyWindowInsetsListener(binding.localGamesTopTabs, (view, insets) -> {
            dispatchedStatusBarInsetPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            dispatchedLandscape = isLandscape();
            applyTopTabHeight();
            return insets;
        });
        updateConfiguration();
    }

    private void applySystemBars() {
        // Reapply after attachment/resume: night-mode recreation can otherwise retain
        // the previous window's icon appearance despite resolving the new background.
        android.content.Context context = binding.getRoot().getContext();
        activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            | android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int barColor = ContextCompat.getColor(context, android.os.Build.VERSION.SDK_INT >= 27
            ? R.color.local_games_background : R.color.local_games_system_bar_legacy);
        activity.getWindow().setStatusBarColor(barColor);
        activity.getWindow().setNavigationBarColor(barColor);
        boolean lightBars = android.os.Build.VERSION.SDK_INT >= 27
            && context.getResources().getBoolean(R.bool.local_games_light_system_bars);
        WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(
            activity.getWindow(), activity.getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(lightBars);
        bars.setAppearanceLightNavigationBars(lightBars);
    }

    /** Reorders existing root pages instead of stacking another copy of each destination. */
    public static void openDestination(Activity activity, int destination) {
        Intent intent;
        if (destination == R.id.local_games_navigation_library ||
            destination == R.id.local_games_navigation_settings) {
            intent = LocalGames.createLaunchIntent(activity).putExtra(EXTRA_DESTINATION, destination);
        } else if (destination == R.id.local_games_navigation_desktop) {
            intent = LocalGames.createDesktopIntent(activity);
        } else if (destination == R.id.local_games_navigation_file_manager) {
            intent = LocalGames.createFileManagerIntent(activity);
        } else if (destination == R.id.local_games_navigation_terminal) {
            LocalGames.requireHost(activity).openTerminal();
            activity.overridePendingTransition(0, 0);
            return;
        } else {
            throw new IllegalArgumentException("ui_destination_unknown");
        }
        activity.startActivity(tabIntent(intent).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
            Intent.FLAG_ACTIVITY_SINGLE_TOP));
        activity.overridePendingTransition(0, 0);
    }

    /** Root tab changes never translate the Activity window containing the navigation chrome. */
    public static Intent tabIntent(Intent intent) {
        return intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_CONTENT_TRANSITION, true);
    }

    public void animateContentChange() {
        binding.uiNavigationContent.animate().cancel();
        binding.uiNavigationContent.setAlpha(0f);
        binding.uiNavigationContent.animate().alpha(1f).setDuration(140).start();
    }

    public void setSelected(int destination) {
        selected = destination;
        updating = true;
        try {
            binding.localGamesNavigation.setSelectedItemId(destination);
            int[] ids = destinations();
            View[] tabs = topTabs();
            for (int index = 0; index < ids.length; index++) tabs[index].setSelected(ids[index] == destination);
        } finally {
            updating = false;
        }
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        updateConfiguration();
    }

    public void updateConfiguration() {
        boolean next = isLandscape();
        if (landscape != next) dispatchedStatusBarInsetPx = -1;
        landscape = next;
        binding.localGamesTopTabs.setVisibility(visible && landscape ? View.VISIBLE : View.GONE);
        binding.localGamesNavigation.setVisibility(visible && !landscape ? View.VISIBLE : View.GONE);
        binding.localGamesNavigationDivider.setVisibility(visible && !landscape ? View.VISIBLE : View.GONE);
        if (visible && landscape) {
            applyTopTabHeight();
            ViewCompat.requestApplyInsets(binding.localGamesTopTabs);
            binding.localGamesTopTabs.post(this::applyTopTabHeight);
        }
    }

    private boolean isLandscape() {
        return activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    @SuppressLint({"DiscouragedApi", "InternalInsetResource"})
    private void applyTopTabHeight() {
        if (!visible || !isLandscape()) return;
        int insets = TopTabBarHeight.usableCachedInset(dispatchedStatusBarInsetPx,
            dispatchedLandscape, true);
        int resource = 0;
        for (String name : TopTabBarHeight.statusBarResourceNames(true)) {
            int id = activity.getResources().getIdentifier(name, "dimen", "android");
            if (id > 0) resource = activity.getResources().getDimensionPixelSize(id);
            if (resource > 0) break;
        }
        int height = TopTabBarHeight.resolve(insets, resource,
            activity.getResources().getDimensionPixelSize(R.dimen.local_games_top_tabs_fallback_height));
        ViewGroup.LayoutParams params = binding.localGamesTopTabs.getLayoutParams();
        if (params.height != height) {
            params.height = height;
            binding.localGamesTopTabs.setLayoutParams(params);
        }
    }

    private static int[] destinations() {
        return new int[] {R.id.local_games_navigation_library, R.id.local_games_navigation_desktop,
            R.id.local_games_navigation_terminal, R.id.local_games_navigation_file_manager,
            R.id.local_games_navigation_settings};
    }

    private View[] topTabs() {
        return new View[] {binding.localGamesTopTabLibrary, binding.localGamesTopTabDesktop,
            binding.localGamesTopTabTerminal, binding.localGamesTopTabFileManager,
            binding.localGamesTopTabSettings};
    }
}
