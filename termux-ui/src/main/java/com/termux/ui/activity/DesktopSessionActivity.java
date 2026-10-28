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
import com.termux.ui.platform.DesktopSessionOverrides;
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
    private DesktopSessionOverrides overrides;
    /** The global touch mode to put back when this session ends; null if it was not replaced. */
    private String globalTouchMode;
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
            @Override public void onFirstFramePresented() {
                firstFrame = true;
                showDesktop();
            }
            @Override public void onControlCenterRequested() { showMenu(); }
            @Override public void onExitRequested() { showMenu(); }
        });
        applyTouchMode();
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
        String touch = getString(R.string.desktop_session_touch_mode, touchModeLabel(
            overrides.getTouchMode(distro.getId())));
        String orientation = getString(R.string.desktop_session_orientation, orientationLabel(
            overrides.getOrientation(distro.getId())));
        new FrostedDialogBuilder(this)
            .setTitle(distro.getName())
            .setItems(new CharSequence[] {touch, orientation,
                getString(R.string.desktop_session_close),
                getString(R.string.desktop_session_stop)}, (dialog, which) -> {
                    if (which == 0) {
                        showTouchModeChoice();
                        return;
                    }
                    if (which == 1) {
                        showOrientationChoice();
                        return;
                    }
                    if (which == 3) {
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

    private static final String[] TOUCH_MODES = {null, DesktopSessionOverrides.TOUCH_TRACKPAD,
        DesktopSessionOverrides.TOUCH_SIMULATED, DesktopSessionOverrides.TOUCH_DIRECT};
    private static final OrientationPreference[] ORIENTATIONS = {null,
        OrientationPreference.HORIZONTAL, OrientationPreference.VERTICAL, OrientationPreference.AUTO};

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

    private void showTouchModeChoice() {
        CharSequence[] labels = new CharSequence[TOUCH_MODES.length];
        int checked = 0;
        String current = overrides.getTouchMode(distro.getId());
        for (int i = 0; i < labels.length; i++) {
            labels[i] = touchModeLabel(TOUCH_MODES[i]);
            if ((current == null ? TOUCH_MODES[i] == null : current.equals(TOUCH_MODES[i]))) checked = i;
        }
        new FrostedDialogBuilder(this)
            .setTitle(R.string.desktop_session_touch_mode_title)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                overrides.setTouchMode(distro.getId(), TOUCH_MODES[which]);
                applyTouchMode();
                dialog.dismiss();
            })
            .show();
    }

    private void showOrientationChoice() {
        CharSequence[] labels = new CharSequence[ORIENTATIONS.length];
        int checked = 0;
        OrientationPreference current = overrides.getOrientation(distro.getId());
        for (int i = 0; i < labels.length; i++) {
            labels[i] = orientationLabel(ORIENTATIONS[i]);
            if (current == ORIENTATIONS[i]) checked = i;
        }
        new FrostedDialogBuilder(this)
            .setTitle(R.string.desktop_session_orientation_title)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                overrides.setOrientation(distro.getId(), ORIENTATIONS[which]);
                applyOrientation(overrides.effectiveOrientation(this, distro.getId()));
                dialog.dismiss();
            })
            .show();
    }

    private void applyOrientation(OrientationPreference preference) {
        setRequestedOrientation(GameSessionOrientation.requestedFor(preference));
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
        if (binding != null) binding.desktopSessionX11.onHostResume();
    }

    @Override
    protected void onPause() {
        if (binding != null) binding.desktopSessionX11.onHostPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (binding != null) binding.desktopSessionX11.onHostWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(pollTick);
        if (overrides != null) restoreGlobalTouchMode();
        if (binding != null) binding.desktopSessionX11.unbind();
        ioExecutor.shutdown();
        binding = null;
        super.onDestroy();
    }
}
