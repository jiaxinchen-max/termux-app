package com.termux.ui.wine.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.ui.R;
import com.termux.ui.activity.AppUiActivity;
import com.termux.ui.api.LocalGames;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.databinding.ActivityGameLaunchBinding;
import com.termux.ui.wine.data.FileGameRepository;
import com.termux.ui.wine.data.GameRepository;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.domain.LaunchStage;
import com.termux.ui.wine.domain.LaunchTask;
import com.termux.ui.wine.domain.LaunchTaskState;
import com.termux.ui.wine.runtime.OrchestrationException;
import com.termux.ui.wine.runtime.Subscription;
import com.termux.ui.wine.runtime.TaskObserver;
import com.termux.ui.wine.service.PersistentLocalGameOrchestrator;
import com.termux.ui.wine.runtime.WineDisplayPolicy;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.data.FileLaunchTaskRepository;
import com.termux.ui.activity.GameSessionOrientation;
import com.termux.ui.platform.X11SessionOverrides;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Task-id driven launch status page; it never owns the Termux runner process. */
public final class GameLaunchActivity extends AppUiActivity {

    public static final String EXTRA_GAME_ID = "com.termux.ui.extra.LAUNCH_GAME_ID";
    /** When true, open the bare Wine desktop for this game's container instead of running it. */
    public static final String EXTRA_DESKTOP = "com.termux.ui.extra.LAUNCH_DESKTOP";
    private static final String STATE_TASK_ID = "game_launch.task_id";
    private static final String STATE_SESSION_OPENED = "game_launch.session_opened";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesLaunchActivityIo"));
    private ActivityGameLaunchBinding binding;
    private PersistentLocalGameOrchestrator orchestrator;
    private GameRepository gameRepository;
    private Subscription subscription;
    private String gameId;
    private boolean desktop;
    private String taskId;
    private boolean destroyed;
    private boolean sessionOpened;
    private boolean nativeWindow;
    @Nullable private android.view.ViewTreeObserver.OnPreDrawListener windowMeasurement;
    @Nullable private Runnable measurementTimeout;

    @NonNull
    public static Intent createTaskIntent(@NonNull Context context, @NonNull String gameId,
                                          @NonNull String taskId) {
        return new Intent(context, GameLaunchActivity.class)
            .putExtra(EXTRA_GAME_ID, gameId)
            .putExtra(com.termux.ui.wine.api.LaunchTasks.EXTRA_TASK_ID, taskId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        gameId = getIntent().getStringExtra(EXTRA_GAME_ID);
        nativeWindow = savedInstanceState != null && savedInstanceState.getBoolean("native-window");
        super.onCreate(savedInstanceState);
        binding = ActivityGameLaunchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        fitContentWidth();
        orchestrator = new PersistentLocalGameOrchestrator(this);
        gameRepository = new FileGameRepository(new GameStoragePaths(getFilesDir()).getLibraryDirectory());
        gameId = getIntent().getStringExtra(EXTRA_GAME_ID);
        desktop = getIntent().getBooleanExtra(EXTRA_DESKTOP, false);
        taskId = savedInstanceState == null
            ? getIntent().getStringExtra(com.termux.ui.wine.api.LaunchTasks.EXTRA_TASK_ID)
            : savedInstanceState.getString(STATE_TASK_ID);
        sessionOpened = savedInstanceState != null &&
            savedInstanceState.getBoolean(STATE_SESSION_OPENED, false);
        binding.gameLaunchToolbar.setNavigationOnClickListener(view -> finish());
        if (TextUtils.isEmpty(gameId)) {
            Toast.makeText(this, R.string.local_game_detail_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (TextUtils.isEmpty(taskId)) createOrReuseTask();
        else observeTask();
    }

    /** Centers the status block and caps it at local_game_launch_content_max_width on wide
     *  windows; on narrow windows the side padding token applies. Re-evaluated on every layout. */
    private void fitContentWidth() {
        final int minSide = getResources().getDimensionPixelSize(R.dimen.local_game_launch_side_padding);
        final int maxWidth = getResources().getDimensionPixelSize(R.dimen.local_game_launch_content_max_width);
        binding.gameLaunchScroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                              oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int side = Math.max(minSide, (width - maxWidth) / 2);
            if (binding.gameLaunchContent.getPaddingLeft() != side
                || binding.gameLaunchContent.getPaddingRight() != side) {
                binding.gameLaunchContent.setPadding(side, binding.gameLaunchContent.getPaddingTop(),
                    side, binding.gameLaunchContent.getPaddingBottom());
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_TASK_ID, taskId);
        outState.putBoolean(STATE_SESSION_OPENED, sessionOpened);
        outState.putBoolean("native-window", nativeWindow);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        clearWindowMeasurement();
        if (subscription != null) subscription.close();
        ioExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }

    private void createOrReuseTask() {
        ioExecutor.execute(() -> {
            try {
                Optional<Game> game = gameRepository.find(gameId);
                boolean shared = false;
                if (game.isPresent()) {
                    android.net.Uri uri = android.net.Uri.parse(game.get().getRootUri());
                    if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                        java.io.File root = new java.io.File(uri.getPath()).getCanonicalFile();
                        java.io.File privateRoot = getFilesDir().getCanonicalFile();
                        shared = !root.equals(privateRoot) && !root.getPath().startsWith(privateRoot.getPath() + "/");
                    } else shared = "com.android.externalstorage.documents".equals(uri.getAuthority());
                }
                boolean needsStorage = shared;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (needsStorage) com.termux.ui.platform.ExternalStorageAccess.ensure(this, this::createOrReuseTaskReady);
                    else createOrReuseTaskReady();
                });
            } catch (IOException | RuntimeException error) { showFailure("game_root_not_posix_accessible"); }
        });
    }

    private void createOrReuseTaskReady() {
        setLoading(true);
        ioExecutor.execute(() -> {
            try {
                GameStoragePaths paths = new GameStoragePaths(getFilesDir());
                boolean active = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory())
                    .findActiveForGame(gameId).isPresent();
                com.termux.ui.wine.domain.RuntimeProfile profile = active ? null
                    : new FileRuntimeProfileRepository(paths.getProfilesDirectory()).find(gameId).orElse(null);
                boolean needsWindow = profile != null && WineDisplayPolicy.NATIVE.equals(profile.getResolution());
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (needsWindow) measureNativeWindow();
                    else submitLaunch(0, 0);
                });
            } catch (IOException | RuntimeException error) { showFailure(error.getMessage()); }
        });
    }

    @Override
    protected void applyAppOrientation() {
        if (nativeWindow && gameId != null) {
            setRequestedOrientation(GameSessionOrientation.requestedFor(
                new X11SessionOverrides(this).getGameOrientation(gameId)));
        } else super.applyAppOrientation();
    }

    private void measureNativeWindow() {
        clearWindowMeasurement();
        nativeWindow = true;
        applyAppOrientation();
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30
                ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.WindowInsetsControllerCompat insets =
            androidx.core.view.WindowCompat.getInsetsController(getWindow(), binding.getRoot());
        insets.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        insets.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        windowMeasurement = () -> {
            if (destroyed || binding == null) return true;
            View root = binding.getRoot();
            int width = root.getWidth(), height = root.getHeight();
            if (width <= 0 || height <= 0) return true;
            com.termux.ui.platform.OrientationPreference orientation = new X11SessionOverrides(this).getGameOrientation(gameId);
            boolean multiWindow = android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode();
            int current = getResources().getConfiguration().orientation;
            if (!multiWindow && (orientation == com.termux.ui.platform.OrientationPreference.HORIZONTAL
                    && current != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                || orientation == com.termux.ui.platform.OrientationPreference.VERTICAL
                    && current != android.content.res.Configuration.ORIENTATION_PORTRAIT)) return true;
            clearWindowMeasurement();
            submitLaunch(width, height);
            return true;
        };
        binding.getRoot().getViewTreeObserver().addOnPreDrawListener(windowMeasurement);
        measurementTimeout = () -> {
            clearWindowMeasurement();
            showFailure("window_size_required");
        };
        binding.getRoot().postDelayed(measurementTimeout, 5000);
        binding.getRoot().requestLayout();
    }

    private void clearWindowMeasurement() {
        if (binding == null) return;
        if (windowMeasurement != null && binding.getRoot().getViewTreeObserver().isAlive())
            binding.getRoot().getViewTreeObserver().removeOnPreDrawListener(windowMeasurement);
        if (measurementTimeout != null) binding.getRoot().removeCallbacks(measurementTimeout);
        windowMeasurement = null;
        measurementTimeout = null;
    }

    private void submitLaunch(int width, int height) {
        ioExecutor.execute(() -> {
            try {
                String created = orchestrator.launch(gameId, desktop, width, height);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    taskId = created;
                    observeTask();
                });
            } catch (OrchestrationException error) {
                showFailure(error.getErrorCode());
            }
        });
    }

    private void observeTask() {
        if (subscription != null) subscription.close();
        try {
            subscription = orchestrator.observe(taskId, new TaskObserver<LaunchTask>() {
                @Override
                public void onChanged(LaunchTask value) {
                    runOnUiThread(() -> {
                        if (!destroyed) render(value);
                    });
                }

                @Override
                public void onError(Throwable error) {
                    showFailure(error.getMessage());
                }
            });
        } catch (OrchestrationException error) {
            showFailure(error.getErrorCode());
        }
    }

    private void render(LaunchTask task) {
        setLoading(false);
        // Keep the status page current even after opening a session: Android can destroy
        // that session and restore this Activity. Rendering terminal status does not navigate
        // away from a live session or interfere with its exit confirmation.
        if (task.getState() == LaunchTaskState.FAILED) {
            renderFailure(task);
            return;
        }
        binding.gameLaunchAction.setVisibility(View.GONE);
        LaunchPresentation.Phase phase = LaunchPresentation.phaseFor(task.getStage());
        binding.gameLaunchState.setText(task.getState() == LaunchTaskState.CANCELLED
            ? R.string.local_game_launch_phase_cancelled : phaseTitle(phase));
        binding.gameLaunchStage.setText(currentOperation(task.getStage()));
        binding.gameLaunchProgress.setProgressCompat(task.getProgress(), true);
        binding.gameLaunchProgressValue.setText(task.getProgress() + "%");
        boolean sessionReady = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            com.termux.ui.wine.runtime.SessionOpenPolicy.shouldOpen(
                com.termux.ui.runtime.XServerBackendSelection.read(new GameStoragePaths(getFilesDir())),
                task.getStage(), task.getState());
        if (sessionReady && !sessionOpened) openSession();
    }

    private void renderFailure(LaunchTask task) {
        binding.gameLaunchState.setText(R.string.local_game_launch_error_title);
        binding.gameLaunchStage.setText(getString(R.string.local_game_launch_error,
            LaunchPresentation.code(task.getErrorCode()),
            task.isRecoverable() ? getString(R.string.local_game_launch_recoverable) : ""));
        binding.gameLaunchProgress.setProgressCompat(task.getProgress(), true);
        binding.gameLaunchProgressValue.setText(task.getProgress() + "%");
        configureAction(LaunchPresentation.recoveryFor(task.getErrorCode(), task.isRecoverable()));
    }

    private void configureAction(LaunchPresentation.RecoveryAction action) {
        if (binding == null) return;
        switch (action) {
            case REAUTHORIZE:
                binding.gameLaunchAction.setText(R.string.local_game_launch_repair_permission);
                binding.gameLaunchAction.setVisibility(View.VISIBLE);
                binding.gameLaunchAction.setOnClickListener(view -> reauthorize());
                break;
            case COMPONENTS:
                binding.gameLaunchAction.setText(R.string.local_game_launch_repair_components);
                binding.gameLaunchAction.setVisibility(View.VISIBLE);
                binding.gameLaunchAction.setOnClickListener(view ->
                    openComponents());
                break;
            case RETRY:
                binding.gameLaunchAction.setText(R.string.local_game_launch_retry);
                binding.gameLaunchAction.setVisibility(View.VISIBLE);
                binding.gameLaunchAction.setOnClickListener(view -> retryLaunch());
                break;
            default:
                binding.gameLaunchAction.setVisibility(View.GONE);
        }
    }

    private void openComponents() {
        ioExecutor.execute(() -> {
            try {
                com.termux.ui.wine.domain.RuntimeProfile profile =
                    new com.termux.ui.wine.data.FileRuntimeProfileRepository(
                        new GameStoragePaths(getFilesDir()).getProfilesDirectory())
                        .find(gameId).orElseThrow(() -> new IOException("runtime_profile_missing"));
                runOnUiThread(() -> {
                    if (!destroyed) startActivity(LocalGames.createCoreComponentsIntent(this,
                        profile.getRuntimeBackendType()));
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (!destroyed) Toast.makeText(this, R.string.local_game_runtime_load_failed,
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void reauthorize() {
        ioExecutor.execute(() -> {
            Optional<Game> found;
            try {
                found = gameRepository.find(gameId);
            } catch (IOException error) {
                found = Optional.empty();
            }
            Optional<Game> result = found;
            runOnUiThread(() -> {
                if (destroyed || !result.isPresent()) return;
                startActivity(LocalGames.createReauthorizeIntent(this, result.get().getRootUri()));
            });
        });
    }

    private void retryLaunch() {
        taskId = null;
        sessionOpened = false;
        createOrReuseTask();
    }

    private int phaseTitle(LaunchPresentation.Phase phase) {
        switch (phase) {
            case CHECK: return R.string.local_game_launch_phase_check;
            case PREPARE: return R.string.local_game_launch_phase_prepare;
            case START: return R.string.local_game_launch_phase_start;
            case RUNNING: return R.string.local_game_launch_phase_running;
            case STOPPING: return R.string.local_game_launch_phase_stopping;
            default: return R.string.local_game_launch_phase_complete;
        }
    }

    @NonNull
    private String currentOperation(@NonNull LaunchStage stage) {
        switch (stage) {
            case QUEUED:
                return getString(R.string.local_game_launch_operation_queued);
            case PRECHECK:
                return getString(R.string.local_game_launch_operation_precheck);
            case PREPARING_PREFIX:
                return getString(R.string.local_game_launch_operation_prefix);
            case STARTING_DISPLAY:
                return getString(R.string.local_game_launch_operation_display);
            case STARTING_AUDIO:
                return getString(R.string.local_game_launch_operation_audio);
            case STARTING_GAME:
                return getString(R.string.local_game_launch_operation_game);
            case WAITING_FIRST_FRAME:
                return getString(R.string.local_game_launch_operation_frame);
            case RUNNING:
                return getString(R.string.local_game_launch_operation_running);
            case CLEANING:
                return getString(R.string.local_game_launch_operation_cleaning);
            default:
                return getString(R.string.local_game_launch_operation_complete);
        }
    }

    private void openSession() {
        if (TextUtils.isEmpty(taskId)) return;
        sessionOpened = true;
        startActivity(GameSessionActivity.createIntent(this, taskId));
    }

    private void setLoading(boolean loading) {
        if (binding == null) return;
        binding.gameLaunchProgress.setIndeterminate(loading);
        binding.gameLaunchProgressValue.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        if (loading) binding.gameLaunchAction.setVisibility(View.GONE);
    }

    private void showFailure(@Nullable String error) {
        runOnUiThread(() -> {
            if (destroyed || binding == null) return;
            setLoading(false);
            binding.gameLaunchAction.setVisibility(View.GONE);
            binding.gameLaunchState.setText(R.string.local_game_launch_error_title);
            binding.gameLaunchStage.setText(TextUtils.isEmpty(error)
                ? "launch_unknown" : error);
        });
    }
}
