package com.termux.ui.activity;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.ui.R;

/**
 * The one menu-row construction shared by the Settings menu and the game runtime options menus:
 * a frosted row (6 dp radius, hairline) with a title, an optional summary and a chevron.
 * The title block and the chevron are both centred on the row's centre line; rows are at least
 * {@code local_games_menu_row_min_height} (48 dp) tall, also without a summary, and text
 * views drop the extra font padding that otherwise makes text sit high in its line box.
 *
 * <p>{@link #addChoice} is the single-choice variant used by in-drawer pages: the same row, but
 * the selected one is tinted with an accent outline and ends with a check mark instead of a chevron.
 * {@link #addToggle} is the on/off variant: the same row ending in a switch, dimmed when disabled.</p>
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
        LinearLayout row = build(context, parent, label, summary, summaryMaxLines,
            FrostedWindow.surface(context, R.color.local_games_surface, FrostedWindow.ROW_RADIUS_DP));
        if (interactive) {
            addTrailingIcon(context, row, R.drawable.local_games_ic_chevron_right);
            row.setClickable(true);
            row.setOnClickListener(onClick);
        }
        return row;
    }

    /**
     * Adds one single-choice row: like {@link #add} but the {@code selected} row gets the accent
     * outline, a tinted fill and a trailing check mark (and is reported as selected to
     * accessibility), while the others end without an icon. Always clickable.
     */
    static LinearLayout addChoice(@NonNull Context context, @NonNull LinearLayout parent,
                                  @NonNull CharSequence label, @Nullable CharSequence summary,
                                  boolean selected, @Nullable View.OnClickListener onClick) {
        Drawable background = selected
            ? selectedSurface(context)
            : FrostedWindow.surface(context, R.color.local_games_surface, FrostedWindow.ROW_RADIUS_DP);
        LinearLayout row = build(context, parent, label, summary, 1, background);
        if (selected) addTrailingIcon(context, row, R.drawable.local_games_ic_check);
        row.setSelected(selected);
        row.setClickable(true);
        row.setOnClickListener(onClick);
        return row;
    }

    /**
     * Adds one on/off row: like {@link #add} but it ends with a switch and the whole row toggles it.
     * A disabled row is dimmed and ignores touches. {@code onChanged} only reports changes made by
     * the user, never the initial {@code checked} state.
     */
    static LinearLayout addToggle(@NonNull Context context, @NonNull LinearLayout parent,
                                  @NonNull CharSequence label, @Nullable CharSequence summary,
                                  boolean checked, boolean enabled,
                                  @NonNull CompoundButton.OnCheckedChangeListener onChanged) {
        LinearLayout row = build(context, parent, label, summary, 2,
            FrostedWindow.surface(context, R.color.local_games_surface, FrostedWindow.ROW_RADIUS_DP));
        SwitchMaterial toggle = new SwitchMaterial(context);
        toggle.setChecked(checked);
        toggle.setEnabled(enabled);
        toggle.setOnCheckedChangeListener(onChanged);
        row.addView(toggle, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.5f);
        if (enabled) {
            row.setClickable(true);
            row.setOnClickListener(view -> toggle.toggle());
        }
        return row;
    }

    /** The row frame (padding, background, gap) with its title block, appended to {@code parent}. */
    private static LinearLayout build(Context context, LinearLayout parent, CharSequence label,
                                      @Nullable CharSequence summary, int summaryMaxLines,
                                      Drawable background) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, PADDING_START_DP), dp(context, PADDING_VERTICAL_DP),
            dp(context, PADDING_END_DP), dp(context, PADDING_VERTICAL_DP));
        row.setMinimumHeight(context.getResources().getDimensionPixelSize(R.dimen.local_games_menu_row_min_height));
        row.setBackground(background);
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
        return row;
    }

    private static void addTrailingIcon(Context context, LinearLayout row, int drawableRes) {
        ImageView icon = new ImageView(context);
        icon.setImageResource(drawableRes);
        icon.setImageTintList(ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.local_games_primary_text)));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(
            dp(context, CHEVRON_BOX_DP), dp(context, CHEVRON_BOX_DP)));
    }

    /** The selected-row frame: the pressed-accent tint with a 1.5 dp primary outline. */
    private static Drawable selectedSurface(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ContextCompat.getColor(context, R.color.local_games_surface_pressed));
        shape.setCornerRadius(dp(context, FrostedWindow.ROW_RADIUS_DP));
        shape.setStroke(Math.round(dp(context, 1) * 1.5f), ContextCompat.getColor(context, R.color.local_games_primary));
        return shape;
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
