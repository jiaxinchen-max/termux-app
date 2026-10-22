package com.termux.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
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
import com.termux.x11.X11SessionView;
import com.termux.x11.X11SessionView.PointerMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Controller" entry of the in-game Operation panel and its drawer sub-page.
 *
 * <p>The entry row shows the profile currently attached to the on-screen controller (read live
 * from the session, so it never disagrees with the controller actually in use) and opens the sub-page
 * through {@code openPage}. The sub-page has three parts: an "Off" menu row, a list box that shows
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

    /** Padding between the list box's frame and its rows. */
    private static final int LIST_PADDING_DP = 8;

    private final Activity activity;
    private final X11SessionView session;
    private final LinearLayout entryHost;
    private final LinearLayout pageList;
    private final TextView notice;
    private final Listener listener;
    private final Runnable openPage;
    private final String mapperSelection;
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
                        @NonNull Listener listener, @NonNull Runnable openPage) {
        this.activity = activity;
        this.session = session;
        this.entryHost = entryHost;
        this.pageList = pageList;
        this.notice = notice;
        this.listener = listener;
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
        if (!controllerActive) {
            pageList.addView(sectionText(R.string.local_game_pointer_options_need_controller, false));
        }
        FrostedMenuRow.addToggle(activity, pageList,
            activity.getString(R.string.local_game_pointer_lock_cursor),
            activity.getString(R.string.local_game_pointer_lock_cursor_summary),
            GamePointerOptions.isLockCursor(mode), controllerActive,
            (button, on) -> applyPointerMode(GamePointerOptions.modeFor(on, false)));
        FrostedMenuRow.addToggle(activity, pageList,
            activity.getString(R.string.local_game_pointer_touch_screen),
            activity.getString(R.string.local_game_pointer_touch_screen_summary),
            GamePointerOptions.isTouchScreen(mode),
            controllerActive && GamePointerOptions.isTouchScreenSwitchEnabled(mode),
            (button, on) -> applyPointerMode(GamePointerOptions.modeFor(false, on)));
        FrostedMenuRow.addToggle(activity, pageList,
            activity.getString(R.string.local_game_pointer_show_controls),
            activity.getString(R.string.local_game_pointer_show_controls_summary),
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
        return GameInputProfileChoices.build(entries, session.getActiveInputProfileId(), offLabel(),
            activity.getString(R.string.local_game_controller_unnamed),
            activity.getString(R.string.local_game_controller_landscape),
            activity.getString(R.string.local_game_controller_portrait));
    }

    private String offLabel() {
        return activity.getString(R.string.local_game_controller_off);
    }
}
