package com.termux.localgames.activity;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.localgames.R;

/**
 * The one menu-row construction shared by the Settings menu and the game runtime options menus:
 * a frosted row (6 dp radius, hairline) with a title, an optional summary and a chevron.
 * The title block and the chevron are both centred on the row's centre line; rows are at least
 * {@code local_games_menu_row_min_height} (48 dp) tall, also without a summary, and text
 * views drop the extra font padding that otherwise makes text sit high in its line box.
 */
final class FrostedMenuRow {

    static final int PADDING_START_DP = 18;
    static final int PADDING_END_DP = 14;
    static final int PADDING_VERTICAL_DP = 8;
    static final int ROW_GAP_DP = 8;
    static final int SUMMARY_GAP_DP = 2;
    static final int CHEVRON_BOX_DP = 24;
    static final float TITLE_SP = 15.5f;
    static final float SUMMARY_SP = 12f;

    private FrostedMenuRow() {}

    /**
     * Adds one menu row to {@code parent} and returns it. A null {@code onClick} (or
     * {@code interactive == false}) makes it a read-only row without a chevron.
     */
    static LinearLayout add(@NonNull Context context, @NonNull LinearLayout parent,
                            @NonNull CharSequence label, @Nullable CharSequence summary,
                            int summaryMaxLines, boolean interactive,
                            @Nullable View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, PADDING_START_DP), dp(context, PADDING_VERTICAL_DP),
            dp(context, PADDING_END_DP), dp(context, PADDING_VERTICAL_DP));
        row.setMinimumHeight(context.getResources().getDimensionPixelSize(R.dimen.local_games_menu_row_min_height));
        row.setBackground(FrostedWindow.surface(context, R.color.local_games_surface, FrostedWindow.ROW_RADIUS_DP));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(context, ROW_GAP_DP);
        parent.addView(row, rowParams);

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(text(context, label, TITLE_SP, R.color.local_games_on_surface));
        if (summary != null && summary.length() > 0) {
            TextView summaryView = text(context, summary, SUMMARY_SP, R.color.local_games_on_surface_secondary);
            summaryView.setMaxLines(summaryMaxLines);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            summaryParams.topMargin = dp(context, SUMMARY_GAP_DP);
            copy.addView(summaryView, summaryParams);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (interactive) {
            ImageView chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.local_games_ic_chevron_right);
            chevron.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.local_games_primary_text)));
            chevron.setScaleType(ImageView.ScaleType.CENTER);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(chevron, new LinearLayout.LayoutParams(
                dp(context, CHEVRON_BOX_DP), dp(context, CHEVRON_BOX_DP)));
            row.setClickable(true);
            row.setOnClickListener(onClick);
        }
        return row;
    }

    private static TextView text(Context context, CharSequence value, float sizeSp, @ColorRes int colorRes) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(ContextCompat.getColor(context, colorRes));
        view.setIncludeFontPadding(false);
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
