package com.termux.x11;

import android.app.Activity;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.viewpager.widget.ViewPager;

import com.termux.x11.controller.core.DownloadProgressDialog;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.winhandler.ProcessInfo;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.utils.TermuxX11ExtraKeys;

import java.util.List;

public final class LorieViewRuntimeApi {
    private LorieViewRuntimeApi() {
    }

    @Nullable
    private static Host sRegisteredHost;

    /** Register a Host for preferences and other components that need it. */
    public static void registerHost(@NonNull Host host) {
        sRegisteredHost = host;
    }

    /** Clear the registered host (call from onDestroy). */
    public static void unregisterHost() {
        sRegisteredHost = null;
    }

    @Nullable
    public static Host getRegisteredHost() {
        return sRegisteredHost;
    }

    public interface ActivityIntegration {
        void onX11PreferenceSwitchChange(boolean isOpen);

        void releaseSlider(boolean open);

        void onChangeOrientation(int landscape);

        void reInstallX11StartScript(Activity activity);

        void stopDesktop();

        void openSoftwareKeyboard();

        void showProcessManager();

        void changePreference(String key);

        List<ProcessInfo> collectProcessorInfo(String tag);

        void setFloatBallMenu(boolean enableFloatBallMenu, boolean enableGlobalFloatBallMenu);

        void onExitApp();
    }

    public interface Host {
        @NonNull
        Activity getActivity();

        @NonNull
        FragmentManager getSupportFragmentManager();

        void openX11Preferences(boolean open);

        void requestX11Focus(boolean focused);

        void openSoftKeyboard();

        void showProcessManager();

        @NonNull
        Prefs getX11Prefs();

        @Nullable
        ActivityIntegration getX11ActivityIntegration();

        void openPreference(boolean open);

        void showInputControlsDialog();

        void installX11ServerBridge();

        void stopDesktop();

        void onX11PreferenceChanged(String key);
    }

    public interface DisplayConnectionListener {
        void onConnectionStateChanged(boolean connected);
    }

    public interface FirstFrameListener {
        void onFirstFramePresented();
    }

    public static final class DisplayController {
        @Nullable
        private Host mHost;
        @Nullable
        private TermuxScreenView mDisplayView;
        @Nullable
        private DisplayConnectionListener mConnectionStateListener;
        private boolean mConnected;

        public void attach(@NonNull Host host, @NonNull TermuxScreenView displayView) {
            detach();
            mHost = host;
            mDisplayView = displayView;
        }

        public void detach() {
            mDisplayView = null;
            mHost = null;
            mConnectionStateListener = null;
            mConnected = false;
        }

        public void setConnectionStateListener(@Nullable DisplayConnectionListener listener) {
            mConnectionStateListener = listener;
            if (listener != null)
                listener.onConnectionStateChanged(mConnected);
        }

        public void setConnected(boolean connected) {
            if (mConnected == connected)
                return;
            mConnected = connected;
            if (mConnectionStateListener != null)
                mConnectionStateListener.onConnectionStateChanged(connected);
        }

        public boolean isConnected() {
            return mConnected;
        }

        public boolean isAttached() {
            return mHost != null && mDisplayView != null;
        }

        @Nullable
        public Host getHost() {
            return mHost;
        }

        @Nullable
        public TermuxScreenView getDisplayView() {
            return mDisplayView;
        }

        @Nullable
        public LorieView getLorieView() {
            return mDisplayView != null ? mDisplayView.getLorieView() : null;
        }
    }

    public interface InputHost {
        String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";

        @NonNull
        Activity getActivity();

        @NonNull
        LorieView getLorieView();

        @NonNull
        DisplayController getX11DisplayController();

        void toggleKeyboardVisibility();

        void toggleExtraKeys();

        void openPreference(boolean open);

        void stopDesktop();

        void prepareToExit();

        void setExternalKeyboardConnected(boolean connected);
    }

    public interface LorieHost extends InputHost {
        @NonNull
        Prefs getX11Prefs();

        boolean shouldUseTermuxExtraKeysBarBehaviour();

        void unsetExtraKeysSpecialKeys();

        boolean shouldInterceptKeys();

        boolean handleKey(KeyEvent event);

        boolean hasWindowFocus();
    }

    public interface ExtraKeysHost extends InputHost {
        void toggleMouseAuxButtons();

        void toggleStylusAuxButtons();
    }

    public interface ToolbarHost extends ExtraKeysHost {
        @NonNull
        ViewPager getDisplayTerminalToolbarViewPager();

        void setTermuxX11ExtraKeys(@Nullable TermuxX11ExtraKeys extraKeys);

        @Nullable
        TermuxX11ExtraKeys getTermuxX11ExtraKeys();
    }

    public interface InputControlsHost extends Host {
        @NonNull
        DownloadProgressDialog getPreloaderDialog();
    }

    public interface WinHandlerHost extends Host {
        @Nullable
        InputControlsView getInputControlsView();

        @NonNull
        WinHandler getWinHandler();

        @Nullable
        List<ProcessInfo> getTermuxProcessorInfo(String tag);
    }
}
