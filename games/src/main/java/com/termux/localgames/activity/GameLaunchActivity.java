package com.termux.localgames.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.localgames.R;
import com.termux.localgames.databinding.ActivityGameLaunchBinding;
import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.domain.LaunchTaskState;
import com.termux.localgames.runtime.OrchestrationException;
import com.termux.localgames.service.PersistentLocalGameOrchestrator;
import com.termux.localgames.runtime.Subscription;
import com.termux.localgames.runtime.TaskObserver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Task-id driven launch status page; it never owns the Termux runner process. */
public final class GameLaunchActivity extends AppCompatActivity {

    public static final String EXTRA_GAME_ID = "com.termux.localgames.extra.LAUNCH_GAME_ID";
    private static final String STATE_TASK_ID = "game_launch.task_id";
    private static final String STATE_SESSION_OPENED = "game_launch.session_opened";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesLaunchActivityIo"));
    private ActivityGameLaunchBinding binding;
    private PersistentLocalGameOrchestrator orchestrator;
    private Subscription subscription;
    private String gameId;
    private String taskId;
    private boolean destroyed;
    private boolean sessionOpened;

    @NonNull
    public static Intent createTaskIntent(@NonNull Context context, @NonNull String gameId,
                                          @NonNull String taskId) {
        return new Intent(context, GameLaunchActivity.class)
            .putExtra(EXTRA_GAME_ID, gameId)
            .putExtra(com.termux.localgames.api.LaunchTasks.EXTRA_TASK_ID, taskId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGameLaunchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        orchestrator = new PersistentLocalGameOrchestrator(this);
        gameId = getIntent().getStringExtra(EXTRA_GAME_ID);
        taskId = savedInstanceState == null
            ? getIntent().getStringExtra(com.termux.localgames.api.LaunchTasks.EXTRA_TASK_ID)
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

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_TASK_ID, taskId);
        outState.putBoolean(STATE_SESSION_OPENED, sessionOpened);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (subscription != null) subscription.close();
        ioExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }

    private void createOrReuseTask() {
        setLoading(true);
        ioExecutor.execute(() -> {
            try {
                String created = orchestrator.launch(gameId);
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
        if (sessionOpened && task.getState().isTerminal()) {
            // The session owns its exit confirmation and final navigation. Do not let the
            // background launch monitor clear it before the dialog can be presented.
            return;
        }
        LaunchPresentation.Phase phase = LaunchPresentation.phaseFor(task.getStage());
        binding.gameLaunchState.setText(task.getState() == LaunchTaskState.CANCELLED
            ? R.string.local_game_launch_phase_cancelled : phaseTitle(phase));
        binding.gameLaunchStage.setText(currentOperation(task.getStage()));
        binding.gameLaunchProgress.setProgressCompat(task.getProgress(), true);
        binding.gameLaunchProgressValue.setText(task.getProgress() + "%");
        boolean terminal = task.getState().isTerminal();
        boolean sessionReady = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !terminal &&
            (task.getStage() == com.termux.localgames.domain.LaunchStage.WAITING_FIRST_FRAME ||
                task.getStage() == com.termux.localgames.domain.LaunchStage.RUNNING);
        if (sessionReady && !sessionOpened) openSession();
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
    }

    private void showFailure(@Nullable String error) {
        runOnUiThread(() -> {
            if (destroyed || binding == null) return;
            setLoading(false);
            binding.gameLaunchState.setText(R.string.local_game_launch_error_title);
            binding.gameLaunchStage.setText(TextUtils.isEmpty(error)
                ? "launch_unknown" : error);
        });
    }
}
