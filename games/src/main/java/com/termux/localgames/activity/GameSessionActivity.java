package com.termux.localgames.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Build;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.termux.localgames.R;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.LaunchTasks;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.LaunchSpecCodec;
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

/** Game session shell for one persisted launch task; process ownership remains in the Service. */
public final class GameSessionActivity extends AppCompatActivity {

    private static final String STATE_CATEGORY = "session-control-category";
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
    private LaunchTask currentTask;
    private int selectedCategory = CATEGORY_OPERATION;
    private AudioManager audioManager;

    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull String taskId) {
        return new Intent(context, GameSessionActivity.class)
            .putExtra(LaunchTasks.EXTRA_TASK_ID, taskId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleSessionBack(); }
        });
        applyFrozenInputProfile(frozenSpec);
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
        if (!binding.gameSessionX11.applyInputProfile(spec.getInputProfileId()))
            binding.gameSessionInputWarning.setVisibility(View.VISIBLE);
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
        new MaterialAlertDialogBuilder(this)
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
            ? R.color.local_games_on_surface : R.color.local_games_on_surface_secondary));
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
        if (binding.gameSessionDrawerLayer.getVisibility() == View.VISIBLE) return;
        binding.gameSessionMenu.setVisibility(View.GONE);
        binding.gameSessionDrawerLayer.setVisibility(View.VISIBLE);
        binding.gameSessionDrawer.setTranslationX(binding.gameSessionDrawer.getWidth() > 0
            ? binding.gameSessionDrawer.getWidth() : dp(460));
        binding.gameSessionDrawer.animate().translationX(0).setDuration(180).start();
    }

    private void hideControlDrawer() {
        hideControlDrawer(null);
    }

    private void hideControlDrawer(@Nullable Runnable afterHidden) {
        if (binding == null ||
            binding.gameSessionDrawerLayer.getVisibility() != View.VISIBLE) {
            if (afterHidden != null) afterHidden.run();
            return;
        }
        binding.gameSessionDrawer.animate()
            .translationX(binding.gameSessionDrawer.getWidth() > 0
                ? binding.gameSessionDrawer.getWidth() : dp(460))
            .setDuration(160)
            .withEndAction(() -> {
                if (binding != null) {
                    binding.gameSessionDrawerLayer.setVisibility(View.GONE);
                    binding.gameSessionMenu.setVisibility(View.VISIBLE);
                    if (afterHidden != null) afterHidden.run();
                }
            })
            .start();
    }

    private void showRuntimeLog() {
        LaunchTask task = currentTask;
        String message = task == null || task.getLogRef().isEmpty()
            ? getString(R.string.local_game_session_log_pending)
            : task.getLogRef();
        new MaterialAlertDialogBuilder(this)
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

    private void handleSessionBack() {
        if (binding != null &&
            binding.gameSessionDrawerLayer.getVisibility() == View.VISIBLE) {
            hideControlDrawer();
        } else {
            showControlDrawer();
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

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_CATEGORY, selectedCategory);
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
        if (binding != null) binding.gameSessionX11.onHostConfigurationChanged(newConfig);
    }

    @Override protected void onDestroy() {
        destroyed = true;
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
