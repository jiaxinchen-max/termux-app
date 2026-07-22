package com.termux.app.activities.termuxbox;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.PopupMenu;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.termux.R;

public final class TermuxBoxHomeCardView {

    private static final int MENU_EDIT = 2;
    private static final int MENU_REMOVE = 3;

    public interface Actions {
        void onStartContainer(TermuxBoxContainerSpec spec);
        void onEditContainer(TermuxBoxContainerSpec spec);
        void onRemoveContainer(TermuxBoxContainerSpec spec);
    }

    private TermuxBoxHomeCardView() {
    }

    public static View create(Context context, TermuxBoxRepository repository, TermuxBoxContainerSpec spec, Actions actions, boolean compact) {
        MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setCardElevation(0f);
        card.setRadius(dp(context, 5));
        card.setStrokeColor(0xFFD3DEE8);
        card.setStrokeWidth(dp(context, 1));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = compact ? 10 : 18;
        body.setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding));
        card.addView(body, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(header);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.icon_menu_game_pad);
        int iconSize = compact ? 30 : 44;
        header.addView(icon, new LinearLayout.LayoutParams(dp(context, iconSize), dp(context, iconSize)));

        LinearLayout titleColumn = new LinearLayout(context);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleColumnParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        titleColumnParams.setMarginStart(dp(context, compact ? 8 : 14));
        header.addView(titleColumn, titleColumnParams);

        TextView title = text(context, spec.name, compact ? 13 : 24, 0xFF24323F, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleColumn.addView(title, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TextView subtitle = text(context, context.getString(R.string.termux_box_home_summary), compact ? 10 : 14, 0xFF60707E, false);
        subtitle.setVisibility(View.GONE);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        subtitleParams.topMargin = dp(context, compact ? 2 : 4);
        titleColumn.addView(subtitle, subtitleParams);

        TextView menuButton = text(context, "\u22EE", compact ? 18 : 24, 0xFF111111, true);
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setBackgroundColor(0x00000000);
        menuButton.setOnClickListener(v -> showMenu(context, v, spec, actions));

        // Start button — play icon, in header row for instant access
        ImageView startButton = new ImageView(context);
        startButton.setImageResource(R.drawable.ic_play);
        startButton.setBackgroundColor(0x00000000);
        startButton.setPadding(0, 0, 0, 0);
        startButton.setClickable(true);
        startButton.setFocusable(true);
        startButton.setOnClickListener(v -> actions.onStartContainer(spec));
        int btnSize = dp(context, compact ? 30 : 44);
        header.addView(startButton, new LinearLayout.LayoutParams(btnSize, btnSize));
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(btnSize, btnSize);
        header.addView(menuButton, menuParams);

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        body.addView(details, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TextView state = text(context, context.getString(R.string.termux_box_home_state_format,
            spec.name,
            spec.screenSize,
            "en_US"),
            compact ? 10 : 13, 0xFF24323F, false);
        state.setPadding(0, dp(context, compact ? 8 : 16), 0, 0);
        details.addView(state);

        TextView ready = text(context, context.getString(R.string.termux_box_home_ready), compact ? 10 : 13, 0xFF60707E, false);
        ready.setPadding(0, dp(context, compact ? 7 : 14), 0, 0);
        details.addView(ready);

        ChipGroup chips = new ChipGroup(context);
        chips.setSingleLine(false);
        chips.setChipSpacing(dp(context, compact ? 4 : 10));
        LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        chipsParams.topMargin = dp(context, compact ? 6 : 12);
        details.addView(chips, chipsParams);

        chips.addView(chip(context, spec.screenSize, compact));
        chips.addView(chip(context, spec.graphicsDriver, compact));
        chips.addView(chip(context, spec.audioDriver, compact));

        View.OnClickListener toggle = v -> {
            boolean expanded = details.getVisibility() != View.VISIBLE;
            details.setVisibility(expanded ? View.VISIBLE : View.GONE);
            subtitle.setVisibility(expanded ? View.VISIBLE : View.GONE);
        };
        header.setOnClickListener(toggle);
        card.setOnClickListener(toggle);

        return card;
    }

    private static void showMenu(Context context, View anchor, TermuxBoxContainerSpec spec, Actions actions) {
        PopupMenu popupMenu = new PopupMenu(context, anchor);
        popupMenu.getMenu().add(0, MENU_EDIT, 0, R.string.termux_box_home_edit_container);
        popupMenu.getMenu().add(0, MENU_REMOVE, 1, R.string.remove);
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_EDIT) {
                actions.onEditContainer(spec);
            } else {
                actions.onRemoveContainer(spec);
            }
            return true;
        });
        popupMenu.show();
    }

    private static Chip chip(Context context, String value, boolean compact) {
        Chip chip = new Chip(context);
        chip.setText(value);
        chip.setCheckable(false);
        chip.setClickable(false);
        chip.setTextSize(compact ? 9 : 14);
        chip.setTextColor(0xFF24323F);
        chip.setChipBackgroundColor(ColorStateList.valueOf(0xFFF0F4FA));
        chip.setChipStrokeColor(ColorStateList.valueOf(0xFFD3DEE8));
        chip.setChipStrokeWidth(dp(context, 1));
        chip.setChipCornerRadius(dp(context, 5));
        chip.setEnsureMinTouchTargetSize(false);
        return chip;
    }

    private static MaterialButton actionButton(Context context, int textResId, boolean compact, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(context);
        if (textResId != 0) {
            button.setText(textResId);
        }
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setCornerRadius(dp(context, 5));
        button.setMinHeight(dp(context, compact ? 32 : 44));
        button.setMinimumHeight(0);
        button.setTextSize(compact ? 9 : 14);
        button.setPadding(dp(context, 2), 0, dp(context, 2), 0);
        return button;
    }

    private static TextView text(Context context, String value, int sizeSp, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        if (bold) {
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return text;
    }

    private static String stripUtf8(String value) {
        if (TextUtils.isEmpty(value)) {
            return "-";
        }
        return value.endsWith(".utf8") ? value.substring(0, value.length() - 5) : value;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT;
}
