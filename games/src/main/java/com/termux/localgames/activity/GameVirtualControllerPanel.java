package com.termux.localgames.activity;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.termux.localgames.R;
import com.termux.x11.X11SessionView;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Virtual controller" row of the in-game Operation panel plus its Frosted profile dialog.
 *
 * <p>The row shows the profile currently attached to the on-screen controller (read live from the
 * session, so it never disagrees with the legacy Input dialog). Choosing a line in the dialog applies
 * it at once and reports the new {@code inputProfileId} (mapper prefix kept, see
 * {@link GameInputProfileChoices}) to the {@link Listener} for persistence.</p>
 */
final class GameVirtualControllerPanel {

    /** Receives every selection the user made through this panel. */
    interface Listener {
        /** {@code inputProfileId} (valid LaunchSpec encoding) to remember for the next launch. */
        void onInputProfileSelected(@NonNull String inputProfileId);
    }

    private static final int ROW_MIN_HEIGHT_DP = 48;

    private final Activity activity;
    private final X11SessionView session;
    private final LinearLayout host;
    private final Listener listener;
    private final String mapperSelection;
    /** Profile id last applied/persisted through this panel; 0 = off. */
    private int trackedProfileId;
    @Nullable private AlertDialog dialog;

    GameVirtualControllerPanel(@NonNull Activity activity, @NonNull X11SessionView session,
                               @NonNull LinearLayout host, @NonNull String initialSelection,
                               @NonNull Listener listener) {
        this.activity = activity;
        this.session = session;
        this.host = host;
        this.listener = listener;
        String mapper = GameInputProfileChoices.mapperOf(initialSelection);
        this.mapperSelection = mapper == null ? "xinput" : mapper;
        this.trackedProfileId = GameInputProfileChoices.profileIdOf(initialSelection);
    }

    /** Rebuilds the row from the live session state (call when the drawer opens). */
    void render() {
        host.removeAllViews();
        List<GameInputProfileChoices.Choice> choices = currentChoices();
        String summary = GameInputProfileChoices.summary(choices, offLabel());
        LinearLayout row = FrostedMenuRow.add(activity, host,
            activity.getString(R.string.local_game_virtual_controller), summary, 1, true,
            view -> showDialog());
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).bottomMargin = 0; // spacing comes from the host
            row.setLayoutParams(params);
        }
        row.setContentDescription(activity.getString(
            R.string.local_game_virtual_controller_description, summary));
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

    /** Closes the dialog (Activity going away). */
    void dismiss() {
        AlertDialog shown = dialog;
        dialog = null;
        if (shown != null && shown.isShowing()) shown.dismiss();
    }

    private void showDialog() {
        if (dialog != null && dialog.isShowing()) return;
        List<GameInputProfileChoices.Choice> choices = currentChoices();
        List<String> labels = new ArrayList<>();
        for (GameInputProfileChoices.Choice choice : choices) labels.add(choice.label);
        int checked = GameInputProfileChoices.checkedIndex(choices);

        ListView list = new ListView(activity);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(new ArrayAdapter<String>(activity,
            android.R.layout.simple_list_item_single_choice, labels) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                row.setMinimumHeight(dp(ROW_MIN_HEIGHT_DP));
                if (row instanceof TextView) ((TextView) row).setMinHeight(dp(ROW_MIN_HEIGHT_DP));
                return row;
            }
        });
        list.setItemChecked(checked, true);
        ConfigurationAwareFrameLayout content = new ConfigurationAwareFrameLayout(activity);
        content.addView(list, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, listHeight()));
        content.setListener(configuration -> {
            ViewGroup.LayoutParams params = list.getLayoutParams();
            int height = listHeight();
            if (params != null && params.height != height) {
                params.height = height;
                list.setLayoutParams(params);
            }
        });

        AlertDialog created = new FrostedDialogBuilder(activity)
            .setTitle(R.string.local_game_virtual_controller)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.local_game_virtual_controller_edit,
                (d, which) -> openEditor())
            .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            created.dismiss();
            select(choices.get(position).profileId);
        });
        created.setOnDismissListener((DialogInterface d) -> {
            if (dialog == created) dialog = null;
        });
        dialog = created;
        created.show();
    }

    private void select(int profileId) {
        String selection;
        try {
            selection = GameInputProfileChoices.compose(mapperSelection, profileId);
        } catch (IllegalArgumentException error) {
            toast(R.string.local_game_virtual_controller_apply_failed);
            return;
        }
        boolean applied;
        try {
            applied = session.applyInputProfile(selection);
        } catch (IllegalStateException error) {
            applied = false;
        }
        if (!applied) {
            toast(R.string.local_game_virtual_controller_apply_failed);
            render();
            return;
        }
        trackedProfileId = profileId;
        render();
        listener.onInputProfileSelected(selection);
    }

    private void openEditor() {
        try {
            session.openInputControlsEditor();
        } catch (RuntimeException error) { // ActivityNotFoundException, unbound session
            toast(R.string.local_game_virtual_controller_editor_failed);
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

    private int listHeight() {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        return DialogSizing.listHeightPx(metrics.heightPixels, metrics.density);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void toast(int messageRes) {
        Toast.makeText(activity, messageRes, Toast.LENGTH_LONG).show();
    }
}
