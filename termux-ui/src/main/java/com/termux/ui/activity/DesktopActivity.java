package com.termux.ui.activity;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.termux.ui.R;
import com.termux.ui.api.DesktopTasks;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.databinding.ActivityDesktopBinding;
import com.termux.ui.databinding.ItemDesktopAddBinding;
import com.termux.ui.databinding.ItemDesktopBinding;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopGlMode;
import com.termux.ui.domain.DesktopRunMode;
import com.termux.ui.domain.DesktopSessionState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the managed KWin desktop distributions (native or proot) as cards, like the Library's
 * game grid, and drives their install, open, stop, log and delete actions. The desktop itself is
 * shown by {@link DesktopSessionActivity}.
 */
public final class DesktopActivity extends AppCompatActivity {

    private static final long REFRESH_INTERVAL_MS = 2000;
    private static final int LOG_TAIL_BYTES = 24 * 1024;
    private static final int MENU_INSTALL = 1;
    private static final int MENU_STOP = 2;
    private static final int MENU_LOGS = 3;
    private static final int MENU_DELETE = 4;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "DesktopIo"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = this::refresh;

    private ActivityDesktopBinding binding;
    private DesktopTasks tasks;
    private boolean destroyed;
    private List<DesktopDistro> shownDistros = Collections.emptyList();
    private List<DesktopSessionState> shownStates = Collections.emptyList();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDesktopBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        tasks = new DesktopTasks(this);
        binding.desktopToolbar.setNavigationOnClickListener(view -> finish());
        GamesHelpDialog.attach(binding.desktopToolbar, R.string.desktop_title,
            R.string.desktop_help);
        WindowInsetsPadding.apply(binding.getRoot(), true, false, true, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTick);
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
        GridLayout grid = binding.desktopItems;
        grid.removeAllViews();
        binding.desktopEmpty.setVisibility(distros.isEmpty() ? View.VISIBLE : View.GONE);
        int gridWidth = gridWidthPx();
        int columns = LibraryGridColumns.columnsFor(
            Math.round(gridWidth / getResources().getDisplayMetrics().density), isLandscape());
        grid.setColumnCount(columns);
        for (int index = 0; index < distros.size(); index++) {
            ItemDesktopBinding card = ItemDesktopBinding.inflate(getLayoutInflater(), grid, false);
            bindCard(card, distros.get(index), states.get(index));
            place(card.getRoot(), index, columns, gridWidth, false);
            grid.addView(card.getRoot());
        }
        ItemDesktopAddBinding add = ItemDesktopAddBinding.inflate(getLayoutInflater(), grid, false);
        add.getRoot().setOnClickListener(view -> showCreateDialog());
        place(add.getRoot(), LibraryGridColumns.importCardIndex(distros.size()), columns,
            gridWidth, true);
        grid.addView(add.getRoot());
    }

    /** Places a card in its grid cell with the Library's width, gutter and margins. */
    private void place(View card, int index, int columns, int gridWidth, boolean fillRow) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = LibraryGridColumns.cardWidthPx(gridWidth, columns, dp(4),
            getResources().getDisplayMetrics().density);
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(LibraryGridColumns.columnOf(index, columns));
        int row = LibraryGridColumns.rowOf(index, columns);
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

    private void bindCard(ItemDesktopBinding card, DesktopDistro distro, DesktopSessionState state) {
        card.desktopName.setText(distro.getName());
        card.desktopModeBadge.setText(distro.getRunMode() == DesktopRunMode.NATIVE
            ? R.string.desktop_mode_native_badge : R.string.desktop_mode_proot_badge);
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
        items.add(Menu.NONE, MENU_LOGS, Menu.NONE, R.string.desktop_action_logs);
        if (!active) items.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.desktop_action_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_INSTALL: confirmInstall(distro); return true;
                case MENU_STOP: stop(distro); return true;
                case MENU_LOGS: showLogs(distro); return true;
                case MENU_DELETE: confirmDelete(distro); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private String glLabel(DesktopGlMode mode) {
        switch (mode) {
            case ZINK: return getString(R.string.desktop_gl_zink);
            case LLVMPIPE: return getString(R.string.desktop_gl_llvmpipe);
            default: return getString(R.string.desktop_gl_system);
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
                    tasks.install(distro);
                    toastOnUi(getString(R.string.desktop_install_started));
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
        new FrostedDialogBuilder(this)
            .setTitle(R.string.desktop_delete_title)
            .setMessage(R.string.desktop_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.desktop_action_delete, (dialog, which) ->
                ioExecutor.execute(() -> {
                    try {
                        tasks.delete(distro);
                    } catch (IOException error) {
                        toastOnUi("desktop_running".equals(error.getMessage())
                            ? getString(R.string.desktop_delete_running)
                            : getString(R.string.desktop_error, "desktop_delete_failed"));
                    }
                    runOnUiThread(this::refresh);
                }))
            .show();
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

    private void showCreateDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        EditText name = field(R.string.desktop_create_name_hint, InputType.TYPE_CLASS_TEXT);
        form.addView(name);

        form.addView(label(R.string.desktop_create_mode_label));
        EditText distro = field(R.string.desktop_create_distro_hint, InputType.TYPE_CLASS_TEXT);
        distro.setText(DesktopDistro.DEFAULT_PROOT_DISTRO);
        distro.setVisibility(View.GONE);
        // Native / proot are chosen with cards, like the Library's game cards.
        final DesktopRunMode[] selectedMode = {DesktopRunMode.NATIVE};
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        List<MaterialCardView> modeCards = new ArrayList<>();
        modeCards.add(modeCard(R.string.desktop_mode_native, R.string.desktop_mode_native_hint));
        modeCards.add(modeCard(R.string.desktop_mode_proot_title,
            R.string.desktop_mode_proot_hint));
        DesktopRunMode[] modes = {DesktopRunMode.NATIVE, DesktopRunMode.PROOT};
        for (int index = 0; index < modeCards.size(); index++) {
            MaterialCardView card = modeCards.get(index);
            DesktopRunMode mode = modes[index];
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(index == 0 ? 0 : dp(4), dp(6), index == 0 ? dp(4) : 0, 0);
            modeRow.addView(card, params);
            card.setOnClickListener(view -> {
                selectedMode[0] = mode;
                styleModeCards(modeCards, mode == DesktopRunMode.NATIVE ? 0 : 1);
                distro.setVisibility(mode == DesktopRunMode.PROOT ? View.VISIBLE : View.GONE);
            });
        }
        styleModeCards(modeCards, 0);
        form.addView(modeRow);
        form.addView(distro);

        Spinner gl = new Spinner(this);
        gl.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[] {getString(R.string.desktop_gl_system),
                getString(R.string.desktop_gl_zink), getString(R.string.desktop_gl_llvmpipe)}));
        form.addView(label(R.string.desktop_create_gl_label));
        form.addView(gl);

        EditText resolution = field(R.string.desktop_create_resolution_hint,
            InputType.TYPE_CLASS_TEXT);
        resolution.setText(DesktopDistro.DEFAULT_RESOLUTION);
        form.addView(resolution);
        EditText refresh = field(R.string.desktop_create_refresh_hint,
            InputType.TYPE_CLASS_NUMBER);
        refresh.setText(String.valueOf(DesktopDistro.DEFAULT_REFRESH_RATE));
        form.addView(refresh);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new FrostedDialogBuilder(this)
            .setTitle(R.string.desktop_create_title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.desktop_create_action, (dialog, which) -> {
                try {
                    boolean proot = selectedMode[0] == DesktopRunMode.PROOT;
                    DesktopDistro created = new DesktopDistro(
                        "desktop-" + UUID.randomUUID().toString().substring(0, 8),
                        name.getText().toString().trim(), selectedMode[0],
                        proot ? distro.getText().toString().trim() : "",
                        DesktopGlMode.values()[gl.getSelectedItemPosition()],
                        resolution.getText().toString().trim(),
                        Integer.parseInt(refresh.getText().toString().trim()));
                    ioExecutor.execute(() -> {
                        try {
                            tasks.save(created);
                        } catch (IOException error) {
                            toastOnUi(getString(R.string.desktop_error, "desktop_save_failed"));
                        }
                        runOnUiThread(this::refresh);
                    });
                } catch (IllegalArgumentException invalid) {
                    Toast.makeText(this, R.string.desktop_create_invalid, Toast.LENGTH_LONG).show();
                }
            })
            .show();
    }

    private MaterialCardView modeCard(@StringRes int title, @StringRes int hint) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.local_games_surface));
        card.setCardElevation(0f);
        card.setRadius(dp(4));
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(10), dp(10), dp(10), dp(10));
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(14);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface));
        body.addView(titleView);
        TextView hintView = new TextView(this);
        hintView.setText(hint);
        hintView.setTextSize(11);
        hintView.setPadding(0, dp(2), 0, 0);
        hintView.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface_secondary));
        body.addView(hintView);
        card.addView(body);
        return card;
    }

    /** The selected mode card gets the primary outline, the other the neutral hairline. */
    private void styleModeCards(List<MaterialCardView> cards, int selected) {
        for (int index = 0; index < cards.size(); index++) {
            boolean on = index == selected;
            cards.get(index).setStrokeColor(ContextCompat.getColor(this,
                on ? R.color.local_games_primary : R.color.local_games_outline));
            cards.get(index).setStrokeWidth(dp(on ? 2 : 1));
        }
    }

    private EditText field(@StringRes int hint, int inputType) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setInputType(inputType);
        view.setSingleLine(true);
        return view;
    }

    private TextView label(@StringRes int text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setPadding(0, dp(10), 0, 0);
        view.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface_secondary));
        return view;
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
            runOnUiThread(this::refresh);
        });
    }

    private void toastOnUi(String message) {
        runOnUiThread(() -> {
            if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Intent for opening this screen. */
    static Intent createIntent(android.content.Context context) {
        return new Intent(context, DesktopActivity.class);
    }
}
