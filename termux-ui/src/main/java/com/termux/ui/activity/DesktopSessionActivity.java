package com.termux.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import com.termux.ui.R;
import com.termux.ui.api.DesktopTasks;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.databinding.ActivityDesktopSessionBinding;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopSessionState;
import com.termux.ui.platform.DesktopSessionOverrides;
import com.termux.ui.platform.OrientationPreference;
import com.termux.x11.X11SessionView;
import com.termux.x11.session.TaskManagerPanel;
import com.termux.x11.session.metrics.PerformanceMetricsRecorder;
import com.termux.x11.session.metrics.PerformanceMetricsSampler;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Shows one KWin desktop on the X11 session view, with the same multi-level control drawer as the
 * game session (Operation / Performance / Settings / Keyboard / Logs, Exit). The view is bound first so the lorie render
 * bridge is listening; only then is the desktop started and KWin's libtermux-render connects to
 * it.
 */
public final class DesktopSessionActivity extends AppCompatActivity {

    private static final String EXTRA_DISTRO_ID = "com.termux.ui.extra.DESKTOP_ID";
    private static final long POLL_INTERVAL_MS = 1000;
    /** The start script needs a moment to reset a stale status file; ignore it meanwhile. */
    private static final long START_GRACE_MS = 8000;
    /** How many one-second polls poke a running desktop after the view is bound or rotated. */
    private static final int NUDGE_POLLS = 6;

    private static final String STATE_CATEGORY = "desktop-control-category";
    private static final int CATEGORY_OPERATION = 0;
    private static final int CATEGORY_PERFORMANCE = 1;
    private static final int CATEGORY_SETTINGS = 2;
    private static final int CATEGORY_KEYBOARD = 3;
    private static final int CATEGORY_LOGS = 4;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "DesktopSessionIo"));
    /** Reads the desktop log for the Logs page, apart from {@link #ioExecutor}'s start/stop work. */
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "DesktopSessionLog"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTick = this::poll;

    private ActivityDesktopSessionBinding binding;
    private DesktopTasks tasks;
    private DesktopDistro distro;
    private DesktopSessionOverrides overrides;
    /** The global touch mode to put back when this session ends; null if it was not replaced. */
    private String globalTouchMode;
    private boolean destroyed;
    private boolean startRequested;
    private long startRequestedAtMs;
    private boolean everRunning;
    private boolean firstFrame;
    private int selectedCategory = CATEGORY_OPERATION;
    private AudioManager audioManager;
    /** True while the drawer is a bottom sheet (compact window); false for the right-side drawer. */
    private boolean drawerBottomSheet;
    /** True from the start of the slide-out until the drawer layer is actually hidden. */
    private boolean drawerHiding;
    private long lastBackHandledAtMs = -1L;
    /** The task manager embedded in the Performance page; created on first need. */
    @Nullable private TaskManagerPanel processPanel;
    private boolean hostResumed;
    private boolean displayConnected;
    private final PerformanceMetricsSampler metricsSampler = new PerformanceMetricsSampler();
    private PerformanceMetricsRecorder metricsRecorder;
    @Nullable private GameLogPanel logPanel;
    private Spinner touchModeSpinner;
    private Spinner orientationSpinner;
    /** True while the dropdowns are refreshed from storage, so that is not taken as a choice. */
    private boolean refreshingChoices;
    /** Polls left that wake the desktop: the renderer reports a first frame even for a black one. */
    private int nudgesLeft = NUDGE_POLLS;

    static Intent createIntent(Context context, String distroId) {
        return new Intent(context, DesktopSessionActivity.class).putExtra(EXTRA_DISTRO_ID, distroId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra(EXTRA_DISTRO_ID);
        overrides = new DesktopSessionOverrides(this);
        applyOrientation(id != null && DesktopDistro.isValidId(id)
            ? overrides.effectiveOrientation(this, id) : OrientationPreference.load(this));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !DesktopDistro.isValidId(id)) {
            finish();
            return;
        }
        tasks = new DesktopTasks(this);
        try {
            Optional<DesktopDistro> found = tasks.find(id);
            if (!found.isPresent()) {
                finish();
                return;
            }
            distro = found.get();
        } catch (IOException error) {
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        binding = ActivityDesktopSessionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.desktopSessionX11.setDialogPaletteProvider(() -> GameDialogPalettes.frosted(this));
        binding.desktopSessionX11.setImmersive(true);
        binding.desktopSessionX11.bind(this, distro.getResolution(), new X11SessionView.Listener() {
            @Override public void onConnectionChanged(boolean connected) {
                displayConnected = connected;
                updateMetricsSampling();
            }
            @Override public void onFirstFramePresented() {
                firstFrame = true;
                showDesktop();
            }
            @Override public void onControlCenterRequested() { showControlDrawer(); }
            @Override public void onExitRequested() { showExitChoice(); }
            @Override public boolean onProcessManagerRequested() { return showPerformancePage(); }
        });
        applyTouchMode();
        metricsRecorder = new PerformanceMetricsRecorder(this, metricsSampler,
            X11SessionView::getRenderedFrameCount);
        selectedCategory = savedInstanceState == null ? CATEGORY_OPERATION
            : savedInstanceState.getInt(STATE_CATEGORY, CATEGORY_OPERATION);
        binding.desktopSessionCloseWaiting.setOnClickListener(view -> finish());
        configureControlCenter();
        WindowInsetsPadding.apply(binding.desktopSessionDrawerLayer, true, true, true, true);
        applyOverlayLayout(getResources().getConfiguration());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleSessionBack(); }
        });
        poll();
    }

    /** Starts the desktop once, after the display bridge is up, then follows its state. */
    private void poll() {
        handler.removeCallbacks(pollTick);
        if (destroyed || distro == null) return;
        ioExecutor.execute(() -> {
            DesktopSessionState observed = tasks.state(distro);
            if (!startRequested && observed != DesktopSessionState.NOT_INSTALLED
                && observed != DesktopSessionState.STARTING
                && observed != DesktopSessionState.RUNNING) {
                startRequested = true;
                startRequestedAtMs = System.currentTimeMillis();
                try {
                    tasks.start(distro);
                } catch (LaunchHostException error) {
                    runOnUiThread(() -> showFailure(getString(R.string.desktop_error,
                        error.getErrorCode())));
                    return;
                }
            }
            boolean inGrace = startRequested && observed != DesktopSessionState.RUNNING
                && observed != DesktopSessionState.NOT_INSTALLED
                && System.currentTimeMillis() - startRequestedAtMs < START_GRACE_MS;
            DesktopSessionState state = inGrace ? DesktopSessionState.STARTING : observed;
            runOnUiThread(() -> onState(state));
        });
    }

    private void onState(DesktopSessionState state) {
        if (destroyed || binding == null) return;
        switch (state) {
            case NOT_INSTALLED:
                showFailure(getString(R.string.desktop_state_not_installed));
                return;
            case RUNNING:
                everRunning = true;
                // A desktop that was already running only repaints on input, so a freshly bound
                // view stays black until something pokes it.
                if (nudgesLeft > 0) {
                    nudgesLeft--;
                    binding.desktopSessionX11.nudgeDisplay();
                }
                break;
            case FAILED:
                showFailure(getString(R.string.desktop_session_failed));
                return;
            case INSTALLED:
                // Our start was submitted and the desktop later went away: stopped from outside.
                if (everRunning) {
                    finish();
                    return;
                }
                break;
            default:
                break;
        }
        handler.postDelayed(pollTick, POLL_INTERVAL_MS);
    }

    private void showDesktop() {
        if (binding != null) binding.desktopSessionWaiting.setVisibility(View.GONE);
    }

    private void showFailure(String message) {
        if (binding == null) return;
        binding.desktopSessionWaiting.setVisibility(View.VISIBLE);
        binding.desktopSessionProgress.setVisibility(View.GONE);
        binding.desktopSessionStatus.setText(message);
    }

    // ---- control drawer ------------------------------------------------------------------------

    private void configureControlCenter() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        binding.desktopSessionKeyboard.setOnClickListener(view -> binding.desktopSessionX11.toggleKeyboard());
        binding.desktopSessionMenu.setOnClickListener(view -> showControlDrawer());
        binding.desktopSessionCloseDrawer.setOnClickListener(view -> hideControlDrawer());
        binding.desktopSessionDrawerLayer.setOnClickListener(view -> hideControlDrawer());
        binding.desktopSessionDrawer.setOnClickListener(view -> { });
        binding.desktopSessionProcessNewTask.setOnClickListener(view -> {
            if (processPanel != null) processPanel.showNewTaskPrompt();
        });
        binding.desktopSessionCategoryOperation.setOnClickListener(view -> selectCategory(CATEGORY_OPERATION));
        binding.desktopSessionCategoryPerformance.setOnClickListener(view -> selectCategory(CATEGORY_PERFORMANCE));
        binding.desktopSessionCategorySettings.setOnClickListener(view -> selectCategory(CATEGORY_SETTINGS));
        binding.desktopSessionCategoryKeyboard.setOnClickListener(view -> selectCategory(CATEGORY_KEYBOARD));
        binding.desktopSessionCategoryLogs.setOnClickListener(view -> selectCategory(CATEGORY_LOGS));
        binding.desktopSessionExit.setOnClickListener(view -> showExitChoice());
        binding.desktopSessionExitCompact.setOnClickListener(view -> showExitChoice());
        buildOperationPanel();
        configureSessionSettings();
        selectCategory(selectedCategory);
    }

    private void configureSessionSettings() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        float brightness = attributes.screenBrightness < 0 ? 0.5f : attributes.screenBrightness;
        binding.desktopSessionBrightness.setProgress(Math.round(brightness * 100));
        binding.desktopSessionBrightness.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.screenBrightness = Math.max(0.01f, progress / 100f);
                getWindow().setAttributes(params);
            }
        });
        int maxVolume = audioManager == null ? 0
            : audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        binding.desktopSessionVolume.setMax(Math.max(1, maxVolume));
        binding.desktopSessionVolume.setProgress(audioManager == null ? 0
            : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        binding.desktopSessionVolume.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && audioManager != null)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            }
        });
        binding.desktopSessionMouseHelper.setChecked(binding.desktopSessionX11.isMouseHelperEnabled());
        binding.desktopSessionMouseHelper.setOnCheckedChangeListener((button, checked) ->
            binding.desktopSessionX11.setMouseHelperEnabled(checked));
        binding.desktopSessionTouchSensitivity.setProgress(
            binding.desktopSessionX11.getTouchSensitivity());
        binding.desktopSessionTouchSensitivity.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) binding.desktopSessionX11.setTouchSensitivity(progress);
            }
        });
    }

    private static final String[] TOUCH_MODES = {null, DesktopSessionOverrides.TOUCH_TRACKPAD,
        DesktopSessionOverrides.TOUCH_SIMULATED, DesktopSessionOverrides.TOUCH_DIRECT};
    private static final OrientationPreference[] ORIENTATIONS = {null,
        OrientationPreference.HORIZONTAL, OrientationPreference.VERTICAL, OrientationPreference.AUTO};

    /**
     * The Operation page: this desktop's touch mode and screen orientation. Each is a radio list
     * whose first entry means "follow the global setting"; any other entry is stored for this
     * desktop only and wins over the global value.
     */
    private void buildOperationPanel() {
        LinearLayout panel = binding.desktopSessionPanelOperation;
        touchModeSpinner = addChoiceSection(panel, R.string.desktop_session_touch_mode_title,
            TOUCH_MODES.length, i -> touchModeLabel(TOUCH_MODES[i]),
            () -> indexOf(TOUCH_MODES, overrides.getTouchMode(distro.getId())), which -> {
                overrides.setTouchMode(distro.getId(), TOUCH_MODES[which]);
                applyTouchMode();
            });
        orientationSpinner = addChoiceSection(panel, R.string.desktop_session_orientation_title,
            ORIENTATIONS.length, i -> orientationLabel(ORIENTATIONS[i]),
            () -> indexOf(ORIENTATIONS, overrides.getOrientation(distro.getId())), which -> {
                overrides.setOrientation(distro.getId(), ORIENTATIONS[which]);
                applyOrientation(overrides.effectiveOrientation(this, distro.getId()));
            });
        refreshOperationChoices();
    }

    private interface LabelOf { CharSequence at(int index); }
    private interface OnChoice { void picked(int index); }
    private interface StoredIndex { int get(); }

    /**
     * One labelled dropdown. Its first entry means "follow the global setting"; any other entry is
     * stored for this desktop only. A Spinner reports its initial selection once it is laid out, so
     * a selection equal to what is stored is not treated as a change.
     */
    private Spinner addChoiceSection(LinearLayout parent, int titleRes, int count, LabelOf label,
                                     StoredIndex stored, OnChoice onChoice) {
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface));
        title.setTextSize(15);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(16);
        parent.addView(title, titleParams);

        CharSequence[] labels = new CharSequence[count];
        for (int i = 0; i < count; i++) labels[i] = label.at(i);
        int textColor = ContextCompat.getColor(this, R.color.local_games_on_surface);
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(this,
            android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convertView, ViewGroup container) {
                TextView view = (TextView) super.getView(position, convertView, container);
                view.setTextColor(textColor);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup container) {
                TextView view = (TextView) super.getDropDownView(position, convertView, container);
                view.setTextColor(textColor);
                view.setMinHeight(dp(48));
                view.setGravity(Gravity.CENTER_VERTICAL);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> view, View item, int position, long id) {
                if (refreshingChoices || position == stored.get()) return;
                onChoice.picked(position);
            }
            @Override public void onNothingSelected(AdapterView<?> view) { }
        });
        parent.addView(spinner, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return spinner;
    }

    private static <T> int indexOf(T[] values, T value) {
        for (int i = 0; i < values.length; i++) {
            if (value == null ? values[i] == null : value.equals(values[i])) return i;
        }
        return 0;
    }

    private String touchModeLabel(String mode) {
        if (mode == null) return getString(R.string.desktop_session_follow_global);
        switch (mode) {
            case DesktopSessionOverrides.TOUCH_TRACKPAD: return getString(R.string.desktop_touch_trackpad);
            case DesktopSessionOverrides.TOUCH_SIMULATED: return getString(R.string.desktop_touch_simulated);
            default: return getString(R.string.desktop_touch_direct);
        }
    }

    private String orientationLabel(OrientationPreference choice) {
        if (choice == null) return getString(R.string.desktop_session_follow_global);
        switch (choice) {
            case HORIZONTAL: return getString(R.string.local_games_orientation_horizontal);
            case VERTICAL: return getString(R.string.local_games_orientation_vertical);
            default: return getString(R.string.local_games_orientation_auto);
        }
    }

    private void applyOrientation(OrientationPreference preference) {
        setRequestedOrientation(GameSessionOrientation.requestedFor(preference));
    }

    private void selectCategory(int category) {
        selectedCategory = category;
        // Performance and Logs are their own pages (a fixed footer / a list that scrolls itself),
        // not panels of the scrolling panel container.
        binding.desktopSessionPanelScroll.setVisibility(
            category == CATEGORY_PERFORMANCE || category == CATEGORY_LOGS ? View.GONE : View.VISIBLE);
        binding.desktopSessionLogPage.setVisibility(category == CATEGORY_LOGS ? View.VISIBLE : View.GONE);
        binding.desktopSessionProcessPage.setVisibility(
            category == CATEGORY_PERFORMANCE ? View.VISIBLE : View.GONE);
        binding.desktopSessionPanelOperation.setVisibility(
            category == CATEGORY_OPERATION ? View.VISIBLE : View.GONE);
        binding.desktopSessionPanelSettings.setVisibility(
            category == CATEGORY_SETTINGS ? View.VISIBLE : View.GONE);
        binding.desktopSessionPanelKeyboard.setVisibility(
            category == CATEGORY_KEYBOARD ? View.VISIBLE : View.GONE);
        int title;
        switch (category) {
            case CATEGORY_PERFORMANCE: title = R.string.local_game_session_performance_title; break;
            case CATEGORY_SETTINGS: title = R.string.local_game_session_settings_title; break;
            case CATEGORY_KEYBOARD: title = R.string.local_game_session_keyboard_title; break;
            case CATEGORY_LOGS: title = R.string.local_game_session_logs; break;
            default: title = R.string.desktop_session_operation_title; break;
        }
        binding.desktopSessionPanelTitle.setText(title);
        setCategorySelected(binding.desktopSessionCategoryOperation, category == CATEGORY_OPERATION);
        setCategorySelected(binding.desktopSessionCategoryPerformance, category == CATEGORY_PERFORMANCE);
        setCategorySelected(binding.desktopSessionCategorySettings, category == CATEGORY_SETTINGS);
        setCategorySelected(binding.desktopSessionCategoryKeyboard, category == CATEGORY_KEYBOARD);
        setCategorySelected(binding.desktopSessionCategoryLogs, category == CATEGORY_LOGS);
        applyProcessPageLayout();
        updateProcessRefresh();
        updateLogPage();
    }

    private void setCategorySelected(@NonNull MaterialButton button, boolean selected) {
        button.setSelected(selected);
        button.setBackgroundResource(selected
            ? R.drawable.local_games_bg_shortcut : android.R.color.transparent);
        button.setTextColor(ContextCompat.getColor(this, selected
            ? R.color.local_games_primary_text : R.color.local_games_on_surface_secondary));
    }

    private void showControlDrawer() {
        boolean layerVisible = binding.desktopSessionDrawerLayer.getVisibility() == View.VISIBLE;
        if (layerVisible && !drawerHiding) return;
        refreshOperationChoices();
        if (layerVisible) {
            // Re-opened while sliding out: turn around from where it is instead of restarting.
            drawerHiding = false;
            binding.desktopSessionDrawer.animate().cancel();
            binding.desktopSessionDrawer.animate().translationX(0).translationY(0).setDuration(180).start();
            updateProcessRefresh();
            updateLogPage();
            return;
        }
        cancelDrawerAnimation();
        binding.desktopSessionMenu.setVisibility(View.GONE);
        binding.desktopSessionDrawerLayer.setVisibility(View.VISIBLE);
        updateProcessRefresh();
        updateLogPage();
        // The drawer's size is only known after layout: park it off-screen and slide it in on the
        // first draw, so no frame shows it at rest first.
        final View drawer = binding.desktopSessionDrawer;
        drawer.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                drawer.getViewTreeObserver().removeOnPreDrawListener(this);
                if (binding == null || drawerHiding
                    || binding.desktopSessionDrawerLayer.getVisibility() != View.VISIBLE) return true;
                setDrawerTranslation(drawer, drawerHiddenOffset());
                drawer.animate().translationX(0).translationY(0).setDuration(180).start();
                return true;
            }
        });
    }

    /** Shows the stored per-desktop choices (they can change from elsewhere, e.g. another session). */
    private void refreshOperationChoices() {
        if (touchModeSpinner == null || orientationSpinner == null) return;
        refreshingChoices = true;
        try {
            touchModeSpinner.setSelection(
                indexOf(TOUCH_MODES, overrides.getTouchMode(distro.getId())), false);
            orientationSpinner.setSelection(
                indexOf(ORIENTATIONS, overrides.getOrientation(distro.getId())), false);
        } finally {
            refreshingChoices = false;
        }
    }

    private void hideControlDrawer() {
        if (binding == null || drawerHiding
            || binding.desktopSessionDrawerLayer.getVisibility() != View.VISIBLE) return;
        drawerHiding = true;
        // The process refresh must not keep running behind a closing/closed drawer.
        stopProcessRefresh();
        updateLogPage();
        float hidden = drawerHiddenOffset();
        binding.desktopSessionDrawer.animate()
            .translationX(drawerBottomSheet ? 0 : hidden)
            .translationY(drawerBottomSheet ? hidden : 0)
            .setDuration(160)
            .withEndAction(this::finishHidingDrawer)
            .start();
    }

    private void finishHidingDrawer() {
        drawerHiding = false;
        if (binding == null) return;
        stopProcessRefresh();
        binding.desktopSessionDrawerLayer.setVisibility(View.GONE);
        binding.desktopSessionMenu.setVisibility(View.VISIBLE);
        // The desktop only repaints on change; closing the drawer must not leave it black.
        nudgesLeft = NUDGE_POLLS;
    }

    private void cancelDrawerAnimation() {
        binding.desktopSessionDrawer.animate().cancel();
        binding.desktopSessionDrawer.setTranslationX(0);
        binding.desktopSessionDrawer.setTranslationY(0);
        drawerHiding = false;
    }

    /** Distance that moves the drawer fully off its edge: its width (side) or height (bottom sheet). */
    private float drawerHiddenOffset() {
        View drawer = binding.desktopSessionDrawer;
        if (drawerBottomSheet) {
            return drawer.getHeight() > 0 ? drawer.getHeight() : binding.getRoot().getHeight();
        }
        return drawer.getWidth() > 0 ? drawer.getWidth()
            : dp(OverlayDrawerSizing.MAX_DRAWER_WIDTH_DP);
    }

    private void setDrawerTranslation(@NonNull View drawer, float offset) {
        drawer.setTranslationX(drawerBottomSheet ? 0 : offset);
        drawer.setTranslationY(drawerBottomSheet ? offset : 0);
    }

    /** Exit leaves the view, optionally stopping the desktop with it. */
    private void showExitChoice() {
        new FrostedDialogBuilder(this)
            .setTitle(R.string.desktop_session_exit_title)
            .setItems(new CharSequence[] {getString(R.string.desktop_session_close),
                getString(R.string.desktop_session_stop)}, (dialog, which) -> {
                    if (which == 1) {
                        ioExecutor.execute(() -> {
                            try {
                                tasks.stop(distro);
                            } catch (LaunchHostException ignored) {
                                // The list screen shows the resulting state.
                            }
                        });
                    }
                    finish();
                })
            .show();
    }

    /**
     * BACK drives the control drawer only: closed -> open it, open -> close it. It reaches this
     * through the back callback, onBackPressed and dispatchKeyEvent, so a repeat within a few
     * milliseconds is the same press.
     */
    private void handleSessionBack() {
        if (binding == null) return;
        long now = SystemClock.uptimeMillis();
        if (GameSessionBackPolicy.isDuplicate(lastBackHandledAtMs, now)) return;
        lastBackHandledAtMs = now;
        boolean drawerOpen = binding.desktopSessionDrawerLayer.getVisibility() == View.VISIBLE
            && !drawerHiding;
        switch (GameSessionBackPolicy.decide(drawerOpen, false, false)) {
            case OPEN_DRAWER:
                showControlDrawer();
                break;
            case CLOSE_DRAWER:
                hideControlDrawer();
                break;
            default:
                break;
        }
    }

    @Override public void onBackPressed() { handleSessionBack(); }

    @Override public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                handleSessionBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- Performance and Logs pages --------------------------------------------------------------

    /** Opens the drawer on the Performance page (the host's "show process manager" request). */
    private boolean showPerformancePage() {
        if (binding == null || destroyed) return false;
        if (!ensureProcessPanel()) return false;
        selectCategory(CATEGORY_PERFORMANCE);
        showControlDrawer();
        updateProcessRefresh();
        return true;
    }

    /** Creates the embedded task manager on first need; false while the X11 runtime is not bound yet. */
    private boolean ensureProcessPanel() {
        if (processPanel != null) return true;
        if (binding == null || destroyed) return false;
        View content = TaskManagerPanel.inflateContent(this, binding.desktopSessionProcessHost);
        TaskManagerPanel panel;
        try {
            panel = binding.desktopSessionX11.createTaskManagerPanel(content, new TaskManagerPanel.Listener() {
                @Override public void onProcessCountChanged(int count) {
                    if (destroyed || binding == null) return;
                    binding.desktopSessionProcessCount.setText(
                        getString(com.termux.x11.R.string.processes) + ": " + count);
                }
                @Override public void onBringToFront() {
                    if (!destroyed && binding != null) hideControlDrawer();
                }
            });
        } catch (IllegalStateException notBound) {
            return false;
        }
        binding.desktopSessionProcessHost.removeAllViews();
        binding.desktopSessionProcessHost.addView(content);
        binding.desktopSessionProcessCount.setText(getString(com.termux.x11.R.string.processes) + ": 0");
        processPanel = panel;
        panel.setMetricsExpanded(false);
        panel.setMetricsSampler(metricsSampler);
        applyProcessPageLayout();
        return true;
    }

    /** The 1s process refresh runs only while the Performance page is shown in an open, resumed drawer. */
    private void updateProcessRefresh() {
        if (binding == null || destroyed) return;
        boolean drawerOpen = binding.desktopSessionDrawerLayer.getVisibility() == View.VISIBLE
            && !drawerHiding;
        boolean wanted = drawerOpen && hostResumed && selectedCategory == CATEGORY_PERFORMANCE;
        if (!wanted) {
            stopProcessRefresh();
            return;
        }
        if (!ensureProcessPanel()) return;
        try {
            processPanel.start();
        } catch (IllegalStateException winHandlerNotReady) {
            // Nothing was started; selecting the page again retries.
        }
    }

    private void stopProcessRefresh() {
        if (processPanel != null) processPanel.stop();
    }

    private void updateMetricsSampling() {
        if (metricsRecorder == null || destroyed) return;
        if (hostResumed && displayConnected) metricsRecorder.start();
        else metricsRecorder.stop();
    }

    /** The Logs page is "shown" only while it is selected in an open drawer. */
    private void updateLogPage() {
        if (binding == null || destroyed) return;
        boolean drawerOpen = binding.desktopSessionDrawerLayer.getVisibility() == View.VISIBLE
            && !drawerHiding;
        if (drawerOpen && selectedCategory == CATEGORY_LOGS) {
            ensureLogPanel().setShown(true);
        } else if (logPanel != null) {
            logPanel.setShown(false);
        }
    }

    private GameLogPanel ensureLogPanel() {
        if (logPanel == null) {
            logPanel = new GameLogPanel(this, logExecutor, binding.desktopSessionLogList,
                binding.desktopSessionLogStatus, binding.desktopSessionLogRefresh);
            logPanel.setLogRef(new GameStoragePaths(getFilesDir()).getDesktopLogFile(distro.getId())
                .getPath());
        }
        return logPanel;
    }

    /** Sizes the Performance page for the current window and drawer form (re-run on rotation). */
    private void applyProcessPageLayout() {
        if (processPanel == null || binding == null) return;
        Configuration config = getResources().getConfiguration();
        float density = getResources().getDisplayMetrics().density;
        int widthDp = config.screenWidthDp > 0 ? config.screenWidthDp
            : Math.round(binding.getRoot().getWidth() / density);
        int heightDp = config.screenHeightDp > 0 ? config.screenHeightDp
            : Math.round(binding.getRoot().getHeight() / density);
        DrawerProcessPageSizing.Result sizing = DrawerProcessPageSizing.compute(widthDp, heightDp,
            drawerBottomSheet ? OverlayDrawerSizing.Placement.BOTTOM_SHEET
                : OverlayDrawerSizing.Placement.SIDE,
            Runtime.getRuntime().availableProcessors());
        LinearLayout.LayoutParams pageParams =
            (LinearLayout.LayoutParams) binding.desktopSessionProcessPage.getLayoutParams();
        if (sizing.explicitHeight) {
            // A bottom sheet wraps its content, so the page needs a definite height.
            pageParams.height = dp(sizing.panelHeightDp + DrawerProcessPageSizing.FOOTER_DP);
            pageParams.weight = 0f;
        } else {
            pageParams.height = 0;
            pageParams.weight = 1f;
        }
        binding.desktopSessionProcessPage.setLayoutParams(pageParams);
        placeProcessPageFooter(sizing.footerFixed);
        processPanel.applyLayout(sizing.layout, true);
    }

    /** The footer stays below the page when the window is tall enough, else ends the scrolled content. */
    private void placeProcessPageFooter(boolean footerFixed) {
        ViewGroup page = binding.desktopSessionProcessPage;
        ViewGroup scrollContent = binding.desktopSessionProcessScrollContent;
        View footer = binding.desktopSessionProcessFooter;
        ViewGroup parent = footerFixed ? page : scrollContent;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        if (footer.getParent() == parent) {
            footer.setLayoutParams(params);
            return;
        }
        ViewGroup old = (ViewGroup) footer.getParent();
        if (old != null) old.removeView(footer);
        parent.addView(footer, parent.getChildCount(), params);
    }

    /** Fits the control drawer to the current window: width, and side column vs. top strip. */
    private void applyOverlayLayout(@NonNull Configuration config) {
        float density = getResources().getDisplayMetrics().density;
        int widthDp = config.screenWidthDp;
        int heightDp = config.screenHeightDp;
        if (widthDp <= 0 || heightDp <= 0) {
            widthDp = Math.round(binding.getRoot().getWidth() / density);
            heightDp = Math.round(binding.getRoot().getHeight() / density);
        }
        boolean compact = widthDp > 0 && OverlayDrawerSizing.placementFor(widthDp, heightDp)
            == OverlayDrawerSizing.Placement.BOTTOM_SHEET;
        // The placement may flip under an open drawer (rotation): drop any half-run slide first.
        boolean wasHiding = drawerHiding;
        cancelDrawerAnimation();
        if (wasHiding) finishHidingDrawer();
        drawerBottomSheet = compact;
        int itemHeight = getResources().getDimensionPixelSize(R.dimen.local_games_menu_row_min_height);
        int gap = getResources().getDimensionPixelSize(R.dimen.local_games_overlay_nav_gap);

        FrameLayout.LayoutParams drawerParams =
            (FrameLayout.LayoutParams) binding.desktopSessionDrawer.getLayoutParams();
        if (compact) {
            drawerParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            drawerParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            drawerParams.gravity = Gravity.BOTTOM;
        } else {
            drawerParams.width = widthDp > 0
                ? OverlayDrawerSizing.drawerWidthPx(Math.round(widthDp * density), density)
                : dp(OverlayDrawerSizing.MAX_DRAWER_WIDTH_DP);
            drawerParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            drawerParams.gravity = Gravity.END;
        }
        binding.desktopSessionDrawer.setCapHeight(compact);
        binding.desktopSessionDrawer.setBackgroundResource(compact
            ? R.drawable.local_games_bg_overlay_sheet : R.drawable.local_games_bg_overlay);
        binding.desktopSessionDrawer.setLayoutParams(drawerParams);
        binding.desktopSessionDrawer.setOrientation(
            compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams hostParams =
            (LinearLayout.LayoutParams) binding.desktopSessionNavHost.getLayoutParams();
        hostParams.width = compact ? ViewGroup.LayoutParams.MATCH_PARENT
            : getResources().getDimensionPixelSize(R.dimen.local_games_overlay_nav_width);
        hostParams.height = compact ? ViewGroup.LayoutParams.WRAP_CONTENT
            : ViewGroup.LayoutParams.MATCH_PARENT;
        binding.desktopSessionNavHost.setLayoutParams(hostParams);

        LinearLayout nav = binding.desktopSessionNav;
        ViewGroup targetParent = compact ? binding.desktopSessionNavStrip : binding.desktopSessionNavHost;
        if (nav.getParent() != targetParent) {
            ((ViewGroup) nav.getParent()).removeView(nav);
            targetParent.addView(nav, new FrameLayout.LayoutParams(
                compact ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                compact ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT));
        }
        binding.desktopSessionNavStrip.setVisibility(compact ? View.VISIBLE : View.GONE);
        nav.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (View button : new View[] {
            binding.desktopSessionCategoryOperation, binding.desktopSessionCategoryPerformance,
            binding.desktopSessionCategorySettings, binding.desktopSessionCategoryKeyboard,
            binding.desktopSessionCategoryLogs, binding.desktopSessionExit}) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
            params.width = compact ? ViewGroup.LayoutParams.WRAP_CONTENT
                : ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = itemHeight;
            params.setMarginEnd(compact ? dp(4) : 0);
            button.setLayoutParams(params);
        }
        binding.desktopSessionNavSpacer.setVisibility(compact ? View.GONE : View.VISIBLE);
        // Compact: the nav strip scrolls, so Exit lives in the panel header next to the close button.
        binding.desktopSessionExit.setVisibility(compact ? View.GONE : View.VISIBLE);
        binding.desktopSessionExitCompact.setVisibility(compact ? View.VISIBLE : View.GONE);

        LinearLayout.LayoutParams contentParams =
            (LinearLayout.LayoutParams) binding.desktopSessionContent.getLayoutParams();
        contentParams.width = compact ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
        contentParams.height = compact ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
        contentParams.weight = 1f;
        contentParams.setMarginStart(compact ? 0 : gap);
        contentParams.topMargin = compact ? gap : 0;
        binding.desktopSessionContent.setLayoutParams(contentParams);
        applyProcessPageLayout();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }

    /**
     * Puts this desktop's effective touch mode into the X11 view: its own choice, else the global
     * setting. The view only reads the global preference, so the real global value is remembered
     * and restored when the session ends.
     */
    private void applyTouchMode() {
        if (binding == null || distro == null) return;
        X11SessionView view = binding.desktopSessionX11;
        String stranded = overrides.getPendingGlobalTouchMode();
        if (stranded != null && globalTouchMode == null) {
            // A crashed session left its override behind; the saved value is the real global one.
            view.setTouchModeValue(stranded);
            overrides.clearPendingGlobalTouchMode();
        }
        if (globalTouchMode == null) globalTouchMode = view.getTouchModeValue();
        String own = overrides.getTouchMode(distro.getId());
        if (own != null) overrides.rememberGlobalTouchMode(globalTouchMode);
        view.setTouchModeValue(DesktopSessionOverrides.effectiveTouchMode(own, globalTouchMode));
        if (own == null) overrides.clearPendingGlobalTouchMode();
    }

    private void restoreGlobalTouchMode() {
        if (binding != null && globalTouchMode != null) {
            binding.desktopSessionX11.setTouchModeValue(globalTouchMode);
        }
        overrides.clearPendingGlobalTouchMode();
        globalTouchMode = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        hostResumed = true;
        if (binding != null) {
            binding.desktopSessionX11.onHostResume();
            updateProcessRefresh();
        }
        updateMetricsSampling();
    }

    @Override
    protected void onPause() {
        hostResumed = false;
        updateMetricsSampling();
        stopProcessRefresh();
        if (binding != null) binding.desktopSessionX11.onHostPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_CATEGORY, selectedCategory);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (binding != null) {
            binding.desktopSessionX11.onHostWindowFocusChanged(hasFocus);
            if (hasFocus) {
                // Closing the menu hands the window back; its surface has no new frame until
                // the desktop is poked, like after binding or rotating.
                nudgesLeft = NUDGE_POLLS;
                handler.postDelayed(() -> {
                    if (!destroyed && binding != null) binding.desktopSessionX11.nudgeDisplay();
                }, 250);
            }
        }
    }

    /** The manifest handles rotation in place, so the X11 view has to be told or it stays black. */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (binding != null) {
            binding.desktopSessionX11.onHostConfigurationChanged(newConfig);
            // The new surface has no frame yet; the next polls nudge the desktop to send one.
            nudgesLeft = NUDGE_POLLS;
            applyOverlayLayout(newConfig);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(pollTick);
        if (overrides != null) restoreGlobalTouchMode();
        if (metricsRecorder != null) metricsRecorder.stop();
        if (processPanel != null) {
            processPanel.release();
            processPanel.stop();
            processPanel = null;
        }
        if (logPanel != null) {
            logPanel.release();
            logPanel = null;
        }
        if (binding != null) binding.desktopSessionX11.unbind();
        ioExecutor.shutdown();
        logExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
