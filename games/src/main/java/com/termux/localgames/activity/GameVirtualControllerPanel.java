package com.termux.localgames.activity;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.termux.localgames.R;
import com.termux.x11.X11SessionView;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Virtual controller" entry of the in-game Operation panel and its drawer sub-page.
 *
 * <p>The entry row shows the profile currently attached to the on-screen controller (read live
 * from the session, so it never disagrees with the controller actually in use) and opens the sub-page
 * through {@code openPage}. The sub-page lists Off and every profile as single-choice rows;
 * choosing one applies it at once and reports the new {@code inputProfileId} (mapper prefix kept,
 * see {@link GameInputProfileChoices}) to the {@link Listener} for persistence. An "Edit controls"
 * row opens the editor. Problems are reported as an inline notice on the page, never as a popup.</p>
 */
final class GameVirtualControllerPanel {

    /** Receives every selection the user made through this panel. */
    interface Listener {
        /** {@code inputProfileId} (valid LaunchSpec encoding) to remember for the next launch. */
        void onInputProfileSelected(@NonNull String inputProfileId);
    }

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

    GameVirtualControllerPanel(@NonNull Activity activity, @NonNull X11SessionView session,
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
            activity.getString(R.string.local_game_virtual_controller), summary, 1, true,
            view -> openPage.run());
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).bottomMargin = 0; // spacing comes from the host
            row.setLayoutParams(params);
        }
        row.setContentDescription(activity.getString(
            R.string.local_game_virtual_controller_description, summary));
    }

    private void renderPage(@NonNull List<GameInputProfileChoices.Choice> choices) {
        pageList.removeAllViews();
        for (GameInputProfileChoices.Choice choice : choices) {
            final int profileId = choice.profileId;
            FrostedMenuRow.addChoice(activity, pageList, choice.label, null, choice.checked,
                view -> select(profileId));
        }
        FrostedMenuRow.add(activity, pageList,
            activity.getString(R.string.local_game_virtual_controller_edit), null, 1, true,
            view -> openEditor());
    }

    private void select(int profileId) {
        String selection;
        try {
            selection = GameInputProfileChoices.compose(mapperSelection, profileId);
        } catch (IllegalArgumentException error) {
            showNotice(R.string.local_game_virtual_controller_apply_failed);
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
            showNotice(R.string.local_game_virtual_controller_apply_failed);
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
            showNotice(R.string.local_game_virtual_controller_editor_failed);
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
            entries.add(new GameInputProfileChoices.Entry(profile.getId(), profile.getName()));
        }
        return GameInputProfileChoices.build(entries, session.getActiveInputProfileId(), offLabel(),
            activity.getString(R.string.local_game_virtual_controller_unnamed));
    }

    private String offLabel() {
        return activity.getString(R.string.local_game_virtual_controller_off);
    }
}
