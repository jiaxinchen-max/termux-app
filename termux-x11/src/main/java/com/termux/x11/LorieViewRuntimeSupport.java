package com.termux.x11;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static com.termux.x11.CmdEntryPoint.ACTION_START;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.x11.controller.winhandler.TaskManagerDialog;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.KeyInterceptor;

import java.util.concurrent.Executors;

final class X11InputController {
    @NonNull
    private final TouchInputHandler mTouchInputHandler;

    X11InputController(@NonNull LorieViewRuntimeApi.InputHost host,
                       @NonNull LorieView lorieView,
                       int longPressDelay) {
        mTouchInputHandler = new TouchInputHandler(host, new InputEventSender(lorieView));
        mTouchInputHandler.setLongPressedDelay(longPressDelay);
    }

    boolean handleTouchEvent(@NonNull View rootView,
                             @NonNull View targetView,
                             @NonNull MotionEvent event) {
        return mTouchInputHandler.handleTouchEvent(rootView, targetView, event);
    }

    boolean sendKeyEvent(@NonNull KeyEvent event) {
        return mTouchInputHandler.sendKeyEvent(event);
    }

    boolean shouldInterceptKeys() {
        return mTouchInputHandler.shouldInterceptKeys();
    }

    void handleHostSizeChanged(int surfaceWidth, int surfaceHeight) {
        mTouchInputHandler.handleHostSizeChanged(surfaceWidth, surfaceHeight);
    }

    void handleClientSizeChanged(int screenWidth, int screenHeight) {
        mTouchInputHandler.handleClientSizeChanged(screenWidth, screenHeight);
    }

    void reloadPreferences(@NonNull Prefs prefs) {
        mTouchInputHandler.reloadPreferences(prefs);
    }

    void setTouchSensitivity(int sensitivity) {
        mTouchInputHandler.setLongPressedDelay(sensitivity);
    }

    void performConfiguredAction(@NonNull Prefs prefs, @Nullable String actionName) {
        if (actionName == null)
            return;
        mTouchInputHandler.extractUserActionFromPreferences(prefs, actionName).accept(0, true);
    }
}

final class X11BroadcastReceiver extends BroadcastReceiver {
    static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";

    interface Host {
        @NonNull
        Prefs getX11Prefs();

        @NonNull
        X11ServerConnector getX11ServerConnector();

        @Nullable
        X11InputController getX11InputController();

        void onX11PreferenceChangedFromBroadcast(@Nullable String key);

        void finishX11Host();
    }

    @NonNull
    private final Host mHost;

    X11BroadcastReceiver(@NonNull Host host) {
        mHost = host;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        mHost.getX11Prefs().recheckStoringSecondaryDisplayPreferences();

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            receiveConnection(intent);
        } else if (ACTION_STOP.equals(action)) {
            mHost.finishX11Host();
        } else if (LoriePreferences.ACTION_PREFERENCES_CHANGED.equals(action)) {
            String key = intent.getStringExtra("key");
            Log.d("X11BroadcastReceiver", "preference: " + key);
            if (!"additionalKbdVisible".equals(key))
                mHost.onX11PreferenceChangedFromBroadcast(key);
        } else if (LorieViewRuntimeApi.InputHost.ACTION_CUSTOM.equals(action)) {
            X11InputController inputController = mHost.getX11InputController();
            if (inputController != null) {
                Log.d("X11BroadcastReceiver", "action " + intent.getStringExtra("what"));
                inputController.performConfiguredAction(mHost.getX11Prefs(), intent.getStringExtra("what"));
            }
        }
    }

    private void receiveConnection(@NonNull Intent intent) {
        try {
            Log.v("X11BroadcastReceiver", "Got new ACTION_START intent");
            mHost.getX11ServerConnector().onReceiveConnection(intent);
        } catch (Exception e) {
            Log.e("X11BroadcastReceiver", "Something went wrong while we extracted connection details from binder.", e);
        }
    }
}

final class X11BroadcastRegistrar {
    @NonNull
    private final Context mContext;
    @NonNull
    private final X11BroadcastReceiver mReceiver;
    private boolean mRegistered;

    X11BroadcastRegistrar(@NonNull Context context, @NonNull X11BroadcastReceiver.Host host) {
        mContext = context;
        mReceiver = new X11BroadcastReceiver(host);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    void register() {
        if (mRegistered)
            return;

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Context.RECEIVER_EXPORTED : 0;
        mContext.registerReceiver(mReceiver, createIntentFilter(), flags);
        mRegistered = true;
    }

    void unregister() {
        if (!mRegistered)
            return;

        mContext.unregisterReceiver(mReceiver);
        mRegistered = false;
    }

    @NonNull
    private static IntentFilter createIntentFilter() {
        IntentFilter filter = new IntentFilter(ACTION_START);
        filter.addAction(LoriePreferences.ACTION_PREFERENCES_CHANGED);
        filter.addAction(X11BroadcastReceiver.ACTION_STOP);
        filter.addAction(LorieViewRuntimeApi.InputHost.ACTION_CUSTOM);
        return filter;
    }
}

final class X11ServerConnector {
    interface Host {
        @NonNull
        Intent getIntent();

        void setIntent(@NonNull Intent intent);

        void runOnUiThread(Runnable action);

        @NonNull
        LorieView getLorieView();

        @NonNull
        Prefs getX11Prefs();

        void onX11ServerConnectionChanged();
    }

    @NonNull
    private final Handler mHandler;
    @NonNull
    private final Host mHost;
    @NonNull
    private final Runnable mRetryConnectRunnable = this::tryConnect;
    @Nullable
    private ICmdEntryInterface mService;

    X11ServerConnector(@NonNull Handler handler, @NonNull Host host) {
        mHandler = handler;
        mHost = host;
    }

    void onReceiveConnection(@Nullable Intent intent) {
        Bundle bundle = intent == null ? null : intent.getBundleExtra(null);
        IBinder binder = bundle == null ? null : bundle.getBinder(null);
        if (binder == null)
            return;

        mService = ICmdEntryInterface.Stub.asInterface(binder);
        try {
            mService.asBinder().linkToDeath(() -> {
                mService = null;

                Log.v("Lorie", "Disconnected");
                mHost.runOnUiThread(() -> {
                    LorieView.connect(-1);
                    mHost.onX11ServerConnectionChanged();
                });
            }, 0);
        } catch (RemoteException ignored) {
        }

        try {
            if (mService != null && mService.asBinder().isBinderAlive()) {
                Log.v("LorieBroadcastReceiver", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = mService.getLogcatOutput();
                if (logcatOutput != null)
                    LorieView.startLogcat(logcatOutput.detachFd());

                tryConnect();

                if (intent != null && intent != mHost.getIntent())
                    mHost.setIntent(intent);
            }
        } catch (Exception e) {
            Log.e("X11ServerConnector", "Something went wrong while establishing connection", e);
        }
    }

    boolean tryConnect() {
        if (LorieView.xConnected()) {
            // The native connection is process-wide while more than one embedded host may have
            // a registered receiver. Every host still needs to reconcile its own view state.
            mHost.onX11ServerConnectionChanged();
            return false;
        }

        if (mService == null) {
            LorieView.requestConnection();
            scheduleReconnect();
            return true;
        }

        try {
            ParcelFileDescriptor fd = mService.getXConnection();
            if (fd != null) {
                Log.v("X11ServerConnector", "Extracting X connection socket.");
                LorieView.connect(fd.detachFd());
                mHost.getLorieView().triggerCallback();
                mHost.onX11ServerConnectionChanged();
                mHost.getLorieView().reloadPreferences(mHost.getX11Prefs());
            } else {
                scheduleReconnect();
            }
        } catch (Exception e) {
            Log.e("X11ServerConnector", "Something went wrong while establishing connection", e);
            mService = null;
            scheduleReconnect();
        }
        return false;
    }

    void detach() {
        mHandler.removeCallbacks(mRetryConnectRunnable);
        mService = null;
    }

    private void scheduleReconnect() {
        mHandler.removeCallbacks(mRetryConnectRunnable);
        mHandler.postDelayed(mRetryConnectRunnable, 250);
    }
}

final class X11SoftKeyboardController {
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final LorieViewRuntimeApi.InputHost mHost;
    @Nullable
    private InputMethodManager mInputMethodManager;
    private boolean mSoftKeyboardShown;
    private boolean mShowImeWhileExternalConnected = true;
    private boolean mExternalKeyboardConnected;

    X11SoftKeyboardController(@NonNull Handler handler, @NonNull LorieViewRuntimeApi.InputHost host) {
        mHandler = handler;
        mHost = host;
    }

    void attach() {
        mInputMethodManager = (InputMethodManager) mHost.getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    boolean isSoftKeyboardShown() {
        return mSoftKeyboardShown;
    }

    void close() {
        InputMethodManager inputMethodManager = mInputMethodManager;
        if (inputMethodManager != null) {
            Activity activity = mHost.getActivity();
            inputMethodManager.hideSoftInputFromWindow(activity.getWindow().getDecorView().getRootView().getWindowToken(), 0);
        }
        mSoftKeyboardShown = false;
    }

    void setShowImeWhileExternalConnected(boolean show) {
        mShowImeWhileExternalConnected = show;
    }

    void toggleKeyboardVisibility() {
        mHandler.postDelayed(() -> {
            Log.d("X11SoftKeyboardController", "Toggling keyboard visibility");
            InputMethodManager inputMethodManager = mInputMethodManager;
            if (inputMethodManager == null)
                return;

            Log.d("X11SoftKeyboardController", "externalKeyboardConnected " + mExternalKeyboardConnected + " showIMEWhileExternalConnected " + mShowImeWhileExternalConnected);
            if (LorieView.connected())
                mHost.getLorieView().requestFocus();
            if (!mExternalKeyboardConnected || mShowImeWhileExternalConnected)
                show();
            else
                close();
        }, 1000);
    }

    void setExternalKeyboardConnected(boolean connected, @Nullable EditText textInput) {
        mExternalKeyboardConnected = connected;
        if (textInput != null)
            textInput.setShowSoftInputOnFocus(!connected || mShowImeWhileExternalConnected);
        if (connected && !mShowImeWhileExternalConnected)
            close();
        mHost.getLorieView().requestFocus();
    }

    private void show() {
        InputMethodManager inputMethodManager = mInputMethodManager;
        if (inputMethodManager != null)
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        mSoftKeyboardShown = true;
    }
}

final class X11WinHandlerController {
    @NonNull
    private final LorieViewRuntimeApi.WinHandlerHost mHost;
    @Nullable
    private WinHandler mWinHandler;

    X11WinHandlerController(@NonNull LorieViewRuntimeApi.WinHandlerHost host) {
        mHost = host;
    }

    void attach(@NonNull LorieView lorieView) {
        if (mWinHandler != null)
            return;

        WinHandler winHandler = new WinHandler(mHost);
        mWinHandler = winHandler;
        lorieView.setWinHandler(winHandler);
        Executors.newSingleThreadExecutor().execute(winHandler::start);
    }

    void detach() {
        if (mWinHandler == null)
            return;

        mWinHandler.stop();
        mWinHandler = null;
    }

    @NonNull
    WinHandler getWinHandler() {
        if (mWinHandler == null)
            throw new IllegalStateException("WinHandler is not attached");
        return mWinHandler;
    }

    boolean isRunning() {
        return mWinHandler != null;
    }

    void showProcessManagerDialog() {
        new TaskManagerDialog(mHost).show();
    }
}

final class X11WindowModeController {
    @NonNull
    private final LorieViewRuntimeApi.LorieHost mHost;
    private boolean mManageHostOrientation = true;

    X11WindowModeController(@NonNull LorieViewRuntimeApi.LorieHost host) {
        mHost = host;
    }

    void setManageHostOrientation(boolean manageHostOrientation) {
        mManageHostOrientation = manageHostOrientation;
    }

    void applyWindowPreferences(boolean hasFocus) {
        Activity activity = mHost.getActivity();
        Prefs prefs = mHost.getX11Prefs();

        KeyInterceptor.recheck();
        prefs.recheckStoringSecondaryDisplayPreferences();

        Window window = activity.getWindow();
        View decorView = window.getDecorView();
        boolean fullscreen = prefs.fullscreen.get();
        boolean hideCutout = prefs.hideCutout.get();
        boolean reseed = prefs.Reseed.get();

        int requestedOrientation = getRequestedOrientation(prefs.forceOrientation.get());
        boolean orientationChangeRequested = mManageHostOrientation &&
            activity.getRequestedOrientation() != requestedOrientation;
        if (orientationChangeRequested)
            activity.setRequestedOrientation(requestedOrientation);

        if (hasFocus) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (hideCutout) {
                    activity.getWindow().getAttributes().layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                } else {
                    activity.getWindow().getAttributes().layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                }
            }

            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);
        }

        window.setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | FLAG_KEEP_SCREEN_ON | FLAG_TRANSLUCENT_STATUS, 0);
        if (hasFocus) {
            if (fullscreen) {
                window.addFlags(FLAG_FULLSCREEN);
                decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                window.clearFlags(FLAG_FULLSCREEN);
                decorView.setSystemUiVisibility(0);
            }
        }

        if (prefs.keepScreenOn.get())
            window.addFlags(FLAG_KEEP_SCREEN_ON);
        else
            window.clearFlags(FLAG_KEEP_SCREEN_ON);

        window.setSoftInputMode(reseed ? SOFT_INPUT_ADJUST_RESIZE : SOFT_INPUT_ADJUST_PAN);

        if (hasFocus && !orientationChangeRequested) {
            mHost.getLorieView().regenerate();
            mHost.getLorieView().requestLayout();
        }
        mHost.getLorieView().requestFocus();
    }

    private int getRequestedOrientation(@NonNull String forceOrientation) {
        switch (forceOrientation) {
            case "portrait":
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case "landscape":
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case "reverse portrait":
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case "reverse landscape":
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            default:
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }
}

final class X11PreferencesController {
    interface Host extends LorieViewRuntimeApi.LorieHost {
        @NonNull
        X11InputController getX11InputController();

        @NonNull
        X11SoftKeyboardController getX11SoftKeyboardController();

        void applyX11WindowPreferences();

        void refreshX11TerminalToolbar();

        void setX11FilterOutWinKey(boolean filter);

        void setX11TermuxExtraKeysBarBehaviour(boolean useTermuxExtraKeysBarBehaviour);

        void setX11FloatBallMenuState(boolean enableFloatBallMenu, boolean enableGlobalFloatBallMenu);

        void setX11MouseAuxButtonsVisible(boolean visible);

        void setX11StylusAuxButtonsVisible(boolean visible);

        void setX11DisplayToolbarAlpha(float alpha);

        boolean isX11InPictureInPictureMode();
    }

    @NonNull
    private final Handler mHandler;
    @NonNull
    private final Host mHost;
    @NonNull
    private final Runnable mApplyPreferencesRunnable = this::applyPreferences;

    X11PreferencesController(@NonNull Handler handler, @NonNull Host host) {
        mHandler = handler;
        mHost = host;
    }

    void onPreferencesChanged(@Nullable String key) {
        if ("additionalKbdVisible".equals(key))
            return;

        Prefs prefs = mHost.getX11Prefs();
        if ("enableFloatBallMenu".equals(key) || "enableGlobalFloatBallMenu".equals(key)) {
            mHost.setX11FloatBallMenuState(prefs.enableFloatBallMenu.get(), prefs.enableGlobalFloatBallMenu.get());
            return;
        }

        mHandler.removeCallbacks(mApplyPreferencesRunnable);
        mHandler.postDelayed(mApplyPreferencesRunnable, 100);
    }

    void cancelPendingChanges() {
        mHandler.removeCallbacks(mApplyPreferencesRunnable);
    }

    @SuppressLint("UnsafeIntentLaunch")
    private void applyPreferences() {
        Prefs prefs = mHost.getX11Prefs();
        prefs.recheckStoringSecondaryDisplayPreferences();

        mHost.applyX11WindowPreferences();
        LorieView lorieView = mHost.getLorieView();

        mHost.getX11InputController().reloadPreferences(prefs);
        lorieView.reloadPreferences(prefs);

        mHost.refreshX11TerminalToolbar();

        lorieView.triggerCallback();

        mHost.setX11FilterOutWinKey(prefs.filterOutWinkey.get());
        if (prefs.enableAccessibilityServiceAutomatically.get())
            KeyInterceptor.launch(mHost.getActivity());
        else if (mHost.getActivity().checkSelfPermission(WRITE_SECURE_SETTINGS) == PERMISSION_GRANTED)
            KeyInterceptor.shutdown(true);

        mHost.setX11TermuxExtraKeysBarBehaviour(prefs.useTermuxEKBarBehaviour.get());
        mHost.getX11SoftKeyboardController().setShowImeWhileExternalConnected(prefs.showIMEWhileExternalConnected.get());

        mHost.setX11MouseAuxButtonsVisible(prefs.showMouseHelper.get());
        mHost.setX11StylusAuxButtonsVisible(prefs.showStylusClickOverride.get());

        float toolbarAlpha = mHost.isX11InPictureInPictureMode() ? 0.f : ((float) prefs.opacityEKBar.get()) / 100;
        mHost.setX11DisplayToolbarAlpha(toolbarAlpha);

        lorieView.requestLayout();
        lorieView.invalidate();
    }
}
