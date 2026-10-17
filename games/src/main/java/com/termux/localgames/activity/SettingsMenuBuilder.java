package com.termux.localgames.activity;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.localgames.R;

/** Builds the Settings page's menu rows into a vertical container: a heading, rows with a title, a
 *  current value or description and a chevron, and rows that open a single-choice selector. Matches the
 *  look of the game runtime options menus (rounded surface rows, 17sp title, 13sp secondary text). */
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

    void heading(String value) {
        TextView heading = text(value, 26, R.color.local_games_on_surface);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), dp(4), dp(4), dp(18));
        container.addView(heading, params);
    }

    /** A menu row. A null {@code onClick} makes it a read-only row without a chevron. */
    void row(String label, @Nullable String summary, @Nullable View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(13), dp(14), dp(13));
        row.setBackground(rowBackground());
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        container.addView(row, rowParams);

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(label, 17, R.color.local_games_on_surface));
        if (summary != null && !summary.isEmpty()) {
            TextView summaryView = text(summary, 13, R.color.local_games_on_surface_secondary);
            summaryView.setMaxLines(3);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            summaryParams.topMargin = dp(3);
            copy.addView(summaryView, summaryParams);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (onClick != null) {
            TextView arrow = text("›", 30, R.color.local_games_primary);
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(36)));
            row.setClickable(true);
            row.setOnClickListener(onClick);
        }
    }

    /** A row showing the current choice; tapping it opens a single-choice selector. */
    void choice(String label, String[] options, int checked, IndexCallback onChoose) {
        String current = checked >= 0 && checked < options.length ? options[checked] : "";
        row(label, current, view -> new MaterialAlertDialogBuilder(context,
            R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(label)
            .setSingleChoiceItems(options, checked, (dialog, which) -> {
                dialog.dismiss();
                onChoose.choose(which);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show());
    }

    private TextView text(String value, int sizeSp, int colorRes) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(ContextCompat.getColor(context, colorRes));
        return view;
    }

    private GradientDrawable rowBackground() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ContextCompat.getColor(context, R.color.local_games_surface));
        shape.setCornerRadius(dp(18));
        shape.setStroke(dp(1), ContextCompat.getColor(context, R.color.local_games_outline));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
