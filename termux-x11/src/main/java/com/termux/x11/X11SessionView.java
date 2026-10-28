package com.termux.x11;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.termux.x11.session.DialogPalette;
import com.termux.x11.controller.winhandler.ProcessInfo;

import java.util.ArrayList;
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
        /**
         * The process manager was requested. Return true after showing it inside the host UI (via
         * {@link #createTaskManagerPanel}); false keeps the default task manager dialog.
         */
        default boolean onProcessManagerRequested() { return false; }
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

    /**
     * Id, display name and screen orientation of one on-screen controller layout; ids are the stable
     * profile ids.
     */
    public static final class InputProfile {
        private final int id;
        private final String name;
        private final boolean portrait;

        InputProfile(int id, @NonNull String name, boolean portrait) {
            this.id = id;
            this.name = name;
            this.portrait = portrait;
        }

        public int getId() { return id; }
        @NonNull public String getName() { return name; }
        /** True for a portrait layout, false for a landscape one (the default of older layouts). */
        public boolean isPortrait() { return portrait; }
    }

    @Nullable private AppCompatActivity activity;
    @Nullable private LorieViewRuntimeController runtime;
    @Nullable private HostAdapter hostAdapter;
    @Nullable private DialogPalette.Provider dialogPaletteProvider;
    private boolean immersive = false;
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

    /**
     * Lets the embedding product style the task manager dialogs. Without a provider the dialogs
     * keep the stock termux-x11 look.
     */
    public void setDialogPaletteProvider(@Nullable DialogPalette.Provider provider) {
        this.dialogPaletteProvider = provider;
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
        runtime.setImmersive(immersive);
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

    /**
     * Runs the host window immersive while it has focus: status and navigation bars hidden (a swipe
     * from the screen edge shows them transiently) and the content laid out under the display cutout.
     * Off by default; takes effect with the next window focus change.
     */
    public void setImmersive(boolean immersive) {
        this.immersive = immersive;
        if (runtime != null) runtime.setImmersive(immersive);
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

    /**
     * Creates the embeddable task manager over a view inflated with
     * {@link com.termux.x11.session.TaskManagerPanel#inflateContent}; the caller owns
     * start()/stop() and must stop it when leaving the page.
     */
    @NonNull
    public com.termux.x11.session.TaskManagerPanel createTaskManagerPanel(
        @NonNull View panelRoot,
        @NonNull com.termux.x11.session.TaskManagerPanel.Listener panelListener) {
        return requireRuntime().createTaskManagerPanel(panelRoot, panelListener);
    }

    /**
     * Cumulative number of frames the X11/Lorie renderer has presented (-1 when unavailable). A
     * once-per-second difference is the presented frame rate of the X11 surface.
     */
    public static long getRenderedFrameCount() {
        return LorieView.renderedFrameCount();
    }

    public boolean applyInputProfile(@NonNull String profileId) {
        return requireRuntime().applyGameInputProfile(profileId);
    }

    /**
     * Forwards an Activity result of the controller editor. Returns true when the result belonged to
     * the editor and the profile list / overlay were refreshed.
     */
    public boolean onHostActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        return runtime != null && runtime.onActivityResult(requestCode, resultCode, data);
    }

    /** Shows or hides the on-screen controller overlay while keeping the active profile. */
    public void setInputControlsVisible(boolean visible) {
        requireRuntime().setInputControlsVisible(visible);
    }

    public boolean isInputControlsVisible() {
        return runtime != null && runtime.isInputControlsVisible();
    }

    /** Reloads and returns the selectable on-screen controller profiles (id + name). */
    @NonNull
    public List<InputProfile> getInputControlsProfiles() {
        List<InputProfile> result = new ArrayList<>();
        if (runtime == null) return result;
        for (com.termux.x11.controller.inputcontrols.ControlsProfile profile : runtime.getInputControlsProfiles()) {
            String name = profile.getName();
            result.add(new InputProfile(profile.id, name == null ? "" : name,
                profile.getOrientation() == com.termux.x11.controller.inputcontrols.ControlsOrientation.PORTRAIT));
        }
        return result;
    }

    /** Id of the profile shown by the overlay, or 0 when the overlay has no active profile. */
    public int getActiveInputProfileId() {
        return runtime == null ? 0 : runtime.getActiveInputControlsProfileId();
    }

    /** How touches are interpreted while a controller profile is active. */
    public enum PointerMode {
        /** Touches move a pointer like a touchpad. */
        TOUCHPAD,
        /** Touches act directly where the screen is touched. */
        TOUCH_SCREEN,
        /** The pointer is captured, for games that need relative mouse movement. */
        LOCKED_CURSOR
    }

    /** Current pointer mode (touch screen, the default, while the session is not bound). */
    @NonNull
    public PointerMode getPointerMode() {
        if (runtime == null) return PointerMode.TOUCH_SCREEN;
        switch (runtime.getTouchMode()) {
            case TRACK_PAD: return PointerMode.TOUCHPAD;
            case LOCKED_CURSOR: return PointerMode.LOCKED_CURSOR;
            default: return PointerMode.TOUCH_SCREEN;
        }
    }

    /** Applies a pointer mode; false (nothing changed) while no controller profile is active. */
    public boolean setPointerMode(@NonNull PointerMode mode) {
        com.termux.x11.controller.widget.TouchpadView.TouchMode touchMode;
        switch (mode) {
            case TOUCHPAD: touchMode = com.termux.x11.controller.widget.TouchpadView.TouchMode.TRACK_PAD; break;
            case LOCKED_CURSOR: touchMode = com.termux.x11.controller.widget.TouchpadView.TouchMode.LOCKED_CURSOR; break;
            default: touchMode = com.termux.x11.controller.widget.TouchpadView.TouchMode.TOUCH_SCREEN; break;
        }
        return requireRuntime().applyTouchMode(touchMode);
    }

    /** The global X11 touch mode value: "1" trackpad, "2" simulated touch screen, "3" direct touch. */
    @NonNull
    public String getTouchModeValue() {
        return getSessionPreferences().getString("touchMode", "1");
    }

    /** Stores a touch mode value (see {@link #getTouchModeValue()}) and applies it to the live session. */
    public void setTouchModeValue(@NonNull String value) {
        getSessionPreferences().edit().putString("touchMode", value).commit();
        if (runtime != null) runtime.applyX11PreferenceChange("touchMode");
    }

    /** Whether the active profile's touchscreen controls are shown (true while not bound). */
    public boolean isShowTouchscreenControls() {
        return runtime == null || runtime.isShowTouchscreenControls();
    }

    /** Shows or hides the active profile's touchscreen controls; false while no profile is active. */
    public boolean setShowTouchscreenControls(boolean show) {
        return requireRuntime().applyShowTouchscreenControls(show);
    }

    /** Opens the controller editor; the result must be forwarded to {@link #onHostActivityResult}. */
    public void openInputControlsEditor() {
        requireRuntime().openInputControlsEditor();
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
        @Override public boolean showEmbeddedProcessManager() { return listener.onProcessManagerRequested(); }
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
        @Nullable @Override public DialogPalette getDialogPalette() {
            DialogPalette.Provider provider = dialogPaletteProvider;
            return provider == null ? null : provider.getDialogPalette();
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
