package com.termux.ui.activity;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.res.Configuration;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.termux.ui.R;
import com.termux.ui.api.DesktopArchiveTasks;
import com.termux.ui.api.DesktopTasks;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.databinding.ActivityDesktopBinding;
import com.termux.ui.databinding.ItemDesktopAddBinding;
import com.termux.ui.databinding.ItemDesktopBinding;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopGlMode;
import com.termux.ui.domain.DesktopRunMode;
import com.termux.ui.domain.DesktopSessionState;
import com.termux.ui.runtime.DesktopCreationCatalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Inventory of the managed desktops, isolated per run mode: the Native tab lists the host
 * compositors (one instance per environment), the Container tab the proot instances (repeatable).
 * Creation happens in {@link DesktopCreateActivity}; this screen drives install, open, stop,
 * settings, logs and delete.
 */
public final class DesktopActivity extends AppCompatActivity {

    private static final long REFRESH_INTERVAL_MS = 2000;
    private static final int LOG_TAIL_BYTES = 24 * 1024;
    private static final int MENU_INSTALL = 1;
    private static final int MENU_STOP = 2;
    private static final int MENU_SETTINGS = 5;
    private static final int MENU_LOGS = 3;
    private static final int MENU_DELETE = 4;
    private static final int MENU_EXPORT = 6;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "DesktopIo"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = this::refresh;

    private ActivityDesktopBinding binding;
    private DesktopTasks tasks;
    private boolean destroyed;
    private DesktopRunMode tab = DesktopRunMode.NATIVE;
    private List<DesktopDistro> shownDistros = new ArrayList<>();
    private List<DesktopSessionState> shownStates = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDesktopBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        tasks = new DesktopTasks(this);
        binding.desktopToolbar.setNavigationOnClickListener(view -> finish());
        GamesHelpDialog.attach(binding.desktopToolbar,
            R.string.desktop_title, R.string.desktop_help);
        WindowInsetsPadding.apply(binding.getRoot(), true, false, true, true);
        binding.desktopTabNative.setOnClickListener(view -> switchTab(DesktopRunMode.NATIVE));
        binding.desktopTabContainer.setOnClickListener(view -> switchTab(DesktopRunMode.PROOT));
        binding.desktopEmptyCreate.setOnClickListener(view -> openCreate());
        binding.desktopImportContainer.setOnClickListener(view ->
            DesktopArchiveFlow.instance().startImport());
        binding.desktopArchiveDetails.setOnClickListener(view ->
            DesktopArchiveFlow.instance().showProgress());
        paintTabs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DesktopArchiveFlow.instance().attach(this, this::renderArchiveStatus, this::refresh);
        refresh();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTick);
        DesktopArchiveFlow.instance().detach(this);
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The column count follows the window width; re-flow the cards already loaded.
        if (binding != null) binding.desktopItems.post(() -> {
            if (!destroyed && binding != null) render(shownDistros, shownStates);
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(refreshTick);
        ioExecutor.shutdown();
        binding = null;
        super.onDestroy();
    }

    public static android.content.Intent createIntent(android.content.Context context) {
        return new android.content.Intent(context, DesktopActivity.class);
    }

    private void switchTab(DesktopRunMode mode) {
        if (tab == mode) return;
        tab = mode;
        paintTabs();
        render(shownDistros, shownStates);
    }

    private void paintTabs() {
        boolean isNative = tab == DesktopRunMode.NATIVE;
        binding.desktopTabNative.setBackground(getDrawable(isNative
            ? R.drawable.desktop_bg_tab_on : R.drawable.desktop_bg_tab_off));
        binding.desktopTabNative.setTextColor(getColor(isNative
            ? android.R.color.white : R.color.local_games_on_surface_secondary));
        binding.desktopTabContainer.setBackground(getDrawable(isNative
            ? R.drawable.desktop_bg_tab_off : R.drawable.desktop_bg_tab_on));
        binding.desktopTabContainer.setTextColor(getColor(isNative
            ? R.color.local_games_on_surface_secondary : android.R.color.white));
        binding.desktopImportContainer.setVisibility(isNative ? View.GONE : View.VISIBLE);
    }

    private void renderArchiveStatus(@Nullable DesktopArchiveTasks.Snapshot snapshot,
                                     boolean preparing) {
        if (destroyed || binding == null) return;
        boolean running = preparing || (snapshot != null
            && snapshot.status == DesktopArchiveTasks.Status.RUNNING);
        boolean visible = preparing || (snapshot != null
            && snapshot.status != DesktopArchiveTasks.Status.IDLE);
        binding.desktopImportContainer.setEnabled(!running && !DesktopArchiveTasks.isBusy());
        binding.desktopArchiveTask.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.desktopArchiveProgress.setVisibility(running ? View.VISIBLE : View.GONE);
        binding.desktopArchiveStatus.setText(preparing
            ? getString(R.string.desktop_archive_preparing)
            : DesktopArchiveFlow.statusText(this, snapshot));
    }

    /** Reloads the inventory and every state off the main thread, then re-renders. */
    private void refresh() {
        handler.removeCallbacks(refreshTick);
        if (destroyed) return;
        ioExecutor.execute(() -> {
            List<DesktopDistro> distros;
            List<DesktopSessionState> states = new ArrayList<>();
            try {
                distros = tasks.list();
            } catch (IOException error) {
                toastOnUi(getString(R.string.desktop_error, "desktop_list_failed"));
                scheduleRefresh();
                return;
            }
            for (DesktopDistro distro : distros) states.add(tasks.state(distro));
            runOnUiThread(() -> {
                if (destroyed || binding == null) return;
                render(distros, states);
                scheduleRefresh();
            });
        });
    }

    private void scheduleRefresh() {
        if (!destroyed) handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS);
    }

    private void render(List<DesktopDistro> distros, List<DesktopSessionState> states) {
        shownDistros = distros;
        shownStates = states;
        List<DesktopDistro> modeDistros = new ArrayList<>();
        List<DesktopSessionState> modeStates = new ArrayList<>();
        for (int index = 0; index < distros.size(); index++) {
            if (distros.get(index).getRunMode() != tab) continue;
            modeDistros.add(distros.get(index));
            modeStates.add(states.get(index));
        }
        GridLayout grid = binding.desktopItems;
        grid.removeAllViews();
        boolean empty = modeDistros.isEmpty();
        binding.desktopEmptyBlock.setVisibility(empty ? View.VISIBLE : View.GONE);
        grid.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            boolean isNative = tab == DesktopRunMode.NATIVE;
            binding.desktopEmptyTitle.setText(isNative
                ? R.string.desktop_empty_native_title : R.string.desktop_empty_container_title);
            binding.desktopEmptyText.setText(isNative
                ? R.string.desktop_empty_native_text : R.string.desktop_empty_container_text);
            return;
        }
        int gridWidth = gridWidthPx();
        int columns = LibraryGridColumns.columnsFor(
            Math.round(gridWidth / getResources().getDisplayMetrics().density), isLandscape());
        grid.setColumnCount(columns);
        for (int index = 0; index < modeDistros.size(); index++) {
            ItemDesktopBinding card = ItemDesktopBinding.inflate(getLayoutInflater(), grid, false);
            bindCard(card, modeDistros.get(index), modeStates.get(index), distros);
            place(card.getRoot(), index, columns, gridWidth, false);
            grid.addView(card.getRoot());
        }
        ItemDesktopAddBinding add = ItemDesktopAddBinding.inflate(getLayoutInflater(), grid, false);
        add.getRoot().setOnClickListener(view -> openCreate());
        place(add.getRoot(), LibraryGridColumns.importCardIndex(modeDistros.size()), columns,
            gridWidth, true);
        grid.addView(add.getRoot());
    }

    private void openCreate() {
        startActivity(DesktopCreateActivity.createIntent(this, tab));
    }

    /** Places a card in its grid cell with the Library's width, gutter and margins. */
    private void place(View card, int index, int columns, int gridWidth, boolean fillRow) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = LibraryGridColumns.cardWidthPx(gridWidth, columns, dp(4),
            getResources().getDisplayMetrics().density);
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(index % columns);
        int row = index / columns;
        params.rowSpec = fillRow ? GridLayout.spec(row, GridLayout.FILL) : GridLayout.spec(row);
        params.setMargins(dp(2), dp(2), dp(2), dp(6));
        card.setLayoutParams(params);
    }

    private int gridWidthPx() {
        int laidOut = binding.desktopItems.getWidth();
        if (laidOut > 0) return laidOut;
        int padding = 2 * getResources().getDimensionPixelSize(
            R.dimen.local_games_page_horizontal_padding);
        return Math.max(dp(140), getResources().getDisplayMetrics().widthPixels - padding);
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void bindCard(ItemDesktopBinding card, DesktopDistro distro,
                          DesktopSessionState state, List<DesktopDistro> all) {
        String label = DesktopCreateActivity.labelOf(this, distro.getDe());
        if (distro.getRunMode() == DesktopRunMode.PROOT) {
            label += " " + DesktopCreationCatalog.instanceLabel(distro, all);
        }
        card.desktopName.setText(label);
        card.desktopModeBadge.setText(distro.getRunMode() == DesktopRunMode.NATIVE
            ? getString(R.string.desktop_mode_native_badge)
            : getString(R.string.desktop_mode_container_badge, distro.getProotDistro()));
        card.desktopDetail.setText(getString(R.string.desktop_card_detail,
            glLabel(distro.getGlMode()), distro.getResolution(), distro.getRefreshRate()));
        card.desktopStatus.setText(stateLabel(state));
        card.desktopStatus.setTextColor(ContextCompat.getColor(this, stateColor(state)));

        boolean active = state == DesktopSessionState.STARTING
            || state == DesktopSessionState.RUNNING;
        boolean install = state == DesktopSessionState.NOT_INSTALLED;
        card.desktopPrimary.setText(install ? R.string.desktop_action_install
            : R.string.desktop_action_open);
        View.OnClickListener primary = view -> {
            if (install) confirmInstall(distro); else open(distro);
        };
        card.desktopPrimary.setOnClickListener(primary);
        card.getRoot().setOnClickListener(primary);
        card.desktopMore.setOnClickListener(view -> showMore(view, distro, state, active));
    }

    private void showMore(View anchor, DesktopDistro distro, DesktopSessionState state,
                          boolean active) {
        PopupMenu menu = new PopupMenu(this, anchor);
        Menu items = menu.getMenu();
        if (state != DesktopSessionState.NOT_INSTALLED && !active) {
            items.add(Menu.NONE, MENU_INSTALL, Menu.NONE, R.string.desktop_action_install);
        }
        if (active) items.add(Menu.NONE, MENU_STOP, Menu.NONE, R.string.desktop_action_stop);
        items.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.desktop_action_settings);
        items.add(Menu.NONE, MENU_LOGS, Menu.NONE, R.string.desktop_action_logs);
        if (distro.getRunMode() == DesktopRunMode.PROOT) {
            items.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.desktop_archive_export)
                .setEnabled(!active && !DesktopArchiveFlow.instance().isBusy());
        }
        if (!active) items.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.desktop_action_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_INSTALL: confirmInstall(distro); return true;
                case MENU_STOP: stop(distro); return true;
                case MENU_SETTINGS:
                    DesktopSettingsDialog.show(this, tasks, distro, this::refresh);
                    return true;
                case MENU_LOGS: showLogs(distro); return true;
                case MENU_DELETE: confirmDelete(distro); return true;
                case MENU_EXPORT:
                    DesktopArchiveFlow.instance().startExport(distro, active);
                    return true;
                default: return false;
            }
        });
        menu.show();
    }

    private String glLabel(DesktopGlMode mode) {
        switch (mode) {
            case SYSTEM: return getString(R.string.desktop_gl_system);
            case ZINK: return getString(R.string.desktop_gl_zink);
            case LLVMPIPE: return getString(R.string.desktop_gl_llvmpipe);
            default: return getString(R.string.desktop_gl_auto);
        }
    }

    private String stateLabel(DesktopSessionState state) {
        switch (state) {
            case INSTALLED: return getString(R.string.desktop_state_installed);
            case STARTING: return getString(R.string.desktop_state_starting);
            case RUNNING: return getString(R.string.desktop_state_running);
            case FAILED: return getString(R.string.desktop_state_failed);
            default: return getString(R.string.desktop_state_not_installed);
        }
    }

    private static int stateColor(DesktopSessionState state) {
        switch (state) {
            case RUNNING:
            case INSTALLED: return R.color.local_games_success_text;
            case FAILED: return R.color.local_games_error;
            default: return R.color.local_games_on_surface_secondary;
        }
    }

    private void confirmInstall(DesktopDistro distro) {
        new FrostedDialogBuilder(this)
            .setTitle(R.string.desktop_action_install)
            .setMessage(R.string.desktop_install_hint)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.desktop_action_install, (dialog, which) ->
                runHostAction(() -> {
                    String taskId = tasks.install(distro);
                    // The install runs in a host terminal session; surface it in the same
                    // console dialog the prefix setups use instead of running headless.
                    runOnUiThread(() -> {
                        if (!destroyed) PrefixSetupConsoleDialog.show(this, taskId);
                    });
                }))
            .show();
    }

    private void open(DesktopDistro distro) {
        startActivity(DesktopSessionActivity.createIntent(this, distro.getId()));
    }

    private void stop(DesktopDistro distro) {
        runHostAction(() -> {
            tasks.stop(distro);
            toastOnUi(getString(R.string.desktop_stop_requested));
        });
    }

    private void confirmDelete(DesktopDistro distro) {
        FrostedDialogBuilder builder = new FrostedDialogBuilder(this);
        builder.setTitle(getString(R.string.desktop_delete_title, distro.getName()));
        builder.setMessage(R.string.desktop_delete_message);
        builder.setNegativeButton(android.R.string.cancel, null);
        if (distro.getRunMode() == DesktopRunMode.PROOT) {
            CheckBox check = new CheckBox(this);
            check.setText(getString(R.string.desktop_delete_container_check,
                distro.getProotDistro()));
            check.setTextSize(12);
            check.setPadding(dp(20), dp(4), dp(20), dp(4));
            builder.setView(check);
            builder.setPositiveButton(R.string.desktop_action_delete, (dialog, which) ->
                ioExecutor.execute(() -> {
                    try {
                        if (check.isChecked()) tasks.deleteAndRemoveContainer(distro);
                        else tasks.delete(distro);
                    } catch (IOException error) {
                        toastOnUi("desktop_running".equals(error.getMessage())
                            ? getString(R.string.desktop_delete_running)
                            : getString(R.string.desktop_error, "desktop_delete_failed"));
                    } catch (LaunchHostException error) {
                        toastOnUi(getString(R.string.desktop_error, error.getErrorCode()));
                    }
                    runOnUiThread(this::refresh);
                }));
        } else {
            builder.setPositiveButton(R.string.desktop_action_delete, (dialog, which) ->
                ioExecutor.execute(() -> {
                    try {
                        tasks.delete(distro);
                    } catch (IOException error) {
                        toastOnUi("desktop_running".equals(error.getMessage())
                            ? getString(R.string.desktop_delete_running)
                            : getString(R.string.desktop_error, "desktop_delete_failed"));
                    }
                    runOnUiThread(this::refresh);
                }));
        }
        builder.show();
    }

    private void showLogs(DesktopDistro distro) {
        ioExecutor.execute(() -> {
            String text = tasks.logTail(distro, LOG_TAIL_BYTES);
            runOnUiThread(() -> {
                if (destroyed) return;
                TextView view = new TextView(this);
                view.setTypeface(Typeface.MONOSPACE);
                view.setTextSize(11);
                view.setTextIsSelectable(true);
                view.setPadding(dp(16), dp(8), dp(16), dp(8));
                view.setText(text.isEmpty() ? getString(R.string.desktop_logs_empty) : text);
                ScrollView scroll = new ScrollView(this);
                scroll.addView(view);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                new FrostedDialogBuilder(this)
                    .setTitle(R.string.desktop_logs_title)
                    .setView(scroll)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            });
        });
    }

    private interface HostAction {
        void run() throws LaunchHostException;
    }

    private void runHostAction(HostAction action) {
        ioExecutor.execute(() -> {
            try {
                action.run();
            } catch (LaunchHostException error) {
                toastOnUi(getString(R.string.desktop_error, error.getErrorCode()));
            }
        });
    }

    private void toastOnUi(String message) {
        runOnUiThread(() -> {
            if (!destroyed) android.widget.Toast.makeText(this, message,
                android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
