package com.termux.x11;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.termux.x11.controller.winhandler.ProcessInfo;

import java.util.List;

/**
 * Standalone X11 session widget for non-terminal products.
 *
 * <p>The widget owns the legacy runtime adapter internally and exposes only session-level
 * operations. Its host Activity does not implement terminal/X11 integration callbacks.</p>
 */
public final class X11SessionView extends FrameLayout {
    public static final String PREFERENCE_NAMESPACE = "games_x11_session_preferences";

    public interface Listener {
        default void onConnectionChanged(boolean connected) { }
        default void onFirstFramePresented() { }
        default void onControlCenterRequested() { }
        default void onExitRequested() { }
    }

    public static final class ProcessSummary {
        private final int processCount;
        private final long memoryBytes;

        ProcessSummary(int processCount, long memoryBytes) {
            this.processCount = processCount;
            this.memoryBytes = memoryBytes;
        }

        public int getProcessCount() { return processCount; }
        public long getMemoryBytes() { return memoryBytes; }
    }

    @Nullable private AppCompatActivity activity;
    @Nullable private LorieViewRuntimeController runtime;
    @Nullable private HostAdapter hostAdapter;
    @NonNull private Listener listener = new Listener() { };
    private TermuxScreenView displayView;

    public X11SessionView(@NonNull Context context) {
        super(context);
        initialize(context);
    }

    public X11SessionView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initialize(context);
    }

    public X11SessionView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize(context);
    }

    private void initialize(@NonNull Context context) {
        displayView = new TermuxScreenView(context);
        addView(displayView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void bind(@NonNull AppCompatActivity activity,
                     @NonNull String displayResolution,
                     @Nullable Listener listener) {
        applyDisplayResolution(displayResolution);
        if (runtime != null) {
            if (this.activity != activity)
                throw new IllegalStateException("X11SessionView is already bound to another Activity");
            this.listener = listener == null ? new Listener() { } : listener;
            runtime.applyX11PreferenceChange("displayResolutionCustom");
            return;
        }
        this.activity = activity;
        this.listener = listener == null ? new Listener() { } : listener;
        hostAdapter = new HostAdapter();
        runtime = new LorieViewRuntimeController(hostAdapter, PREFERENCE_NAMESPACE);
        runtime.setX11ActivityIntegration(hostAdapter);
        runtime.setManageHostOrientation(false);
        runtime.setTerminalToolbarEnabled(false);
        runtime.attachTermuxScreenView(displayView);
        runtime.setX11ConnectionStateListener(this.listener::onConnectionChanged);
        runtime.setX11FirstFrameListener(this.listener::onFirstFramePresented);
    }

    private void applyDisplayResolution(@NonNull String resolution) {
        String[] dimensions = resolution.split("x", -1);
        if (dimensions.length != 2)
            throw new IllegalArgumentException("Invalid X11 display resolution");
        try {
            int width = Integer.parseInt(dimensions[0]);
            int height = Integer.parseInt(dimensions[1]);
            if (width <= 0 || height <= 0 || width > Short.MAX_VALUE || height > Short.MAX_VALUE)
                throw new IllegalArgumentException("Invalid X11 display resolution");
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid X11 display resolution", error);
        }

        boolean stored = getSessionPreferences().edit()
            .putString("displayResolutionMode", "custom")
            .putString("displayResolutionCustom", resolution)
            .putBoolean("displayStretch", false)
            .putBoolean("adjustResolution", false)
            .commit();
        if (!stored) throw new IllegalStateException("Cannot store X11 display resolution");
    }

    public void unbind() {
        if (runtime != null) runtime.destroy();
        runtime = null;
        hostAdapter = null;
        activity = null;
        listener = new Listener() { };
    }

    public void onHostResume() {
        if (runtime != null) runtime.onResume();
    }

    public void onHostPause() {
        if (runtime != null) runtime.onPause();
    }

    public void onHostWindowFocusChanged(boolean hasFocus) {
        if (runtime != null) runtime.onWindowFocusChanged(hasFocus);
    }

    public void onHostConfigurationChanged(@NonNull Configuration configuration) {
        if (runtime != null) runtime.onConfigurationChanged(configuration);
    }

    public void toggleKeyboard() {
        requireRuntime().toggleKeyboardVisibility();
    }

    public void showInputControls() {
        requireRuntime().showInputControlsDialog();
    }

    public void showProcessManager() {
        requireRuntime().showProcessManagerDialog();
    }

    public boolean applyInputProfile(@NonNull String profileId) {
        return requireRuntime().applyGameInputProfile(profileId);
    }

    @NonNull
    public ProcessSummary collectProcessSummary() {
        List<ProcessInfo> processes = ProcessInfo.collectFromProc();
        if (processes == null) return new ProcessSummary(0, 0);
        long memoryBytes = 0;
        for (ProcessInfo process : processes) memoryBytes += process.memoryUsage;
        return new ProcessSummary(processes.size(), memoryBytes);
    }

    public boolean isMouseHelperEnabled() {
        return getSessionPreferences().getBoolean("showMouseHelper", false);
    }

    public void setMouseHelperEnabled(boolean enabled) {
        getSessionPreferences().edit().putBoolean("showMouseHelper", enabled).apply();
        requireRuntime().applyX11PreferenceChange("showMouseHelper");
    }

    public int getTouchSensitivity() {
        return getSessionPreferences().getInt("touch_sensitivity", 1);
    }

    public void setTouchSensitivity(int sensitivity) {
        int normalized = Math.max(1, Math.min(20, sensitivity));
        getSessionPreferences().edit()
            .putInt("touch_sensitivity", normalized)
            .apply();
        requireRuntime().setTouchSensitivity(normalized);
    }

    @NonNull
    private android.content.SharedPreferences getSessionPreferences() {
        return getContext().getSharedPreferences(PREFERENCE_NAMESPACE, Context.MODE_PRIVATE);
    }

    @NonNull
    private LorieViewRuntimeController requireRuntime() {
        if (runtime == null) throw new IllegalStateException("X11SessionView is not bound");
        return runtime;
    }

    private final class HostAdapter implements LorieViewRuntimeApi.Host,
        LorieViewRuntimeApi.ActivityIntegration {

        @NonNull @Override public Activity getActivity() { return requireActivity(); }
        @NonNull @Override public FragmentManager getSupportFragmentManager() {
            return requireActivity().getSupportFragmentManager();
        }
        @Override public void openX11Preferences(boolean open) { }
        @Override public void requestX11Focus(boolean focused) {
            if (runtime != null) runtime.setX11FocusedChanged(focused);
        }
        @Override public void openSoftKeyboard() { toggleKeyboard(); }
        @Override public void showProcessManager() { X11SessionView.this.showProcessManager(); }
        @NonNull @Override public Prefs getX11Prefs() { return requireRuntime().getX11Prefs(); }
        @Nullable @Override public LorieViewRuntimeApi.ActivityIntegration getX11ActivityIntegration() {
            return this;
        }
        @Override public void openPreference(boolean open) { }
        @Override public void showInputControlsDialog() { X11SessionView.this.showInputControls(); }
        @Override public void installX11ServerBridge() { }
        @Override public void stopDesktop() { listener.onExitRequested(); }
        @Override public void onX11PreferenceChanged(String key) {
            if (runtime != null) runtime.applyX11PreferenceChange(key);
        }
        @Override public void onX11PreferenceSwitchChange(boolean isOpen) { }
        @Override public void releaseSlider(boolean open) {
            if (open) listener.onControlCenterRequested();
        }
        @Override public void onChangeOrientation(int landscape) { }
        @Override public void reInstallX11StartScript(Activity activity) { }
        @Override public void openSoftwareKeyboard() { toggleKeyboard(); }
        @Override public List<ProcessInfo> collectProcessorInfo(String tag) {
            return ProcessInfo.collectFromProc();
        }
        @Override public void setFloatBallMenu(boolean enabled, boolean global) { }
        @Override public void onExitApp() { listener.onExitRequested(); }
        @Override public void changePreference(String key) { onX11PreferenceChanged(key); }

        @NonNull
        private AppCompatActivity requireActivity() {
            if (activity == null) throw new IllegalStateException("X11SessionView is not bound");
            return activity;
        }
    }
}
