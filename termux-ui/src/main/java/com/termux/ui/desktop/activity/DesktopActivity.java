package com.termux.ui.desktop.activity;

import android.content.res.Configuration;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.termux.ui.R;
import com.termux.ui.activity.AppUiActivity;
import com.termux.ui.activity.FrostedDialogBuilder;
import com.termux.ui.activity.LibraryGridColumns;
import com.termux.ui.activity.PrefixSetupConsoleDialog;
import com.termux.ui.activity.UiNavigation;
import com.termux.ui.activity.CollectionToolbar;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.databinding.ActivityDesktopBinding;
import com.termux.ui.databinding.ItemDesktopAddBinding;
import com.termux.ui.databinding.ItemDesktopBinding;
import com.termux.ui.databinding.ItemDesktopImportBinding;
import com.termux.ui.desktop.api.DesktopArchiveTasks;
import com.termux.ui.desktop.api.DesktopTasks;
import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.domain.DesktopSessionState;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;
import com.termux.ui.desktop.runtime.DesktopCreationCatalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Inventory of the managed desktops, isolated per run mode: the Native tab lists the host
 * compositors (one instance per environment), the Proot selection the container instances (repeatable).
 * Creation happens in {@link DesktopCreateActivity}; this screen drives install, open, stop,
 * settings, logs and delete.
 */
public final class DesktopActivity extends AppUiActivity {

    private final Set<String> handledCreations = new HashSet<>();
    private final ActivityResultLauncher<Intent> createDesktop = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                installCreatedDesktop(result.getData().getStringExtra(DesktopCreateActivity.EXTRA_CREATED_ID));
            }
        });

    private static final long REFRESH_INTERVAL_MS = 2000;
    private static final int LOG_TAIL_BYTES = 24 * 1024;
    private static final int MENU_INSTALL = 1;
    private static final int MENU_STOP = 2;
    private static final int MENU_SETTINGS = 5;
    private static final int MENU_LOGS = 3;
    private static final int MENU_DELETE = 4;
    private static final int MENU_EXPORT = 6;
    private static final int MENU_USB_STORAGE = 7;
    private static final int MENU_ARCHIVE_PROGRESS = 8;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "DesktopIo"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = this::refresh;

    private ActivityDesktopBinding binding;
    private UiNavigation navigation;
    private CollectionToolbar collectionToolbar;
    private DesktopTasks tasks;
    private boolean destroyed;
    private volatile boolean resumed;
    @Nullable private List<Object> renderedKey;
    private DesktopRunMode tab = DesktopRunMode.NATIVE;
    private List<DesktopDistro> shownDistros = new ArrayList<>();
    private List<DesktopSessionState> shownStates = new ArrayList<>();
    private boolean archivePreparingOrRunning;
    @Nullable private ItemDesktopImportBinding importCard;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDesktopBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        tasks = new DesktopTasks(this);
        if (savedInstanceState != null) {
            ArrayList<String> handled = savedInstanceState.getStringArrayList("desktop-handled-creations");
            if (handled != null) handledCreations.addAll(handled);
        }
        collectionToolbar = new CollectionToolbar(this, binding.desktopToolbar, "desktop",
            R.string.desktop_title, R.string.desktop_help, this::refresh,
            () -> render(shownDistros, shownStates));
        navigation = UiNavigation.install(this, R.id.local_games_navigation_desktop, null);
        collectionToolbar.enableRuntimeSelector(() -> switchTab(collectionToolbar.isProot()
            ? DesktopRunMode.PROOT : DesktopRunMode.NATIVE));
        tab = collectionToolbar.isProot() ? DesktopRunMode.PROOT : DesktopRunMode.NATIVE;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        DesktopArchiveFlow.instance().attach(this, this::renderArchiveStatus, this::refresh);
        refresh();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refreshTick);
        DesktopArchiveFlow.instance().detach(this);
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        navigation.updateConfiguration();
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
        render(shownDistros, shownStates);
    }

    private void renderArchiveStatus(@Nullable DesktopArchiveTasks.Snapshot snapshot,
                                     boolean preparing) {
        if (destroyed || binding == null) return;
        boolean running = preparing || (snapshot != null
            && snapshot.status == DesktopArchiveTasks.Status.RUNNING);
        archivePreparingOrRunning = running;
        updateImportAvailability();
    }

    private boolean anyDesktopActive() {
        for (DesktopSessionState state : shownStates) if (state.isActive()) return true;
        return false;
    }

    private void updateImportAvailability() {
        if (importCard == null) return;
        DesktopArchiveFlow flow = DesktopArchiveFlow.instance();
        boolean importInProgress = archivePreparingOrRunning && flow.hasProgressFor("");
        boolean enabled = importInProgress || (!archivePreparingOrRunning
            && !DesktopArchiveTasks.isBusy() && !anyDesktopActive());
        importCard.getRoot().setEnabled(enabled);
        importCard.getRoot().setAlpha(enabled ? 1f : .5f);
        importCard.desktopImportMore.setVisibility(flow.hasProgressFor("") ? View.VISIBLE : View.GONE);
    }

    /** Reloads the inventory and every state off the main thread, then re-renders. */
    private void refresh() {
        handler.removeCallbacks(refreshTick);
        if (destroyed || !resumed) return;
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
                if (destroyed || !resumed || binding == null) return;
                render(distros, states);
                scheduleRefresh();
            });
        });
    }

    private void scheduleRefresh() {
        if (!destroyed && resumed) handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS);
    }

    private void render(List<DesktopDistro> distros, List<DesktopSessionState> states) {
        shownDistros = distros;
        shownStates = states;
        updateImportAvailability();
        int gridWidth = gridWidthPx();
        List<Object> key = new ArrayList<>();
        key.add(tab);
        key.add(collectionToolbar.isList());
        key.add(gridWidth);
        key.add(getResources().getDisplayMetrics().densityDpi);
        key.add(isLandscape());
        for (int index = 0; index < distros.size(); index++) {
            DesktopDistro distro = distros.get(index);
            if (distro.getRunMode() != tab) continue;
            key.add(Arrays.asList(distro.getId(), distro.getName(), distro.getRunMode(),
                distro.getProotDistro(), distro.getDe(), distro.getHostGlMode(), distro.getGuestGlMode(),
                distro.getResolution(), distro.getRefreshRate(), distro.getUsbStorage(),
                states.get(index)));
        }
        // Keep the touched/focused views attached during unchanged inventory polls.
        if (key.equals(renderedKey)) return;
        renderedKey = key;
        List<DesktopDistro> modeDistros = new ArrayList<>();
        List<DesktopSessionState> modeStates = new ArrayList<>();
        for (int index = 0; index < distros.size(); index++) {
            if (distros.get(index).getRunMode() != tab) continue;
            modeDistros.add(distros.get(index));
            modeStates.add(states.get(index));
        }
        GridLayout grid = binding.desktopItems;
        importCard = null;
        grid.removeAllViews();
        binding.desktopCount.setText(collectionToolbar.getRuntimeCountText(modeDistros.size()));
        grid.setVisibility(View.VISIBLE);
        int columns = collectionToolbar.isList() ? 1 : LibraryGridColumns.columnsFor(
            Math.round(gridWidth / getResources().getDisplayMetrics().density), isLandscape());
        grid.setColumnCount(columns);
        for (int index = 0; index < modeDistros.size(); index++) {
            ItemDesktopBinding card = ItemDesktopBinding.bind(getLayoutInflater().inflate(
                collectionToolbar.isList() ? R.layout.item_desktop_list : R.layout.item_desktop,
                grid, false));
            bindCard(card, modeDistros.get(index), modeStates.get(index), distros);
            place(card.getRoot(), index, columns, gridWidth, false);
            grid.addView(card.getRoot());
        }
        ItemDesktopAddBinding add = ItemDesktopAddBinding.bind(getLayoutInflater().inflate(
            collectionToolbar.isList() ? R.layout.item_desktop_add_list : R.layout.item_desktop_add,
            grid, false));
        add.getRoot().setOnClickListener(view -> openCreate());
        place(add.getRoot(), modeDistros.size(), columns, gridWidth, true);
        grid.addView(add.getRoot());
        if (tab == DesktopRunMode.PROOT) {
            importCard = ItemDesktopImportBinding.bind(getLayoutInflater().inflate(
                collectionToolbar.isList() ? R.layout.item_desktop_import_list : R.layout.item_desktop_import,
                grid, false));
            importCard.getRoot().setOnClickListener(view -> {
                DesktopArchiveFlow flow = DesktopArchiveFlow.instance();
                if (archivePreparingOrRunning && flow.hasProgressFor("")) flow.showProgressFor("");
                else flow.startImport();
            });
            importCard.desktopImportMore.setOnClickListener(view -> {
                PopupMenu menu = new PopupMenu(this, view);
                menu.getMenu().add(R.string.desktop_archive_view_progress);
                menu.setOnMenuItemClickListener(item -> {
                    DesktopArchiveFlow.instance().showProgressFor("");
                    return true;
                });
                menu.show();
            });
            place(importCard.getRoot(), modeDistros.size() + 1, columns, gridWidth, true);
            grid.addView(importCard.getRoot());
            updateImportAvailability();
        }
    }

    private void openCreate() {
        createDesktop.launch(DesktopCreateActivity.createIntent(this, tab));
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
            getString(R.string.desktop_host_summary, DesktopCreateActivity.hostLabel(this, distro.getHostGlMode()))
                + (distro.getRunMode() == DesktopRunMode.PROOT ? " · " + getString(R.string.desktop_guest_summary,
                    DesktopCreateActivity.guestLabel(this, distro.getGuestGlMode())) : ""), distro.getResolution(), distro.getRefreshRate()));
        card.desktopStatus.setText(stateLabel(state));
        card.desktopStatus.setTextColor(ContextCompat.getColor(this, stateColor(state)));

        boolean active = state.isActive();
        boolean stopping = state == DesktopSessionState.STOPPING;
        boolean install = state == DesktopSessionState.NOT_INSTALLED;
        card.desktopPrimary.setText(install ? R.string.desktop_action_install
            : R.string.desktop_action_open);
        View.OnClickListener primary = view -> {
            if (install) confirmInstall(distro); else open(distro);
        };
        card.desktopPrimary.setOnClickListener(primary);
        card.desktopPrimary.setEnabled(!stopping);
        card.getRoot().setOnClickListener(stopping ? null : primary);
        card.desktopMore.setOnClickListener(view -> showMore(view, distro, state, active));
    }

    private void showMore(View anchor, DesktopDistro distro, DesktopSessionState state,
                          boolean active) {
        PopupMenu menu = new PopupMenu(this, anchor);
        Menu items = menu.getMenu();
        if (state != DesktopSessionState.NOT_INSTALLED && !active) {
            items.add(Menu.NONE, MENU_INSTALL, Menu.NONE, R.string.desktop_action_install);
        }
        if (active && state != DesktopSessionState.STOPPING)
            items.add(Menu.NONE, MENU_STOP, Menu.NONE, R.string.desktop_action_stop);
        items.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.desktop_action_settings);
        if (DesktopArchiveFlow.instance().hasProgressFor(distro.getId())) {
            items.add(Menu.NONE, MENU_ARCHIVE_PROGRESS, Menu.NONE, R.string.desktop_archive_view_progress);
        }
        items.add(Menu.NONE, MENU_USB_STORAGE, Menu.NONE, R.string.desktop_usb_title)
            .setEnabled(state != DesktopSessionState.STOPPING);
        if (distro.getRunMode() == DesktopRunMode.PROOT) {
            if (distro.getDe().isGnomeSession()) {
                items.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.desktop_usb_gnome_archive_unsupported).setEnabled(false);
            } else {
                items.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.desktop_archive_export)
                    .setEnabled(!anyDesktopActive() && !DesktopArchiveFlow.instance().isBusy());
            }
        }
        items.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.desktop_action_delete);
        items.add(Menu.NONE, MENU_LOGS, Menu.NONE, R.string.desktop_action_logs);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_INSTALL: confirmInstall(distro); return true;
                case MENU_STOP: stop(distro); return true;
                case MENU_SETTINGS:
                    runHostAction(() -> {
                        try { tasks.forceStop(distro); }
                        catch (IOException error) { toastOnUi(getString(R.string.desktop_error, error.getMessage())); return; }
                        runOnUiThread(() -> DesktopSettingsDialog.show(this, tasks, distro, this::refresh));
                    });
                    return true;
                case MENU_LOGS: showLogs(distro); return true;
                case MENU_ARCHIVE_PROGRESS:
                    DesktopArchiveFlow.instance().showProgressFor(distro.getId());
                    return true;
                case MENU_DELETE: confirmDelete(distro); return true;
                case MENU_USB_STORAGE:
                    DesktopUsbStorageDialog.show(this, tasks, distro, this::refresh);
                    return true;
                case MENU_EXPORT:
                    if (distro.getUsbStorage() != DesktopUsbStorageMode.OFF) {
                        new FrostedDialogBuilder(this).setTitle(R.string.desktop_archive_export)
                            .setMessage(R.string.desktop_usb_archive_disable)
                            .setPositiveButton(android.R.string.ok, null).show();
                        return true;
                    }
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
            case STOPPING: return getString(R.string.desktop_state_stopping);
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

    private void installCreatedDesktop(@Nullable String id) {
        if (!DesktopDistro.isValidId(id) || !handledCreations.add(id)) return;
        runHostAction(() -> {
            try {
                java.util.Optional<DesktopDistro> saved = tasks.find(id);
                if (!saved.isPresent()) throw new IOException("desktop_not_found");
                installAndShowConsole(saved.get());
            } catch (IOException error) {
                throw new LaunchHostException("desktop_load_failed", true, error);
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putStringArrayList("desktop-handled-creations", new ArrayList<>(handledCreations));
        super.onSaveInstanceState(outState);
    }

    private void confirmInstall(DesktopDistro distro) {
        new FrostedDialogBuilder(this)
            .setTitle(R.string.desktop_action_install)
            .setMessage(R.string.desktop_install_hint)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.desktop_action_install, (dialog, which) ->
                runHostAction(() -> installAndShowConsole(distro)))
            .show();
    }

    /** Both creation and manual retry attach to the same real installation terminal. */
    private void installAndShowConsole(DesktopDistro distro) throws LaunchHostException {
        String taskId = tasks.install(distro);
        runOnUiThread(() -> {
            if (!destroyed) PrefixSetupConsoleDialog.show(this, taskId);
        });
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
                        toastOnUi(getString(R.string.desktop_error, "desktop_delete_failed"));
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
                        toastOnUi(getString(R.string.desktop_error, "desktop_delete_failed"));
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
