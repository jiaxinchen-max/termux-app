package com.termux.localgames.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.termux.localgames.R;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.LaunchTasks;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.LaunchSpecCodec;
import com.termux.localgames.data.OrientationPreference;
import com.termux.localgames.databinding.ActivityGameSessionBinding;
import com.termux.localgames.domain.LaunchSpec;
import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.runtime.OrchestrationException;
import com.termux.localgames.runtime.Subscription;
import com.termux.localgames.runtime.TaskObserver;
import com.termux.localgames.service.PersistentLocalGameOrchestrator;
import com.termux.x11.X11SessionView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Game session shell for one persisted launch task; process ownership remains in the Service. */
public final class GameSessionActivity extends AppCompatActivity {

    private static final String STATE_CATEGORY = "session-control-category";
    private static final String STATE_INPUT_SELECTION = "session-input-selection";
    private static final int CATEGORY_OPERATION = 0;
    private static final int CATEGORY_PERFORMANCE = 1;
    private static final int CATEGORY_SETTINGS = 2;
    private static final int CATEGORY_KEYBOARD = 3;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesSessionIo"));
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
    private LaunchTask currentTask;
    private int selectedCategory = CATEGORY_OPERATION;
    private AudioManager audioManager;
    private GameVirtualControllerPanel virtualControllerPanel;
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
        binding = ActivityGameSessionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        orchestrator = new PersistentLocalGameOrchestrator(this);
        binding.gameSessionX11.setDialogPaletteProvider(() -> GameDialogPalettes.frosted(this));
        binding.gameSessionX11.bind(this, frozenSpec.getResolution(), new X11SessionView.Listener() {
            @Override public void onConnectionChanged(boolean connected) {
                reportConnection(connected);
            }
            @Override public void onFirstFramePresented() { reportFirstFrame(); }
            @Override public void onControlCenterRequested() { showControlDrawer(); }
            @Override public void onExitRequested() { requestSafeExitConfirmation(); }
        });
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
        setUpVirtualControllerRow();
        observeTask();
    }

    private void configureControlCenter() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        binding.gameSessionKeyboard.setOnClickListener(view -> binding.gameSessionX11.toggleKeyboard());
        binding.gameSessionInput.setOnClickListener(view -> binding.gameSessionX11.showInputControls());
        binding.gameSessionMenu.setOnClickListener(view -> showControlDrawer());
        binding.gameSessionCloseDrawer.setOnClickListener(view -> hideControlDrawer());
        binding.gameSessionDrawerLayer.setOnClickListener(view -> hideControlDrawer());
        binding.gameSessionDrawer.setOnClickListener(view -> { });
        binding.gameSessionProcesses.setOnClickListener(view -> binding.gameSessionX11.showProcessManager());
        binding.gameSessionProcessOpen.setOnClickListener(view -> binding.gameSessionX11.showProcessManager());
        binding.gameSessionProcessRefresh.setOnClickListener(view -> refreshProcessSummary());
        binding.gameSessionLogs.setOnClickListener(view -> showRuntimeLog());
        binding.gameSessionCategoryOperation.setOnClickListener(view -> selectCategory(CATEGORY_OPERATION));
        binding.gameSessionCategoryPerformance.setOnClickListener(view -> selectCategory(CATEGORY_PERFORMANCE));
        binding.gameSessionCategorySettings.setOnClickListener(view -> selectCategory(CATEGORY_SETTINGS));
        binding.gameSessionCategoryKeyboard.setOnClickListener(view -> selectCategory(CATEGORY_KEYBOARD));
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

    /** Adds the "Virtual controller" row above the legacy Input row of the Operation panel. */
    private void setUpVirtualControllerRow() {
        LinearLayout host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        int index = binding.gameSessionPanelOperation.indexOfChild(binding.gameSessionInput);
        binding.gameSessionPanelOperation.addView(host, Math.max(0, index), params);
        virtualControllerPanel = new GameVirtualControllerPanel(this, binding.gameSessionX11, host,
            inputSelection, this::persistInputSelection);
        virtualControllerPanel.render();
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
                    if (!destroyed) Toast.makeText(this,
                        R.string.local_game_virtual_controller_save_failed, Toast.LENGTH_LONG).show();
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
        binding.gameSessionPanelOperation.setVisibility(
            category == CATEGORY_OPERATION ? View.VISIBLE : View.GONE);
        binding.gameSessionPanelPerformance.setVisibility(
            category == CATEGORY_PERFORMANCE ? View.VISIBLE : View.GONE);
        binding.gameSessionPanelSettings.setVisibility(
            category == CATEGORY_SETTINGS ? View.VISIBLE : View.GONE);
        binding.gameSessionPanelKeyboard.setVisibility(
            category == CATEGORY_KEYBOARD ? View.VISIBLE : View.GONE);
        int title;
        switch (category) {
            case CATEGORY_PERFORMANCE:
                title = R.string.local_game_session_performance_title;
                refreshProcessSummary();
                break;
            case CATEGORY_SETTINGS:
                title = R.string.local_game_session_settings_title;
                break;
            case CATEGORY_KEYBOARD:
                title = R.string.local_game_session_keyboard_title;
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
    }

    private void setCategorySelected(@NonNull MaterialButton button, boolean selected) {
        button.setSelected(selected);
        button.setBackgroundResource(selected
            ? R.drawable.local_games_bg_shortcut : android.R.color.transparent);
        button.setTextColor(ContextCompat.getColor(this, selected
            ? R.color.local_games_primary_text : R.color.local_games_on_surface_secondary));
    }

    private void refreshProcessSummary() {
        binding.gameSessionProcessSummary.setText(
            R.string.local_game_session_process_summary_pending);
        X11SessionView sessionView = binding.gameSessionX11;
        ioExecutor.execute(() -> {
            X11SessionView.ProcessSummary summary = sessionView.collectProcessSummary();
            int count = summary.getProcessCount();
            long totalMemory = summary.getMemoryBytes();
            runOnUiThread(() -> {
                if (destroyed || binding == null) return;
                if (count == 0) {
                    binding.gameSessionProcessSummary.setText(
                        R.string.local_game_session_process_summary_empty);
                } else {
                    binding.gameSessionProcessSummary.setText(getString(
                        R.string.local_game_session_process_summary,
                        count, Formatter.formatFileSize(this, totalMemory)));
                }
            });
        });
    }

    private void showControlDrawer() {
        boolean layerVisible = binding.gameSessionDrawerLayer.getVisibility() == View.VISIBLE;
        if (layerVisible && !drawerHiding) return;
        if (virtualControllerPanel != null) virtualControllerPanel.render();
        if (layerVisible) {
            // Re-opened while sliding out: turn around from where it is instead of restarting.
            drawerHiding = false;
            binding.gameSessionDrawer.animate().cancel();
            binding.gameSessionDrawer.animate().translationX(0).translationY(0).setDuration(180).start();
            return;
        }
        cancelDrawerAnimation();
        binding.gameSessionMenu.setVisibility(View.GONE);
        binding.gameSessionDrawerLayer.setVisibility(View.VISIBLE);
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

    private void showRuntimeLog() {
        LaunchTask task = currentTask;
        String message = task == null || task.getLogRef().isEmpty()
            ? getString(R.string.local_game_session_log_pending)
            : task.getLogRef();
        new FrostedDialogBuilder(this)
            .setTitle(R.string.local_game_session_logs)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
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
     * closed drawer -> open it; open drawer inside a sub-level -> one level up; open drawer at its
     * top level -> close it. Ignored while the exit confirmation is showing or about to show. BACK
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
        switch (GameSessionBackPolicy.decide(drawerOpen, drawerHasSubLevel(),
            exitConfirmationVisible || exitConfirmationPending || exitRequested)) {
            case OPEN_DRAWER:
                showControlDrawer();
                break;
            case NAVIGATE_UP:
                navigateDrawerUp();
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
     * The drawer is flat today: Operation/Performance/Settings/Keyboard are peer tabs of an
     * always-visible navigation, and everything they open (process manager, input controls, runtime
     * log, controller profile picker) is a dialog that handles BACK itself. When a panel gains a real
     * sub-level, report it here and step out of it in {@link #navigateDrawerUp()}.
     */
    private boolean drawerHasSubLevel() {
        return false;
    }

    private void navigateDrawerUp() {
        // No sub-level exists yet, so GameSessionBackPolicy never asks for this.
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
        if (binding != null && virtualControllerPanel != null)
            virtualControllerPanel.onHostActivityResult(requestCode, resultCode, data);
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_CATEGORY, selectedCategory);
        if (inputSelection != null) outState.putString(STATE_INPUT_SELECTION, inputSelection);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        if (binding != null) binding.gameSessionX11.onHostResume();
    }

    @Override protected void onPause() {
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
            binding.gameSessionExit}) {
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
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (virtualControllerPanel != null) virtualControllerPanel.dismiss();
        if (subscription != null) subscription.close();
        if (binding != null) binding.gameSessionX11.unbind();
        ioExecutor.shutdownNow();
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
