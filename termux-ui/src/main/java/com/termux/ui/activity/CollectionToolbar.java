package com.termux.ui.activity;

import android.app.Activity;
import android.view.MenuItem;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.content.ContextCompat;

import com.termux.ui.R;

/** Identical root collection actions, with a separate saved layout choice for each page. */
public final class CollectionToolbar {
    private final Activity activity;
    private final Toolbar toolbar;
    private final String page;
    private FileManagerViewMode mode;
    private boolean proot;
    private Runnable runtimeChanged;
    private View runtimeAction;
    private ImageView runtimeIcon;
    private TextView runtimeLabel;

    public CollectionToolbar(Activity activity, Toolbar toolbar, String page,
            int helpTitle, int helpText, Runnable refresh, Runnable layoutChanged) {
        this.activity = activity;
        this.toolbar = toolbar;
        this.page = page;
        mode = FileManagerViewMode.load(activity, page);
        toolbar.setTitle(null);
        toolbar.setNavigationIcon(null);
        toolbar.getMenu().clear();
        toolbar.inflateMenu(R.menu.local_games_collection_runtime);
        toolbar.inflateMenu(R.menu.local_games_file_manager);
        toolbar.inflateMenu(R.menu.local_games_help);
        toolbar.getMenu().findItem(R.id.local_games_action_refresh_directory)
            .setTitle(R.string.local_games_collection_refresh);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.local_games_action_runtime_mode) {
                toggleRuntime();
            } else if (item.getItemId() == R.id.local_games_action_refresh_directory) {
                refresh.run();
            } else if (item.getItemId() == R.id.local_games_action_view_mode) {
                mode = mode.toggled();
                mode.save(activity, page);
                updateIcon();
                layoutChanged.run();
            } else if (item.getItemId() == R.id.local_games_action_help) {
                GamesHelpDialog.show(activity, helpTitle, helpText);
            } else return false;
            return true;
        });
        updateIcon();
    }

    public FileManagerViewMode getMode() { return mode; }
    public boolean isList() { return mode == FileManagerViewMode.LIST; }

    /** Shared mode badge and explicit label, with a fixed touch target in both modes. */
    public void enableRuntimeSelector(Runnable changed) {
        proot = isProotSelected(activity, page);
        runtimeChanged = changed;
        MenuItem item = toolbar.getMenu().findItem(R.id.local_games_action_runtime_mode);
        runtimeAction = LayoutInflater.from(toolbar.getContext()).inflate(
            R.layout.view_collection_runtime_mode, toolbar, false);
        runtimeIcon = runtimeAction.findViewById(R.id.collection_runtime_icon);
        runtimeLabel = runtimeAction.findViewById(R.id.collection_runtime_label);
        runtimeAction.setOnClickListener(view -> toggleRuntime());
        item.setActionView(runtimeAction).setVisible(true);
        updateRuntimeIcon();
    }

    private void toggleRuntime() {
        proot = !proot;
        activity.getSharedPreferences("local_games_settings", Context.MODE_PRIVATE)
            .edit().putBoolean(page + "_proot", proot).apply();
        updateRuntimeIcon();
        runtimeChanged.run();
    }

    public String getRuntimeCountText(int count) {
        return activity.getString(R.string.collection_runtime_count,
            activity.getString(proot ? R.string.collection_mode_proot : R.string.collection_mode_native), count);
    }

    private void updateRuntimeIcon() {
        MenuItem item = toolbar.getMenu().findItem(R.id.local_games_action_runtime_mode);
        if (!item.isVisible()) return;
        String current = activity.getString(proot ? R.string.collection_mode_proot : R.string.collection_mode_native);
        String next = activity.getString(proot ? R.string.collection_mode_native : R.string.collection_mode_proot);
        String description = activity.getString(R.string.collection_runtime_switch, current, next);
        runtimeIcon.setImageDrawable(new RuntimeModeIcon(activity, proot));
        runtimeLabel.setText(current);
        runtimeAction.setContentDescription(description);
        TooltipCompat.setTooltipText(runtimeAction, description);
        item.setTitle(description);
        androidx.core.view.MenuItemCompat.setContentDescription(item, description);
        androidx.core.view.MenuItemCompat.setTooltipText(item, description);
    }

    public boolean isProot() { return proot; }

    public static boolean isProotSelected(Context context, String page) {
        return context.getSharedPreferences("local_games_settings", Context.MODE_PRIVATE)
            .getBoolean(page + "_proot", false);
    }

    private void updateIcon() {
        MenuItem item = toolbar.getMenu().findItem(R.id.local_games_action_view_mode);
        item.setIcon(isList() ? R.drawable.local_games_ic_view_grid : R.drawable.local_games_ic_view_list);
        item.setTitle(isList() ? R.string.local_game_file_manager_view_grid : R.string.local_game_file_manager_view_list);
        int color = ContextCompat.getColor(activity, R.color.local_games_on_surface);
        for (int index = 0; index < toolbar.getMenu().size(); index++) {
            MenuItem action = toolbar.getMenu().getItem(index);
            if (action.getItemId() != R.id.local_games_action_runtime_mode && action.getIcon() != null) action.getIcon().mutate().setTint(color);
        }
        updateRuntimeIcon();
    }
}
