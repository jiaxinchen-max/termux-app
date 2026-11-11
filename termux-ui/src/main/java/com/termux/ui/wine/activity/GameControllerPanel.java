package com.termux.ui.wine.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.termux.ui.R;
import com.termux.ui.activity.FrostedMenuRow;
import com.termux.ui.activity.FrostedWindow;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.x11.X11SessionView;
import com.termux.x11.X11SessionView.PointerMode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The "Controller" entry of the in-game Operation panel and its drawer sub-page.
 *
 * <p>The entry row shows the profile currently attached to the on-screen controller (read live
 * from the session, so it never disagrees with the controller actually in use) and opens the sub-page
 * through {@code openPage}. The sub-page starts with independent API switches and a next-launch mapping picker, followed by
 * an "Off" menu row, a list box that shows
 * four profile rows at a time and scrolls inside itself (see {@link GameControllerListSizing}), and an
 * "Edit controls" menu row that opens the editor, so Off and Edit stay visible however long the list
 * is. Choosing a row applies it at once and reports the new {@code inputProfileId} (mapper prefix
 * kept, see {@link GameInputProfileChoices}) to the {@link Listener} for persistence. Below them an "Input options" section holds the Lock cursor, Touch screen
 * mode and Show touchscreen controls switches, read live from the session and applied at once; they
 * only mean something while a controller is active and are disabled while Off is selected. Problems
 * are reported as an inline notice on the page, never as a popup.</p>
 */
final class GameControllerPanel {

    /** Receives every selection the user made through this panel. */
    interface Listener {
        /** {@code inputProfileId} (valid LaunchSpec encoding) to remember for the next launch. */
        void onInputProfileSelected(@NonNull String inputProfileId);
    }

    interface SettingsListener {
        void onMapperSelected(@NonNull String mapper);
        void onInputApiSelected(boolean xinputApi, boolean enabled);
    }

    enum Setting { LOAD, MAPPING, XINPUT, DINPUT, TOUCH_PROFILE }

    /** Padding between the list box's frame and its rows. */
    private static final int LIST_PADDING_DP = 8;

    private final Activity activity;
    private final X11SessionView session;
    private final LinearLayout entryHost;
    private final LinearLayout pageList;
    private final TextView notice;
    private final Listener listener;
    private final SettingsListener settingsListener;
    private final Runnable openPage;
    private final String mapperSelection;
    @Nullable private RuntimeProfile savedSettings;
    private final EnumSet<Setting> pendingSettings = EnumSet.of(Setting.LOAD);
    private boolean syncingSettings;
    @Nullable private LinearLayout apiRow, mappingRow, mappingChoices;
    @Nullable private SwitchMaterial xinputSwitch, dinputSwitch, mappingSwitch;
    @Nullable private TextView mappingStatus;
    /** Profile id last applied/persisted through this panel; 0 = off. */
    private int trackedProfileId;
    /** The profile list box of the last render; its scroll position is carried over to the next one. */
    @Nullable private ScrollView profileViewport;
    private int profileScrollY;
    /** True until the first render: the selected profile is scrolled into the list box's view once. */
    private boolean revealSelected = true;

    GameControllerPanel(@NonNull Activity activity, @NonNull X11SessionView session,
                        @NonNull LinearLayout entryHost, @NonNull LinearLayout pageList,
                        @NonNull TextView notice, @NonNull String initialSelection,
                        @NonNull Listener listener, @NonNull SettingsListener settingsListener,
                        @NonNull Runnable openPage) {
        this.activity = activity;
        this.session = session;
        this.entryHost = entryHost;
        this.pageList = pageList;
        this.notice = notice;
        this.listener = listener;
        this.settingsListener = settingsListener;
        this.openPage = openPage;
        String mapper = GameInputProfileChoices.mapperOf(initialSelection);
        this.mapperSelection = mapper == null ? "xinput" : mapper;
        this.trackedProfileId = GameInputProfileChoices.profileIdOf(initialSelection);
    }

    /** Rebuilds the entry row and the sub-page rows from the live session state. */
    void render() {
        List<GameInputProfileChoices.Choice> choices = currentChoices();
        renderEntry(choices);
        renderPage(choices);
    }

    /**
     * Forwards an Activity result to the session. When the editor returned and the profile this
     * panel had selected no longer exists (deleted in the editor), the selection falls back to Off
     * and is reported for persistence.
     */
    boolean onHostActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        boolean handled = session.onHostActivityResult(requestCode, resultCode, data);
        if (!handled) return false;
        if (trackedProfileId != GameInputProfileChoices.OFF && !profileExists(trackedProfileId)) {
            trackedProfileId = GameInputProfileChoices.OFF;
            listener.onInputProfileSelected(
                GameInputProfileChoices.compose(mapperSelection, GameInputProfileChoices.OFF));
        }
        render();
        return true;
    }

    /** Shows {@code messageRes} on the sub-page (below the rows). */
    void showNotice(@StringRes int messageRes) {
        notice.setText(messageRes);
        notice.setVisibility(View.VISIBLE);
    }

    void clearNotice() {
        notice.setText("");
        notice.setVisibility(View.GONE);
    }

    void settingsLoading(@NonNull Setting setting) {
        pendingSettings.add(setting);
        updateSettingsControls();
    }

    /** Called only after the settings were successfully read/saved and API flags applied. */
    void settingsSaved(@NonNull RuntimeProfile profile, @NonNull Setting setting) {
        savedSettings = profile;
        pendingSettings.remove(setting);
        clearNotice();
        updateSettingsControls();
    }

    void settingsFailed(@NonNull Setting setting) {
        pendingSettings.remove(setting);
        updateSettingsControls();
        showNotice(R.string.local_game_controller_settings_failed);
    }

    private void renderEntry(@NonNull List<GameInputProfileChoices.Choice> choices) {
        entryHost.removeAllViews();
        String summary = GameInputProfileChoices.summary(choices, offLabel());
        LinearLayout row = FrostedMenuRow.add(activity, entryHost,
            activity.getString(R.string.local_game_controller), summary, 1, true,
            view -> openPage.run());
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).bottomMargin = 0; // spacing comes from the host
            row.setLayoutParams(params);
        }
        row.setContentDescription(activity.getString(
            R.string.local_game_controller_description, summary));
    }

    private void renderPage(@NonNull List<GameInputProfileChoices.Choice> choices) {
        if (profileViewport != null) profileScrollY = profileViewport.getScrollY();
        pageList.removeAllViews();
        renderControllerSettings();
        GameInputProfileChoices.Choice off = null;
        List<GameInputProfileChoices.Choice> profiles = new ArrayList<>();
        for (GameInputProfileChoices.Choice choice : choices) {
            if (choice.profileId == GameInputProfileChoices.OFF) off = choice;
            else profiles.add(choice);
        }
        if (off != null) {
            FrostedMenuRow.addChoice(activity, pageList, off.label, null, off.checked,
                view -> select(GameInputProfileChoices.OFF));
        }
        renderProfileList(profiles);
        FrostedMenuRow.add(activity, pageList,
            activity.getString(R.string.local_game_controller_edit), null, 1, true,
            view -> openEditor());
        renderOptions();
    }

    private void renderControllerSettings() {
        // Full page renders reuse these controls too. Save callbacks update them in place.
        if (apiRow != null) {
            pageList.addView(apiRow);
            pageList.addView(mappingRow);
            updateSettingsControls();
            return;
        }
        LinearLayout apis = new LinearLayout(activity);
        apiRow = apis;
        apis.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams apiParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apiParams.bottomMargin = dp(FrostedMenuRow.ROW_GAP_DP);
        pageList.addView(apis, apiParams);
        xinputSwitch = addApiSwitch(apis, true);
        dinputSwitch = addApiSwitch(apis, false);
        LinearLayout mapping = FrostedMenuRow.add(activity, pageList,
            activity.getString(R.string.local_game_runtime_gamepad_mapping), mappingSummary(), 2, false, null);
        mappingRow = mapping;
        mappingStatus = (TextView) ((LinearLayout) mapping.getChildAt(0)).getChildAt(1);
        mapping.setOrientation(LinearLayout.VERTICAL);
        mapping.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout choices = new LinearLayout(activity);
        mappingChoices = choices;
        choices.setGravity(Gravity.CENTER);
        TextView xinput = text(R.string.local_game_controller_mapping_xinput, 14f);
        TextView dinput = text(R.string.local_game_controller_mapping_dinput, 14f);
        xinput.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        dinput.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        SwitchMaterial toggle = new SwitchMaterial(activity);
        mappingSwitch = toggle;
        toggle.setId(R.id.game_controller_mapping_switch);
        toggle.setContentDescription(activity.getString(R.string.local_game_runtime_gamepad_mapping));
        toggle.setTextOff(mapperLabel("xinput"));
        toggle.setTextOn(mapperLabel("dinput"));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingSettings) settingsListener.onMapperSelected(checked ? "dinput" : "xinput");
        });
        choices.addView(xinput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        toggleParams.setMarginStart(dp(10));
        toggleParams.setMarginEnd(dp(10));
        choices.addView(toggle, toggleParams);
        choices.addView(dinput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mapping.addView(choices, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updateSettingsControls();
    }

    private SwitchMaterial addApiSwitch(LinearLayout parent, boolean xinput) {
        SwitchMaterial toggle = new SwitchMaterial(activity);
        toggle.setId(xinput ? R.id.game_controller_xinput_switch : R.id.game_controller_dinput_switch);
        toggle.setText(xinput ? R.string.local_game_controller_mapping_xinput
            : R.string.local_game_controller_mapping_dinput);
        toggle.setTextSize(14f);
        toggle.setTextColor(ContextCompat.getColor(activity, R.color.local_games_on_surface));
        toggle.setSingleLine(true);
        toggle.setSwitchPadding(dp(4));
        toggle.setSwitchMinWidth(dp(36));
        toggle.setPadding(dp(6), 0, dp(6), 0);
        toggle.setMinHeight(dp(48));
        toggle.setBackground(FrostedWindow.surface(activity, R.color.local_games_surface,
            FrostedWindow.ROW_RADIUS_DP));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingSettings) settingsListener.onInputApiSelected(xinput, checked);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (xinput) params.setMarginEnd(dp(FrostedMenuRow.ROW_GAP_DP));
        parent.addView(toggle, params);
        return toggle;
    }

    /** Preserve optimistic positions of other queued edits; syncing never initiates another save. */
    private void updateSettingsControls() {
        if (mappingSwitch == null) return;
        syncingSettings = true;
        try {
            updateSettingSwitch(xinputSwitch, Setting.XINPUT,
                savedSettings != null && savedSettings.isXinputEnabled());
            updateSettingSwitch(dinputSwitch, Setting.DINPUT,
                savedSettings != null && savedSettings.isDinputEnabled());
            String mapper = savedSettings == null ? mapperSelection
                : GameInputProfileChoices.mapperOf(savedSettings.getInputProfileId());
            updateSettingSwitch(mappingSwitch, Setting.MAPPING, "dinput".equals(mapper));
            mappingChoices.setAlpha(mappingSwitch.isEnabled() ? 1f : 0.5f);
            String summary = mappingSummary();
            if (!summary.contentEquals(mappingStatus.getText())) mappingStatus.setText(summary);
        } finally {
            syncingSettings = false;
        }
    }

    private void updateSettingSwitch(SwitchMaterial toggle, Setting setting, boolean checked) {
        boolean pending = pendingSettings.contains(setting);
        if (!pending && toggle.isChecked() != checked) toggle.setChecked(checked);
        boolean enabled = savedSettings != null && !pendingSettings.contains(Setting.LOAD) && !pending;
        if (toggle.isEnabled() != enabled) toggle.setEnabled(enabled);
        // The mapping's parent dims its labels and switch together.
        float alpha = enabled || setting == Setting.MAPPING ? 1f : 0.5f;
        if (toggle.getAlpha() != alpha) toggle.setAlpha(alpha);
    }

    private String mappingSummary() {
        if (savedSettings == null) return activity.getString(pendingSettings.contains(Setting.LOAD)
            ? R.string.local_game_controller_settings_loading : R.string.local_game_controller_settings_failed);
        String mapper = GameInputProfileChoices.mapperOf(savedSettings.getInputProfileId());
        return mapperSelection.equals(mapper)
            ? activity.getString(R.string.local_game_controller_mapping_next_launch)
            : activity.getString(R.string.local_game_controller_mapping_state, mapperLabel(mapperSelection));
    }

    private String mapperLabel(String mapper) {
        return activity.getString("dinput".equals(mapper)
            ? R.string.local_game_controller_mapping_dinput : R.string.local_game_controller_mapping_xinput);
    }

    /**
     * The profiles in a list box of a fixed four-row height that scrolls inside itself. It keeps its
     * height with fewer profiles, shows a hint with none, keeps its scroll position across renders
     * and, the first time, scrolls the selected profile into view.
     */
    private void renderProfileList(@NonNull List<GameInputProfileChoices.Choice> profiles) {
        ScrollView viewport = new ScrollView(activity);
        viewport.setBackground(FrostedWindow.surface(activity, R.color.local_games_surface,
            FrostedWindow.ROW_RADIUS_DP));
        viewport.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(LIST_PADDING_DP);
        rows.setPadding(padding, padding, padding, 0); // the last row's gap is the bottom padding
        View selectedRow = null;
        for (GameInputProfileChoices.Choice choice : profiles) {
            final int profileId = choice.profileId;
            LinearLayout row = FrostedMenuRow.addChoice(activity, rows, choice.label, null,
                choice.checked, view -> select(profileId));
            if (choice.checked) selectedRow = row;
        }
        if (profiles.isEmpty()) {
            TextView hint = text(R.string.local_game_controller_none, 13f);
            hint.setPadding(dp(10), dp(8), dp(10), dp(16));
            rows.addView(hint);
        }
        viewport.addView(rows, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int height = GameControllerListSizing.viewportHeightPx(
            activity.getResources().getDimensionPixelSize(R.dimen.local_games_menu_row_min_height),
            dp(FrostedMenuRow.ROW_GAP_DP), padding);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.bottomMargin = dp(FrostedMenuRow.ROW_GAP_DP);
        pageList.addView(viewport, params);
        keepDragsInside(viewport);
        profileViewport = viewport;

        final View reveal = revealSelected ? selectedRow : null;
        final int restoreY = profileScrollY;
        revealSelected = false;
        viewport.post(() -> {
            if (reveal == null) {
                viewport.scrollTo(0, restoreY);
            } else if (reveal.getTop() < viewport.getScrollY()) {
                viewport.scrollTo(0, reveal.getTop() - padding);
            } else if (reveal.getBottom() > viewport.getScrollY() + viewport.getHeight()) {
                viewport.scrollTo(0, reveal.getBottom() - viewport.getHeight() + padding);
            }
        });
    }

    /**
     * While a drag starts inside a list box that can scroll, the page behind it must not take it over.
     * The listener only steers that decision and never consumes the event, so there is no click for
     * it to perform.
     */
    @SuppressLint("ClickableViewAccessibility")
    private static void keepDragsInside(@NonNull ScrollView list) {
        list.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN
                && (view.canScrollVertically(1) || view.canScrollVertically(-1))) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
    }

    /** The Input options section: three switches over the live pointer mode / overlay state. */
    private void renderOptions() {
        boolean controllerActive = session.getActiveInputProfileId() != 0;
        PointerMode mode = session.getPointerMode();
        pageList.addView(sectionText(R.string.local_game_pointer_options_title, true));
        FrostedMenuRow.addToggle(activity, pageList,
            activity.getString(R.string.local_game_pointer_lock_cursor), null,
            GamePointerOptions.isLockCursor(mode), controllerActive,
            (button, on) -> applyPointerMode(GamePointerOptions.modeFor(on, false)));
        FrostedMenuRow.addToggle(activity, pageList,
            activity.getString(R.string.local_game_pointer_touch_screen), null,
            GamePointerOptions.isTouchScreen(mode),
            controllerActive && GamePointerOptions.isTouchScreenSwitchEnabled(mode),
            (button, on) -> applyPointerMode(GamePointerOptions.modeFor(false, on)));
        FrostedMenuRow.addToggle(activity, pageList,
            activity.getString(R.string.local_game_pointer_show_controls), null,
            session.isShowTouchscreenControls(), controllerActive,
            (button, on) -> applyShowControls(on));
    }

    private TextView sectionText(@StringRes int textRes, boolean heading) {
        TextView view = text(textRes, 12f);
        if (heading) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(heading ? 12 : 0);
        params.bottomMargin = dp(8);
        view.setLayoutParams(params);
        return view;
    }

    /** A secondary-colored text line of the page. */
    private TextView text(@StringRes int textRes, float sizeSp) {
        TextView view = new TextView(activity);
        view.setText(textRes);
        view.setTextSize(sizeSp);
        view.setTextColor(ContextCompat.getColor(activity, R.color.local_games_on_surface_secondary));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void applyPointerMode(@NonNull PointerMode mode) {
        boolean applied;
        try {
            applied = session.setPointerMode(mode);
        } catch (IllegalStateException error) {
            applied = false;
        }
        finishOptionChange(applied);
    }

    private void applyShowControls(boolean show) {
        boolean applied;
        try {
            applied = session.setShowTouchscreenControls(show);
        } catch (IllegalStateException error) {
            applied = false;
        }
        finishOptionChange(applied);
    }

    /** Re-reads the real state into the page; a failed change leaves the inline notice. */
    private void finishOptionChange(boolean applied) {
        if (applied) clearNotice();
        render();
        if (!applied) showNotice(R.string.local_game_pointer_apply_failed);
    }

    private void select(int profileId) {
        String selection;
        try {
            selection = GameInputProfileChoices.compose(mapperSelection, profileId);
        } catch (IllegalArgumentException error) {
            showNotice(R.string.local_game_controller_apply_failed);
            return;
        }
        boolean applied;
        try {
            applied = session.applyInputProfile(selection);
        } catch (IllegalStateException error) {
            applied = false;
        }
        if (!applied) {
            render();
            showNotice(R.string.local_game_controller_apply_failed);
            return;
        }
        trackedProfileId = profileId;
        clearNotice();
        render();
        listener.onInputProfileSelected(selection);
    }

    private void openEditor() {
        try {
            session.openInputControlsEditor();
            clearNotice();
        } catch (RuntimeException error) { // ActivityNotFoundException, unbound session
            showNotice(R.string.local_game_controller_editor_failed);
        }
    }

    private boolean profileExists(int profileId) {
        for (X11SessionView.InputProfile profile : session.getInputControlsProfiles()) {
            if (profile.getId() == profileId) return true;
        }
        return false;
    }

    private List<GameInputProfileChoices.Choice> currentChoices() {
        List<GameInputProfileChoices.Entry> entries = new ArrayList<>();
        for (X11SessionView.InputProfile profile : session.getInputControlsProfiles()) {
            entries.add(new GameInputProfileChoices.Entry(profile.getId(), profile.getName(),
                profile.isPortrait()));
        }
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        boolean portraitScreen = metrics.heightPixels > metrics.widthPixels;
        entries = GameInputProfileChoices.forScreenOrientation(entries, portraitScreen,
            session.getActiveInputProfileId());
        return GameInputProfileChoices.build(entries, session.getActiveInputProfileId(), offLabel(),
            activity.getString(R.string.local_game_controller_unnamed),
            activity.getString(R.string.local_game_controller_landscape),
            activity.getString(R.string.local_game_controller_portrait));
    }

    private String offLabel() {
        return activity.getString(R.string.local_game_controller_off);
    }
}
