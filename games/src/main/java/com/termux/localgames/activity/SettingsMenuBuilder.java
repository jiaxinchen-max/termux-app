package com.termux.localgames.activity;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.localgames.R;

/** Builds the Settings page's menu rows into a vertical container: a heading, rows with a title, a
 *  current value or description and a chevron, and rows that open a single-choice selector. Matches the
 *  look of the game runtime options menus (frosted surface rows, 15.5sp title, 12sp secondary text). */
final class SettingsMenuBuilder {

    interface IndexCallback {
        void choose(int index);
    }

    private final Context context;
    private final LinearLayout container;

    SettingsMenuBuilder(Context context, LinearLayout container) {
        this.context = context;
        this.container = container;
        container.removeAllViews();
    }

    /**
     * The big heading only repeats the toolbar title when the toolbar is visible and already shows
     * the same text; in that case the page keeps the toolbar title and drops the heading.
     */
    static boolean repeatsToolbarTitle(boolean toolbarVisible, @Nullable CharSequence toolbarTitle,
                                       @Nullable CharSequence heading) {
        if (!toolbarVisible || toolbarTitle == null || heading == null) return false;
        String title = toolbarTitle.toString().trim();
        return !title.isEmpty() && title.equals(heading.toString().trim());
    }

    void heading(String value) {
        TextView heading = text(value, 26, R.color.local_games_on_surface);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), dp(4), dp(4), dp(18));
        container.addView(heading, params);
    }

    /** A menu row. A null {@code onClick} makes it a read-only row without a chevron. */
    void row(String label, @Nullable String summary, @Nullable View.OnClickListener onClick) {
        FrostedMenuRow.add(context, container, label, summary, 3, onClick != null, onClick);
    }

    /** A row showing the current choice; tapping it opens a single-choice selector. */
    void choice(String label, String[] options, int checked, IndexCallback onChoose) {
        String current = checked >= 0 && checked < options.length ? options[checked] : "";
        row(label, current, view -> new FrostedDialogBuilder(context,
            R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(label)
            .setSingleChoiceItems(options, checked, (dialog, which) -> {
                dialog.dismiss();
                onChoose.choose(which);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show());
    }

    private TextView text(String value, float sizeSp, int colorRes) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(ContextCompat.getColor(context, colorRes));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
