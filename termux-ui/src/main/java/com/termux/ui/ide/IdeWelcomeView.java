package com.termux.ui.ide;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.termux.ui.R;
import java.io.File;
import java.util.List;

/** AndroidIDE-inspired welcome layout; actions delegate to the existing native workspace. */
public final class IdeWelcomeView extends LinearLayout {
    public interface Actions {
        void onAction(int label);
        void onProject(File project);
    }
    private final Actions actions;
    private final LinearLayout recent;
    private final TextView empty;

    public IdeWelcomeView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        setOrientation(wide ? HORIZONTAL : VERTICAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int inset = wide ? 36 : 20;
        setPadding(dp(inset), dp(24), dp(inset), dp(24));
        setMinimumHeight(dp(Math.max(360, getResources().getConfiguration().screenHeightDp - 180)));
        setContentDescription(context.getString(R.string.ide_welcome));

        LinearLayout introduction = column();
        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.local_games_ic_ide);
        logo.setImageTintList(ColorStateList.valueOf(colour(R.color.local_games_primary)));
        introduction.addView(logo, new LayoutParams(dp(56), dp(56)));
        TextView name = label(R.string.ide_brand, 30, false);
        name.setTypeface(null, Typeface.BOLD); introduction.addView(name);
        introduction.addView(label(R.string.ide_welcome_subtitle, 16, true));

        LinearLayout operations = column();
        operations.addView(label(R.string.ide_get_started, 18, false));
        addAction(operations, R.string.ide_new_project, R.drawable.local_games_ic_add, true);
        addAction(operations, R.string.ide_open_project, R.drawable.local_games_ic_folder, false);
        addAction(operations, R.string.ide_import_project, R.drawable.local_games_ic_download, false);
        addAction(operations, R.string.ide_tools, R.drawable.local_games_ic_settings, false);
        addAction(operations, R.string.ide_open_terminal, R.drawable.local_games_ic_terminal, false);
        addAction(operations, R.string.ide_task, R.drawable.local_games_ic_file, false);

        LinearLayout projectSection = column();
        projectSection.setPadding(0, dp(24), 0, 0);
        TextView recentTitle = label(R.string.ide_recent_projects, 16, false);
        recentTitle.setTypeface(null, Typeface.BOLD); projectSection.addView(recentTitle);
        recent = column(); projectSection.addView(recent);
        empty = label(R.string.ide_loading_projects, 14, true); projectSection.addView(empty);

        if (wide) {
            introduction.addView(projectSection);
            LayoutParams left = new LayoutParams(0, -2, 1.2f); left.setMarginEnd(dp(48));
            addView(introduction, left); addView(operations, new LayoutParams(0, -2, 1));
        } else {
            addView(introduction, new LayoutParams(-1, -2));
            LayoutParams actionParams = new LayoutParams(-1, -2); actionParams.topMargin = dp(24);
            addView(operations, actionParams); addView(projectSection, new LayoutParams(-1, -2));
        }
    }
    public void setProjects(List<File> projects) {
        recent.removeAllViews();
        empty.setText(R.string.ide_no_projects);
        empty.setVisibility(projects.isEmpty() ? VISIBLE : GONE);
        for (int i = 0; i < Math.min(5, projects.size()); i++) {
            File project = projects.get(i);
            MaterialButton row = button(project.getName(), R.drawable.local_games_ic_folder, false);
            row.setContentDescription(project.getName());
            row.setOnClickListener(v -> actions.onProject(project));
            recent.addView(row, spacedParams());
        }
        if (projects.size() > 5) addAction(recent, R.string.ide_all_projects, R.drawable.local_games_ic_more_actions, false);
    }
    private void addAction(LinearLayout parent, int label, int icon, boolean primary) {
        MaterialButton button = button(getContext().getString(label), icon, primary);
        button.setOnClickListener(v -> actions.onAction(label)); parent.addView(button, spacedParams());
    }
    private MaterialButton button(String text, int icon, boolean primary) {
        MaterialButton button = new MaterialButton(getContext(), null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text); button.setTextSize(14); button.setAllCaps(false);
        button.setSingleLine(); button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setCornerRadius(dp(16)); button.setMinHeight(dp(52));
        button.setPaddingRelative(dp(18), 0, dp(18), 0);
        button.setInsetTop(0); button.setInsetBottom(0);
        button.setIconResource(icon); button.setIconSize(dp(22)); button.setIconPadding(dp(14));
        int foreground = colour(primary ? R.color.local_games_on_primary : R.color.local_games_on_surface);
        button.setTextColor(foreground); button.setIconTint(ColorStateList.valueOf(foreground));
        button.setBackgroundTintList(ColorStateList.valueOf(colour(primary ? R.color.local_games_primary : R.color.local_games_surface)));
        button.setStrokeColor(ColorStateList.valueOf(colour(R.color.local_games_outline)));
        button.setStrokeWidth(primary ? 0 : dp(1));
        return button;
    }
    private LayoutParams spacedParams() { LayoutParams p = new LayoutParams(-1, dp(54)); p.topMargin = dp(10); return p; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(getContext()); view.setOrientation(VERTICAL); return view; }
    private TextView label(int value, int size, boolean secondary) {
        TextView text = new TextView(getContext()); text.setText(value); text.setTextSize(size);
        text.setTextColor(colour(secondary ? R.color.local_games_on_surface_secondary : R.color.local_games_on_surface));
        text.setPadding(0, dp(8), 0, dp(4)); return text;
    }
    private int colour(int resource) { return ContextCompat.getColor(getContext(), resource); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
