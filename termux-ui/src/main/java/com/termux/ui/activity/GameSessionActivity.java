package com.termux.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import com.termux.ui.api.LaunchTasks;
import com.termux.ui.data.FileRuntimeProfileRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.data.LaunchSpecCodec;
import com.termux.ui.platform.OrientationPreference;
import com.termux.ui.databinding.ActivityGameSessionBinding;
import com.termux.ui.domain.LaunchSpec;
import com.termux.ui.domain.LaunchStage;
import com.termux.ui.domain.LaunchTask;
import com.termux.ui.runtime.OrchestrationException;
import com.termux.ui.runtime.Subscription;
import com.termux.ui.runtime.TaskObserver;
import com.termux.ui.service.PersistentLocalGameOrchestrator;
import com.termux.x11.X11SessionView;
import com.termux.x11.session.TaskManagerPanel;
import com.termux.x11.session.metrics.PerformanceMetricsRecorder;
import com.termux.x11.session.metrics.PerformanceMetricsSampler;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Game session shell for one persisted launch task; process ownership remains in the Service. */
public final class GameSessionActivity extends AppCompatActivity {

    private static final String STATE_CATEGORY = "session-control-category";
    private static final String STATE_INPUT_SELECTION = "session-input-selection";
    private static final String STATE_METRICS_EXPANDED = "session-metrics-expanded";
    private static final int CATEGORY_OPERATION = 0;
    private static final int CATEGORY_PERFORMANCE = 1;
    private static final int CATEGORY_SETTINGS = 2;
    private static final int CATEGORY_KEYBOARD = 3;
    private static final int CATEGORY_LOGS = 4;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesSessionIo"));
    /** Reads the launch log for the Logs page; separate so a large log never delays {@link #ioExecutor}'s session reports. */
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesSessionLog"));
    private ActivityGameSessionBinding binding;
    private PersistentLocalGameOrchestrator orchestrator;
    private Subscription subscription;
    private String taskId;
    private boolean destroyed;
    private boolean exitRequested;
    private boolean exitConfirmationVisible;
    private volatile boolean exitConfirmationPending;
    private boolean libraryReturnStarted;
    private long lastBackHandledAtMs = -1L;
    /** True while the drawer is a bottom sheet (compact window); false for the right-side drawer. */
    private boolean drawerBottomSheet;
    /** True from the start of the slide-out until the drawer layer is actually hidden. */
    private boolean drawerHiding;
    /**
     * The task manager embedded in the Performance page; created on first need and then kept for
     * the Activity's life (its refresh timer only runs while that page is visible, see
     * {@link #updateProcessRefresh()}). Null until then.
     */
    @Nullable
    private TaskManagerPanel processPanel;
    /** True between onResume and onPause: the refresh timer never runs while paused. */
    private boolean hostResumed;
    /** True while the X11 display is connected, i.e. the game session is actually running. */
    private boolean displayConnected;
    /**
     * Last minute of FPS/CPU/memory for the Performance page. One instance per Activity (a new
     * session starts empty); sampled by {@link #metricsRecorder} independently of the Performance
     * page being visible.
     */
    private final PerformanceMetricsSampler metricsSampler = new PerformanceMetricsSampler();
    private PerformanceMetricsRecorder metricsRecorder;
    /** The Expand/Collapse choice; survives Activity recreation through the saved state. */
    private boolean metricsExpanded;
    private LaunchTask currentTask;
    /** The Logs page controller; created the first time that page is shown. Null until then. */
    @Nullable
    private GameLogPanel logPanel;
    private int selectedCategory = CATEGORY_OPERATION;
    private AudioManager audioManager;
    private GameControllerPanel controllerPanel;
    /** True while the Controller sub-page (third drawer level) replaces the Operation panel. */
    private boolean controllerPageOpen;
    private String gameId;
    /** Latest xinput/dinput[:id] selection of this session (frozen value until the user changes it). */
    private String inputSelection;

    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull String taskId) {
        return new Intent(context, GameSessionActivity.class)
            .putExtra(LaunchTasks.EXTRA_TASK_ID, taskId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The session follows the Settings "Orientation" choice (the manifest no longer pins landscape).
        setRequestedOrientation(GameSessionOrientation.requestedFor(OrientationPreference.load(this)));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            finish();
            return;
        }
        taskId = getIntent().getStringExtra(LaunchTasks.EXTRA_TASK_ID);
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,128}")) {
            finish();
            return;
        }
        LaunchSpec frozenSpec = readFrozenLaunchSpec();
        if (frozenSpec == null) {
            finish();
            return;
        }
        // Immersive session: the content also covers the display cutout area (the system bars are
        // hidden by the X11 host on window focus, see X11SessionView#setImmersive).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams windowAttributes = getWindow().getAttributes();
            windowAttributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(windowAttributes);
        }
        binding = ActivityGameSessionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        orchestrator = new PersistentLocalGameOrchestrator(this);
        binding.gameSessionX11.setDialogPaletteProvider(() -> GameDialogPalettes.frosted(this));
        binding.gameSessionX11.setImmersive(true);
        binding.gameSessionX11.bind(this, frozenSpec.getResolution(), new X11SessionView.Listener() {
            @Override public void onConnectionChanged(boolean connected) {
                displayConnected = connected;
                updateMetricsSampling();
                reportConnection(connected);
            }
            @Override public void onFirstFramePresented() { reportFirstFrame(); }
            @Override public void onControlCenterRequested() { showControlDrawer(); }
            @Override public void onExitRequested() { requestSafeExitConfirmation(); }
            @Override public boolean onProcessManagerRequested() { return showPerformancePage(); }
        });
        metricsExpanded = savedInstanceState != null
            && savedInstanceState.getBoolean(STATE_METRICS_EXPANDED, false);
        // FPS = frames the X11/Lorie renderer presented (cumulative counter, differenced per second).
        metricsRecorder = new PerformanceMetricsRecorder(this, metricsSampler,
            X11SessionView::getRenderedFrameCount);
        selectedCategory = savedInstanceState == null ? CATEGORY_OPERATION
            : savedInstanceState.getInt(STATE_CATEGORY, CATEGORY_OPERATION);
        configureControlCenter();
        WindowInsetsPadding.apply(binding.gameSessionDrawerLayer, true, true, true, true);
        applyOverlayLayout(getResources().getConfiguration());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleSessionBack(); }
        });
        gameId = frozenSpec.getGameId();
        String restoredSelection = savedInstanceState == null ? null
            : savedInstanceState.getString(STATE_INPUT_SELECTION);
        if (GameInputProfileChoices.isValidSelection(restoredSelection)
            && restoredSelection.startsWith(GameInputProfileChoices.mapperOf(
                frozenSpec.getInputProfileId()))) {
            // Activity recreated (e.g. dark-mode switch): keep what the user picked in this session.
            inputSelection = restoredSelection;
            applyInputSelection(restoredSelection);
        } else {
            inputSelection = frozenSpec.getInputProfileId();
            applyFrozenInputProfile(frozenSpec);
        }
        setUpControllerRow();
        observeTask();
    }

    private void configureControlCenter() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        binding.gameSessionKeyboard.setOnClickListener(view -> binding.gameSessionX11.toggleKeyboard());
        binding.gameSessionMenu.setOnClickListener(view -> showControlDrawer());
        binding.gameSessionCloseDrawer.setOnClickListener(view -> hideControlDrawer());
        binding.gameSessionDrawerLayer.setOnClickListener(view -> hideControlDrawer());
        binding.gameSessionDrawer.setOnClickListener(view -> { });
        binding.gameSessionProcessNewTask.setOnClickListener(view -> {
            if (processPanel != null) processPanel.showNewTaskPrompt();
        });
        binding.gameSessionPanelBack.setOnClickListener(view -> setControllerPageOpen(false));
        binding.gameSessionCategoryOperation.setOnClickListener(view -> selectCategory(CATEGORY_OPERATION));
        binding.gameSessionCategoryPerformance.setOnClickListener(view -> selectCategory(CATEGORY_PERFORMANCE));
        binding.gameSessionCategorySettings.setOnClickListener(view -> selectCategory(CATEGORY_SETTINGS));
        binding.gameSessionCategoryKeyboard.setOnClickListener(view -> selectCategory(CATEGORY_KEYBOARD));
        binding.gameSessionCategoryLogs.setOnClickListener(view -> selectCategory(CATEGORY_LOGS));
        binding.gameSessionExit.setOnClickListener(view -> requestSafeExitConfirmation());
        binding.gameSessionExitCompact.setOnClickListener(view -> requestSafeExitConfirmation());
        configureSessionSettings();
        selectCategory(selectedCategory);
    }

    private void configureSessionSettings() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        float brightness = attributes.screenBrightness < 0 ? 0.5f : attributes.screenBrightness;
        binding.gameSessionBrightness.setProgress(Math.round(brightness * 100));
        binding.gameSessionBrightness.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.screenBrightness = Math.max(0.01f, progress / 100f);
                getWindow().setAttributes(params);
            }
        });

        int maxVolume = audioManager == null ? 0
            : audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        binding.gameSessionVolume.setMax(Math.max(1, maxVolume));
        binding.gameSessionVolume.setProgress(audioManager == null ? 0
            : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        binding.gameSessionVolume.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && audioManager != null)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            }
        });

        binding.gameSessionMouseHelper.setChecked(binding.gameSessionX11.isMouseHelperEnabled());
        binding.gameSessionMouseHelper.setOnCheckedChangeListener((button, checked) -> {
            binding.gameSessionX11.setMouseHelperEnabled(checked);
        });
        binding.gameSessionTouchSensitivity.setProgress(
            binding.gameSessionX11.getTouchSensitivity());
        binding.gameSessionTouchSensitivity.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                binding.gameSessionX11.setTouchSensitivity(progress);
            }
        });
    }

    @Nullable
    private LaunchSpec readFrozenLaunchSpec() {
        try {
            File source = new File(new GameStoragePaths(getFilesDir()).getLaunchSpecsDirectory(),
                taskId + ".launchspec");
            return new LaunchSpecCodec().read(source);
        } catch (Exception error) {
            return null;
        }
    }

    private void applyFrozenInputProfile(@NonNull LaunchSpec spec) {
        applyInputSelection(spec.getInputProfileId());
    }

    private void applyInputSelection(@NonNull String selection) {
        if (!binding.gameSessionX11.applyInputProfile(selection))
            binding.gameSessionInputWarning.setVisibility(View.VISIBLE);
    }

    /**
     * Adds the "Controller" row, the Operation panel's only controller entry, below its
     * summary; the row opens the Controller sub-page.
     */
    private void setUpControllerRow() {
        LinearLayout host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        binding.gameSessionPanelOperation.addView(host, params);
        controllerPanel = new GameControllerPanel(this, binding.gameSessionX11, host,
            binding.gameSessionControllerList, binding.gameSessionControllerNotice, inputSelection,
            this::persistInputSelection, this::openControllerPage);
        controllerPanel.render();
    }

    /** Applies nothing (the panel already did); remembers the choice for the next launch. */
    private void persistInputSelection(@NonNull String selection) {
        inputSelection = selection;
        if (destroyed || gameId == null) return;
        final String id = gameId;
        final File profilesDirectory = new GameStoragePaths(getFilesDir()).getProfilesDirectory();
        try {
            ioExecutor.execute(() -> {
                boolean saved = GameInputProfileChoices.persist(
                    new FileRuntimeProfileRepository(profilesDirectory), id, selection);
                if (!saved) runOnUiThread(() -> {
                    if (!destroyed && controllerPanel != null) {
                        controllerPanel.showNotice(R.string.local_game_controller_save_failed);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // Activity is shutting down; the selection stays session-local.
        }
    }

    private void observeTask() {
        try {
            subscription = orchestrator.observe(taskId, new TaskObserver<LaunchTask>() {
                @Override public void onChanged(LaunchTask value) {
                    runOnUiThread(() -> { if (!destroyed) render(value); });
                }
                @Override public void onError(Throwable error) {
                    runOnUiThread(() -> { if (!destroyed) finish(); });
                }
            });
        } catch (OrchestrationException error) {
            finish();
        }
    }

    private void render(LaunchTask task) {
        currentTask = task;
        if (logPanel != null) logPanel.setLogRef(task.getLogRef());
        if (task.getState().isTerminal()) {
            if ((!exitConfirmationVisible && !exitConfirmationPending) || exitRequested)
                returnToGameLibrary();
            return;
        }
        if (task.getStage() == LaunchStage.RUNNING) {
            binding.gameSessionWaiting.setVisibility(View.GONE);
            binding.gameSessionStatus.setText(R.string.local_game_session_running);
        } else {
            binding.gameSessionWaiting.setVisibility(View.VISIBLE);
            binding.gameSessionStatus.setText(task.isDisplayConnected()
                ? R.string.local_game_session_waiting_frame
                : R.string.local_game_session_connecting);
        }
    }

    private void reportConnection(boolean connected) {
        if (destroyed || orchestrator == null) return;
        ioExecutor.execute(() -> {
            try { orchestrator.reportDisplayConnection(taskId, connected); }
            catch (OrchestrationException ignored) { }
        });
    }

    private void reportFirstFrame() {
        if (destroyed || orchestrator == null) return;
        ioExecutor.execute(() -> {
            try { orchestrator.reportFirstFrame(taskId); }
            catch (OrchestrationException ignored) { }
        });
    }

    private void requestSafeExitConfirmation() {
        if (destroyed || exitRequested || exitConfirmationVisible || exitConfirmationPending) return;
        exitConfirmationPending = true;
        runOnUiThread(() -> {
            exitConfirmationPending = false;
            confirmSafeExit();
        });
    }

    private void confirmSafeExit() {
        if (destroyed || exitRequested || exitConfirmationVisible || isFinishing()) return;
        exitConfirmationVisible = true;
        new FrostedDialogBuilder(this)
            .setTitle(R.string.local_game_session_exit_title)
            .setMessage(R.string.local_game_session_exit_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_game_session_exit_confirm, (dialog, which) -> safeExit())
            .setOnDismissListener(dialog -> {
                exitConfirmationVisible = false;
                exitConfirmationPending = false;
                if (!exitRequested && currentTask != null && currentTask.getState().isTerminal()) {
                    returnToGameLibrary();
                }
            })
            .show();
    }

    private void returnToGameLibrary() {
        if (libraryReturnStarted || destroyed || isFinishing()) return;
        libraryReturnStarted = true;
        Intent intent = LocalGames.createLaunchIntent(this)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void selectCategory(int category) {
        selectedCategory = category;
        // Picking any category (including Operation again) returns to that category's top level.
        setControllerPageOpen(false);
        // Performance and Logs are their own pages (a fixed footer / a list that scrolls itself), not
        // panels of the scrolling panel container.
        binding.gameSessionPanelScroll.setVisibility(
            category == CATEGORY_PERFORMANCE || category == CATEGORY_LOGS ? View.GONE : View.VISIBLE);
        binding.gameSessionLogPage.setVisibility(category == CATEGORY_LOGS ? View.VISIBLE : View.GONE);
        binding.gameSessionProcessPage.setVisibility(
            category == CATEGORY_PERFORMANCE ? View.VISIBLE : View.GONE);
        binding.gameSessionPanelSettings.setVisibility(
            category == CATEGORY_SETTINGS ? View.VISIBLE : View.GONE);
        binding.gameSessionPanelKeyboard.setVisibility(
            category == CATEGORY_KEYBOARD ? View.VISIBLE : View.GONE);
        int title;
        switch (category) {
            case CATEGORY_PERFORMANCE:
                title = R.string.local_game_session_performance_title;
                break;
            case CATEGORY_SETTINGS:
                title = R.string.local_game_session_settings_title;
                break;
            case CATEGORY_KEYBOARD:
                title = R.string.local_game_session_keyboard_title;
                break;
            case CATEGORY_LOGS:
                title = R.string.local_game_session_logs;
                break;
            default:
                title = R.string.local_game_session_operation_title;
                break;
        }
        binding.gameSessionPanelTitle.setText(title);
        setCategorySelected(binding.gameSessionCategoryOperation, category == CATEGORY_OPERATION);
        setCategorySelected(binding.gameSessionCategoryPerformance, category == CATEGORY_PERFORMANCE);
        setCategorySelected(binding.gameSessionCategorySettings, category == CATEGORY_SETTINGS);
        setCategorySelected(binding.gameSessionCategoryKeyboard, category == CATEGORY_KEYBOARD);
        setCategorySelected(binding.gameSessionCategoryLogs, category == CATEGORY_LOGS);
        applyProcessPageLayout();
        updateProcessRefresh();
        updateLogPage();
    }

    /** Opens the Controller sub-page (a row of the Operation panel leads here). */
    private void openControllerPage() {
        if (binding == null || destroyed) return;
        selectCategory(CATEGORY_OPERATION);
        if (controllerPanel != null) {
            controllerPanel.clearNotice();
            controllerPanel.render();
        }
        setControllerPageOpen(true);
    }

    /**
     * Shows or leaves the Controller sub-page. The page only exists under the Operation
     * category: while open it replaces the Operation panel and the header gets a back button and the
     * page title.
     */
    private void setControllerPageOpen(boolean open) {
        if (binding == null) return;
        boolean onOperation = selectedCategory == CATEGORY_OPERATION;
        controllerPageOpen = open && onOperation;
        binding.gameSessionPanelOperation.setVisibility(
            onOperation && !controllerPageOpen ? View.VISIBLE : View.GONE);
        binding.gameSessionPanelController.setVisibility(
            controllerPageOpen ? View.VISIBLE : View.GONE);
        binding.gameSessionPanelBack.setVisibility(controllerPageOpen ? View.VISIBLE : View.GONE);
        if (onOperation) {
            binding.gameSessionPanelTitle.setText(controllerPageOpen
                ? R.string.local_game_controller : R.string.local_game_session_operation_title);
        }
        if (controllerPageOpen) binding.gameSessionPanelScroll.scrollTo(0, 0);
        else if (controllerPanel != null) controllerPanel.render();
    }

    private void setCategorySelected(@NonNull MaterialButton button, boolean selected) {
        button.setSelected(selected);
        button.setBackgroundResource(selected
            ? R.drawable.local_games_bg_shortcut : android.R.color.transparent);
        button.setTextColor(ContextCompat.getColor(this, selected
            ? R.color.local_games_primary_text : R.color.local_games_on_surface_secondary));
    }

    private void showControlDrawer() {
        boolean layerVisible = binding.gameSessionDrawerLayer.getVisibility() == View.VISIBLE;
        if (layerVisible && !drawerHiding) return;
        if (controllerPanel != null) controllerPanel.render();
        if (layerVisible) {
            // Re-opened while sliding out: turn around from where it is instead of restarting.
            drawerHiding = false;
            binding.gameSessionDrawer.animate().cancel();
            binding.gameSessionDrawer.animate().translationX(0).translationY(0).setDuration(180).start();
            updateProcessRefresh();
            updateLogPage();
            return;
        }
        cancelDrawerAnimation();
        binding.gameSessionMenu.setVisibility(View.GONE);
        binding.gameSessionDrawerLayer.setVisibility(View.VISIBLE);
        updateProcessRefresh();
        updateLogPage();
        // The drawer's size is only known after layout: park it off-screen and slide it in on the
        // first draw, so no frame shows it at rest first.
        final View drawer = binding.gameSessionDrawer;
        drawer.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                drawer.getViewTreeObserver().removeOnPreDrawListener(this);
                if (binding == null || drawerHiding
                    || binding.gameSessionDrawerLayer.getVisibility() != View.VISIBLE) return true;
                setDrawerTranslation(drawer, drawerHiddenOffset());
                drawer.animate().translationX(0).translationY(0).setDuration(180).start();
                return true;
            }
        });
    }

    private void hideControlDrawer() {
        if (binding == null || drawerHiding
            || binding.gameSessionDrawerLayer.getVisibility() != View.VISIBLE) return;
        drawerHiding = true;
        // The process refresh must not keep running behind a closing/closed drawer, and the log
        // search keyboard must not stay up over the game.
        stopProcessRefresh();
        updateLogPage();
        float hidden = drawerHiddenOffset();
        binding.gameSessionDrawer.animate()
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
        binding.gameSessionDrawerLayer.setVisibility(View.GONE);
        binding.gameSessionMenu.setVisibility(View.VISIBLE);
    }

    /** Stops any running slide and puts the drawer back at rest, whichever way it was sliding. */
    private void cancelDrawerAnimation() {
        binding.gameSessionDrawer.animate().cancel();
        binding.gameSessionDrawer.setTranslationX(0);
        binding.gameSessionDrawer.setTranslationY(0);
        drawerHiding = false;
    }

    /** Distance that moves the drawer fully off its edge: its width (side) or height (bottom sheet). */
    private float drawerHiddenOffset() {
        View drawer = binding.gameSessionDrawer;
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

    private void safeExit() {
        exitRequested = true;
        binding.gameSessionWaiting.setVisibility(View.VISIBLE);
        binding.gameSessionStatus.setText(R.string.local_game_launch_cancel);
        ioExecutor.execute(() -> {
            try { orchestrator.cancel(taskId, false); }
            catch (OrchestrationException error) {
                runOnUiThread(() -> exitRequested = false);
            }
        });
    }

    /**
     * BACK drives the control drawer, never the exit question (that is the Exit button's job):
     * closed drawer -> open it; open drawer -> close it. Ignored while the exit confirmation is showing or about to show. BACK
     * reaches this through the back callback, onBackPressed and dispatchKeyEvent, so a repeat within
     * a few milliseconds is the same press (without the guard open and close would cancel out).
     */
    private void handleSessionBack() {
        if (binding == null) return;
        long now = SystemClock.uptimeMillis();
        if (GameSessionBackPolicy.isDuplicate(lastBackHandledAtMs, now)) return;
        lastBackHandledAtMs = now;
        boolean drawerOpen = binding.gameSessionDrawerLayer.getVisibility() == View.VISIBLE
            && !drawerHiding;
        switch (GameSessionBackPolicy.decide(drawerOpen, controllerPageOpen,
            exitConfirmationVisible || exitConfirmationPending || exitRequested)) {
            case OPEN_DRAWER:
                showControlDrawer();
                break;
            case NAVIGATE_UP:
                setControllerPageOpen(false);
                break;
            case CLOSE_DRAWER:
                hideControlDrawer();
                break;
            case IGNORE:
            default:
                break;
        }
    }

    /**
     * Opens the drawer on the Performance page (the host's "show process manager" request, instead
     * of the task manager dialog). Returns false when the task manager cannot start yet, so the
     * default dialog path decides.
     */
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
        View content = TaskManagerPanel.inflateContent(this, binding.gameSessionProcessHost);
        TaskManagerPanel panel;
        try {
            panel = binding.gameSessionX11.createTaskManagerPanel(content, new TaskManagerPanel.Listener() {
                @Override public void onProcessCountChanged(int count) {
                    if (destroyed || binding == null) return;
                    binding.gameSessionProcessCount.setText(getString(com.termux.x11.R.string.processes) + ": " + count);
                }
                @Override public void onBringToFront() {
                    if (!destroyed && binding != null) hideControlDrawer();
                }
            });
        } catch (IllegalStateException notBound) {
            return false;
        }
        binding.gameSessionProcessHost.removeAllViews();
        binding.gameSessionProcessHost.addView(content);
        binding.gameSessionProcessCount.setText(getString(com.termux.x11.R.string.processes) + ": 0");
        processPanel = panel;
        // Games host switch: Expand/Collapse dashboard over the session's metric history.
        panel.setMetricsExpanded(metricsExpanded);
        panel.setMetricsSampler(metricsSampler);
        applyProcessPageLayout();
        return true;
    }

    /**
     * The one place that decides whether the 1s process refresh runs: only while the Performance
     * page is selected, the drawer is open (not sliding out) and the Activity is resumed. Called on
     * every state change that could flip that; starting and stopping are both idempotent.
     */
    private void updateProcessRefresh() {
        if (binding == null || destroyed) return;
        boolean drawerOpen = binding.gameSessionDrawerLayer.getVisibility() == View.VISIBLE
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

    /**
     * The one place that decides whether the Logs page is "shown" (its log is loaded on becoming
     * shown): only while that page is selected and the drawer is open (not sliding out). Called on
     * every state change that could flip that; the panel ignores a repeated state.
     */
    private void updateLogPage() {
        if (binding == null || destroyed) return;
        boolean drawerOpen = binding.gameSessionDrawerLayer.getVisibility() == View.VISIBLE
            && !drawerHiding;
        if (drawerOpen && selectedCategory == CATEGORY_LOGS) {
            ensureLogPanel().setShown(true);
        } else if (logPanel != null) {
            logPanel.setShown(false);
        }
    }

    private GameLogPanel ensureLogPanel() {
        if (logPanel == null) {
            logPanel = new GameLogPanel(this, logExecutor, binding.gameSessionLogList,
                binding.gameSessionLogStatus, binding.gameSessionLogRefresh);
            logPanel.setLogRef(currentTask == null ? null : currentTask.getLogRef());
        }
        return logPanel;
    }

    /**
     * The one place that decides whether metric sampling runs: only while the Activity is resumed and
     * the display is connected (game running); independent of the Performance page. Start and stop
     * are idempotent; stopping cancels the worker so nothing leaks while paused.
     */
    private void updateMetricsSampling() {
        if (metricsRecorder == null || destroyed) return;
        if (hostResumed && displayConnected) metricsRecorder.start();
        else metricsRecorder.stop();
    }

    /** Cancels the refresh timer if it runs (the panel and its views are kept for the next start). */
    private void stopProcessRefresh() {
        if (processPanel != null) processPanel.stop();
    }

    /** Sizes the Performance page for the current window and drawer form (re-run on rotation, in place). */
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
            (LinearLayout.LayoutParams) binding.gameSessionProcessPage.getLayoutParams();
        if (sizing.explicitHeight) {
            // A bottom sheet wraps its content, so the page needs a definite height.
            pageParams.height = dp(sizing.panelHeightDp + DrawerProcessPageSizing.FOOTER_DP);
            pageParams.weight = 0f;
        } else {
            pageParams.height = 0;
            pageParams.weight = 1f;
        }
        binding.gameSessionProcessPage.setLayoutParams(pageParams);
        placeProcessPageFooter(sizing.footerFixed);
        processPanel.applyLayout(sizing.layout, true);
    }

    /**
     * The Performance page always scrolls as one page: the host (dashboard + fully expanded list) sits in the
     * page's ScrollView. The footer stays fixed below it when the window is tall enough; otherwise it is
     * moved to the end of the scrolled content. Views are only re-parented (never rebuilt), so rotation
     * keeps the rows and the refresh timer.
     */
    private void placeProcessPageFooter(boolean footerFixed) {
        ViewGroup page = binding.gameSessionProcessPage;
        ViewGroup scrollContent = binding.gameSessionProcessScrollContent;
        View footer = binding.gameSessionProcessFooter;
        if (footerFixed) {
            moveTo(footer, page, page.getChildCount(), footerParams());
        } else {
            moveTo(footer, scrollContent, scrollContent.getChildCount(), footerParams());
        }
    }

    private LinearLayout.LayoutParams footerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        return params;
    }

    /** Re-parents {@code view} unless it already sits in {@code parent} (then only updates its params). */
    private static void moveTo(View view, ViewGroup parent, int index, ViewGroup.LayoutParams params) {
        if (view.getParent() == parent) {
            view.setLayoutParams(params);
            return;
        }
        ViewGroup old = (ViewGroup) view.getParent();
        if (old != null) old.removeView(view);
        parent.addView(view, Math.min(index, parent.getChildCount()), params);
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

    @Override protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (binding != null && controllerPanel != null)
            controllerPanel.onHostActivityResult(requestCode, resultCode, data);
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_CATEGORY, selectedCategory);
        outState.putBoolean(STATE_METRICS_EXPANDED,
            processPanel != null ? processPanel.isMetricsExpanded() : metricsExpanded);
        if (inputSelection != null) outState.putString(STATE_INPUT_SELECTION, inputSelection);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        hostResumed = true;
        if (binding != null) {
            binding.gameSessionX11.onHostResume();
            updateProcessRefresh();
        }
        updateMetricsSampling();
    }

    @Override protected void onPause() {
        hostResumed = false;
        updateMetricsSampling();
        stopProcessRefresh();
        if (binding != null) binding.gameSessionX11.onHostPause();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (binding != null) binding.gameSessionX11.onHostWindowFocusChanged(hasFocus);
    }

    @Override public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (binding != null) {
            binding.gameSessionX11.onHostConfigurationChanged(newConfig);
            applyOverlayLayout(newConfig);
            // The controller list only shows the layouts of the screen's orientation.
            if (controllerPanel != null) controllerPanel.render();
        }
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

        // Compact: full-width bottom sheet, content height capped by CappedHeightLinearLayout.
        // Otherwise: full-height column on the right edge.
        FrameLayout.LayoutParams drawerParams =
            (FrameLayout.LayoutParams) binding.gameSessionDrawer.getLayoutParams();
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
        binding.gameSessionDrawer.setCapHeight(compact);
        binding.gameSessionDrawer.setBackgroundResource(compact
            ? R.drawable.local_games_bg_overlay_sheet : R.drawable.local_games_bg_overlay);
        binding.gameSessionDrawer.setLayoutParams(drawerParams);
        binding.gameSessionDrawer.setOrientation(
            compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams hostParams =
            (LinearLayout.LayoutParams) binding.gameSessionNavHost.getLayoutParams();
        hostParams.width = compact ? ViewGroup.LayoutParams.MATCH_PARENT
            : getResources().getDimensionPixelSize(R.dimen.local_games_overlay_nav_width);
        hostParams.height = compact ? ViewGroup.LayoutParams.WRAP_CONTENT
            : ViewGroup.LayoutParams.MATCH_PARENT;
        binding.gameSessionNavHost.setLayoutParams(hostParams);

        LinearLayout nav = binding.gameSessionNav;
        ViewGroup targetParent = compact ? binding.gameSessionNavStrip : binding.gameSessionNavHost;
        if (nav.getParent() != targetParent) {
            ((ViewGroup) nav.getParent()).removeView(nav);
            targetParent.addView(nav, new FrameLayout.LayoutParams(
                compact ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                compact ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT));
        }
        binding.gameSessionNavStrip.setVisibility(compact ? View.VISIBLE : View.GONE);
        nav.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (View button : new View[] {
            binding.gameSessionCategoryOperation, binding.gameSessionCategoryPerformance,
            binding.gameSessionCategorySettings, binding.gameSessionCategoryKeyboard,
            binding.gameSessionCategoryLogs, binding.gameSessionExit}) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
            params.width = compact ? ViewGroup.LayoutParams.WRAP_CONTENT
                : ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = itemHeight;
            params.setMarginEnd(compact ? dp(4) : 0);
            button.setLayoutParams(params);
        }
        binding.gameSessionNavSpacer.setVisibility(compact ? View.GONE : View.VISIBLE);
        // Compact: the nav strip scrolls, so its last item (Exit) can sit out of view; Exit lives in
        // the panel header next to the close button instead. The side nav keeps its own Exit.
        binding.gameSessionExit.setVisibility(compact ? View.GONE : View.VISIBLE);
        binding.gameSessionExitCompact.setVisibility(compact ? View.VISIBLE : View.GONE);

        LinearLayout.LayoutParams contentParams =
            (LinearLayout.LayoutParams) binding.gameSessionContent.getLayoutParams();
        contentParams.width = compact ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
        contentParams.height = compact ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
        contentParams.weight = 1f;
        contentParams.setMarginStart(compact ? 0 : gap);
        contentParams.topMargin = compact ? gap : 0;
        binding.gameSessionContent.setLayoutParams(contentParams);
        // Rotation with the Performance page open: keep it and re-flow it for the new form.
        applyProcessPageLayout();
    }

    @Override protected void onDestroy() {
        destroyed = true;
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
        if (subscription != null) subscription.close();
        if (binding != null) binding.gameSessionX11.unbind();
        ioExecutor.shutdownNow();
        logExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
