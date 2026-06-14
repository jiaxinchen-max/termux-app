package com.termux.app.activities.termuxbox;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.termux.R;

public class TermuxBoxHomeFragment extends Fragment {

    private TermuxBoxRepository repository;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        repository = navigator().getRepository();

        NestedScrollView root = new NestedScrollView(requireContext());
        root.setFillViewport(true);
        root.setBackgroundColor(0xFFF4F6FA);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.addView(content, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(buildHomeCard());
        return root;
    }

    private View buildHomeCard() {
        MaterialCardView card = new MaterialCardView(requireContext());
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setCardElevation(0f);
        card.setRadius(dp(5));
        card.setStrokeColor(0xFFD3DEE8);
        card.setStrokeWidth(dp(1));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(params);

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.addView(body, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(header);

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(R.drawable.icon_menu_game_pad);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        header.addView(icon, iconParams);

        LinearLayout titleColumn = new LinearLayout(requireContext());
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(14));
        header.addView(titleColumn, titleParams);

        TextView title = new TextView(requireContext());
        title.setText("编辑容器");
        title.setTextColor(0xFF24323F);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleColumn.addView(title);

        TextView subtitle = new TextView(requireContext());
        subtitle.setText("Winlator 风格的统一参数入口。");
        subtitle.setTextColor(0xFF60707E);
        subtitle.setTextSize(13f);
        subtitle.setPadding(0, dp(4), 0, 0);
        titleColumn.addView(subtitle);

        TextView state = new TextView(requireContext());
        state.setText("当前容器：" + valueOrDash(repository.getCurrentWineContainerName())
            + " · 分辨率：" + repository.getFallbackResolution()
            + " · 语言：" + stripUtf8(repository.getLocale()));
        state.setTextColor(0xFF24323F);
        state.setTextSize(13f);
        state.setPadding(0, dp(16), 0, 0);
        body.addView(state);

        View divider = new View(requireContext());
        divider.setBackgroundColor(0xFFD7D7D7);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        dividerParams.topMargin = dp(14);
        body.addView(divider, dividerParams);

        TextView ready = new TextView(requireContext());
        ready.setText("Ready");
        ready.setTextColor(0xFF60707E);
        ready.setTextSize(13f);
        ready.setPadding(0, dp(14), 0, 0);
        body.addView(ready);

        ChipGroup chips = new ChipGroup(requireContext());
        chips.setSingleLine(true);
        chips.setChipSpacing(dp(10));
        LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsParams.topMargin = dp(12);
        body.addView(chips, chipsParams);

        chips.addView(chip(repository.getFallbackResolution()));
        chips.addView(chip("Turnip (Adreno)"));
        chips.addView(chip("ALSA"));

        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(18);
        body.addView(buttons, buttonsParams);

        MaterialButton startWineButton = actionButton("启动 Wine", v -> navigator().startWine());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(startWineButton, startParams);

        Space spacer = new Space(requireContext());
        buttons.addView(spacer, new LinearLayout.LayoutParams(dp(12), 1));

        MaterialButton openSettingsButton = actionButton("编辑容器", v -> navigator().openSection(TermuxBoxSection.CONTAINERS));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(openSettingsButton, applyParams);

        return card;
    }

    private Chip chip(String text) {
        Chip chip = new Chip(requireContext());
        chip.setText(text);
        chip.setCheckable(false);
        chip.setClickable(false);
        chip.setTextColor(0xFF24323F);
        chip.setChipBackgroundColor(ColorStateList.valueOf(0xFFF0F4FA));
        chip.setChipStrokeColor(ColorStateList.valueOf(0xFFD3DEE8));
        chip.setChipStrokeWidth(dp(1));
        chip.setChipCornerRadius(dp(5));
        return chip;
    }

    private MaterialButton actionButton(String text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setCornerRadius(dp(5));
        button.setMinHeight(dp(44));
        return button;
    }

    private String stripUtf8(String value) {
        if (TextUtils.isEmpty(value)) {
            return "-";
        }
        return value.endsWith(".utf8") ? value.substring(0, value.length() - 5) : value;
    }

    private String valueOrDash(@Nullable String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }

    private int dp(int value) {
        return Math.round(value * requireContext().getResources().getDisplayMetrics().density);
    }

    private TermuxBoxNavigator navigator() {
        if (getActivity() instanceof TermuxBoxNavigator) {
            return (TermuxBoxNavigator) getActivity();
        }
        throw new IllegalStateException("TermuxBoxNavigator not attached");
    }
}
