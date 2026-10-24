package com.termux.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.ui.R;
import com.termux.ui.api.DesktopTasks;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.databinding.ActivityDesktopSessionBinding;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopSessionState;
import com.termux.ui.platform.OrientationPreference;
import com.termux.x11.X11SessionView;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows one KWin desktop on the X11 session view. The view is bound first so the lorie render
 * bridge is listening; only then is the desktop started and KWin's libtermux-render connects to
 * it.
 */
public final class DesktopSessionActivity extends AppCompatActivity {

    private static final String EXTRA_DISTRO_ID = "com.termux.ui.extra.DESKTOP_ID";
    private static final long POLL_INTERVAL_MS = 1000;
    /** The start script needs a moment to reset a stale status file; ignore it meanwhile. */
    private static final long START_GRACE_MS = 8000;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "DesktopSessionIo"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTick = this::poll;

    private ActivityDesktopSessionBinding binding;
    private DesktopTasks tasks;
    private DesktopDistro distro;
    private boolean destroyed;
    private boolean startRequested;
    private long startRequestedAtMs;
    private boolean everRunning;
    private boolean firstFrame;

    static Intent createIntent(Context context, String distroId) {
        return new Intent(context, DesktopSessionActivity.class).putExtra(EXTRA_DISTRO_ID, distroId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(GameSessionOrientation.requestedFor(OrientationPreference.load(this)));
        String id = getIntent().getStringExtra(EXTRA_DISTRO_ID);
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
            @Override public void onFirstFramePresented() {
                firstFrame = true;
                showDesktop();
            }
            @Override public void onControlCenterRequested() { showMenu(); }
            @Override public void onExitRequested() { showMenu(); }
        });
        binding.desktopSessionMenu.setOnClickListener(view -> showMenu());
        binding.desktopSessionCloseWaiting.setOnClickListener(view -> finish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { showMenu(); }
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

    private void showMenu() {
        new FrostedDialogBuilder(this)
            .setTitle(distro.getName())
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

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) binding.desktopSessionX11.onHostResume();
    }

    @Override
    protected void onPause() {
        if (binding != null) binding.desktopSessionX11.onHostPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(pollTick);
        if (binding != null) binding.desktopSessionX11.unbind();
        ioExecutor.shutdown();
        binding = null;
        super.onDestroy();
    }
}
