package com.termux.x11;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_BACK;
import static android.view.KeyEvent.KEYCODE_META_LEFT;
import static android.view.KeyEvent.KEYCODE_META_RIGHT;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.DragEvent;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;
import androidx.fragment.app.FragmentManager;
import androidx.viewpager.widget.ViewPager;

import com.termux.x11.controller.container.Container;
import com.termux.x11.controller.container.Shortcut;
import com.termux.x11.controller.InputControllerActivity;
import com.termux.x11.controller.contentdialog.ContentDialog;
import com.termux.x11.controller.core.DownloadProgressDialog;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.widget.TouchpadView;
import com.termux.x11.controller.winhandler.ProcessInfo;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.FullscreenWorkaround;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.SamsungDexUtils;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public final class LorieViewRuntimeController implements LorieViewRuntimeApi.LorieHost, LorieViewRuntimeApi.ToolbarHost, LorieViewRuntimeApi.WinHandlerHost, LorieViewRuntimeApi.InputControlsHost, X11ServerConnector.Host, X11BroadcastReceiver.Host, X11PreferencesController.Host {
    public static final String ACTION_CUSTOM = LorieViewRuntimeApi.InputHost.ACTION_CUSTOM;
    private static final android.os.Handler handler = LoriePreferences.handler;
    public TermuxX11ExtraKeys mExtraKeys;
    protected FrameLayout frm;
    @NonNull
    private final LorieViewRuntimeApi.Host mHost;
    @NonNull
    private final Activity mActivity;
    @NonNull
    private final Prefs prefs;
    protected final LorieViewRuntimeApi.DisplayController mX11DisplayController = new LorieViewRuntimeApi.DisplayController();
    protected final X11ServerConnector mX11ServerConnector = new X11ServerConnector(handler, this);
    protected X11InputController mX11InputController;
    private final X11BroadcastRegistrar mX11BroadcastRegistrar;
    private final X11SoftKeyboardController mX11SoftKeyboardController = new X11SoftKeyboardController(handler, this);
    private final X11WinHandlerController mX11WinHandlerController = new X11WinHandlerController(this);
    private final X11WindowModeController mX11WindowModeController = new X11WindowModeController(this);
    private final X11PreferencesController mX11PreferencesController = new X11PreferencesController(handler, this);
    private View.OnKeyListener mLorieKeyListener;
    private boolean filterOutWinKey = false;
    private static final int KEY_BACK = 158;
    protected boolean mEnableFloatBallMenu = false;
    private boolean isInPictureInPictureMode = false;
    boolean useTermuxEKBarBehaviour = false;
    private LorieViewRuntimeApi.ActivityIntegration termuxActivityListener;
    private LorieView xServer;
    private int orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    private InputControlsManager inputControlsManager;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private DownloadProgressDialog preloaderDialog;
    private float globalCursorSpeed = 1.0f;
    private ControlsProfile profile;
    private String controlsProfile;
    private Container container;

    //    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChangedListener = (__, key) -> onPreferencesChanged(key);

    ViewTreeObserver.OnPreDrawListener mOnPredrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (LorieView.connected())
                handler.post(() -> findViewById(android.R.id.content).getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener));
            return false;
        }
    };

    private static final AtomicInteger liveInstanceCount = new AtomicInteger(0);

    public LorieViewRuntimeController(@NonNull LorieViewRuntimeApi.Host host) {
        mHost = host;
        mActivity = host.getActivity();
        prefs = new Prefs(mActivity);
        LoriePreferences.prefs = prefs;
        mX11BroadcastRegistrar = new X11BroadcastRegistrar(mActivity, this);
        liveInstanceCount.incrementAndGet();
        LorieViewRuntimeRegistry.register(this);
        KeyInterceptor.setActivity(this);
        initializeLorieViewRuntimePreferences();
    }

    public static boolean hasLiveInstance() {
        return liveInstanceCount.get() > 0;
    }

    public Prefs getX11Prefs() {
        return prefs;
    }

    @Nullable
    public LorieViewRuntimeApi.ActivityIntegration getX11ActivityIntegration() {
        return termuxActivityListener;
    }

    public void setX11ActivityIntegration(@Nullable LorieViewRuntimeApi.ActivityIntegration integration) {
        termuxActivityListener = integration;
    }

    @NonNull
    @Override
    public FragmentManager getSupportFragmentManager() {
        return mHost.getSupportFragmentManager();
    }

    @NonNull
    @Override
    public Intent getIntent() {
        return mActivity.getIntent();
    }

    @Override
    public void setIntent(@NonNull Intent intent) {
        mActivity.setIntent(intent);
    }

    @Override
    public void runOnUiThread(Runnable action) {
        mActivity.runOnUiThread(action);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T findViewById(int id) {
        return mActivity.findViewById(id);
    }

    private Window getWindow() {
        return mActivity.getWindow();
    }

    private android.content.res.Resources getResources() {
        return mActivity.getResources();
    }

    private Object getSystemService(String name) {
        return mActivity.getSystemService(name);
    }

    @Override
    public boolean hasWindowFocus() {
        return mActivity.hasWindowFocus();
    }

    @Override
    public boolean shouldUseTermuxExtraKeysBarBehaviour() {
        return useTermuxEKBarBehaviour;
    }

    @Override
    public void unsetExtraKeysSpecialKeys() {
        if (mExtraKeys != null)
            mExtraKeys.unsetSpecialKeys();
    }

    @Override
    public void setTermuxX11ExtraKeys(TermuxX11ExtraKeys extraKeys) {
        mExtraKeys = extraKeys;
    }

    @Override
    public TermuxX11ExtraKeys getTermuxX11ExtraKeys() {
        return mExtraKeys;
    }

    private void initializeLorieViewRuntimePreferences() {
        int modeValue = Integer.parseInt(prefs.touchMode.get()) - 1;
        if (modeValue > 2) {
            prefs.touchMode.put("1");
        }
    }

    public void attachTermuxScreenView(@NonNull TermuxScreenView displayView) {
        initializeX11Display(displayView);
    }

    public void setX11ConnectionStateListener(LorieViewRuntimeApi.DisplayConnectionListener listener) {
        mX11DisplayController.setConnectionStateListener(listener);
    }

    protected void initializeX11Display(@NonNull TermuxScreenView displayView) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);

        LorieView previousLorieView = mX11DisplayController.getLorieView();
        if (previousLorieView != null) {
            previousLorieView.clearCallback();
            previousLorieView.setLorieHost(null);
        }
        mX11DisplayController.attach(this, displayView);

        frm = displayView.getDisplayFrame();
        displayView.findViewById(R.id.preferences_button).setOnClickListener((l) -> {
            if (null != termuxActivityListener) {
                termuxActivityListener.onX11PreferenceSwitchChange(true);
            }
        });
        LorieView lorieView = displayView.getLorieView();
        lorieView.setLorieHost(this);
        View lorieParent = (View) lorieView.getParent();
//        Log.d("Mainactivity","frm==lorieParent:"+String.valueOf(frm==lorieParent));

        int touch_sensitivity = preferences.getInt("touch_sensitivity", 1);
        mX11InputController = new X11InputController(this, lorieView, touch_sensitivity);
//        Log.d("LorieViewRuntimeController","touch_sensitivity:"+touch_sensitivity);
        mLorieKeyListener = (v, k, e) -> {

            if (k == KEYCODE_BACK) {
                if (mX11SoftKeyboardController.isSoftKeyboardShown()) {
                    if (e.getAction() == ACTION_UP) {
                        mX11SoftKeyboardController.close();
                    }
                    return true;
                }
                if (null != termuxActivityListener && !mEnableFloatBallMenu) {
                    if (e.getAction() == ACTION_UP) {
                        releaseSlider(true);
                    }
                    return true;
                }
                if (!getX11Focus()) {
                    if (e.getAction() == ACTION_UP) {
                        if (!back2PreviousMenu()) {
                            termuxActivityListener.onX11PreferenceSwitchChange(false);
                        }
                    }
                    return true;
                }
            }
            InputDevice dev = e.getDevice();
            boolean result = mX11InputController.sendKeyEvent(e);

            // Do not steal dedicated buttons from a full external keyboard.
            if (useTermuxEKBarBehaviour && mExtraKeys != null && (dev == null || dev.isVirtual()))
                mExtraKeys.unsetSpecialKeys();
            return result;
        };
        View.OnTouchListener lorieTouchListener = (v, event) -> {
            MotionEvent x11Event = MotionEvent.obtain(event);
            try {
                return mX11InputController.handleTouchEvent(lorieParent, lorieView, x11Event);
            } finally {
                x11Event.recycle();
            }
        };
        lorieParent.setOnTouchListener(lorieTouchListener);
        lorieView.setOnTouchListener(lorieTouchListener);
        lorieView.setOnHoverListener((v, e) -> {
            MotionEvent x11Event = MotionEvent.obtain(e);
            try {
                return mX11InputController.handleTouchEvent(lorieParent, lorieView, x11Event);
            } finally {
                x11Event.recycle();
            }
        });
        lorieView.setOnKeyListener(mLorieKeyListener);

        lorieView.setCallback((surfaceWidth, surfaceHeight, screenWidth, screenHeight) -> {
            String name;
            int framerate = (int) ((lorieView.getDisplay() != null) ? lorieView.getDisplay().getRefreshRate() : 30);

            mX11InputController.handleHostSizeChanged(surfaceWidth, surfaceHeight);
            mX11InputController.handleClientSizeChanged(screenWidth, screenHeight);
            if (lorieView.getDisplay() == null || lorieView.getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY)
                name = "Builtin Display";
            else if (SamsungDexUtils.checkDeXEnabled(mActivity))
                name = "Dex Display";
            else
                name = "External Display";
            LorieView.sendWindowChange(screenWidth, screenHeight, framerate, name);
        });

        mX11BroadcastRegistrar.register();

        mX11SoftKeyboardController.attach();

        // Taken from Stackoverflow answer https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible/7509285#
//        FullscreenWorkaround.assistActivity(this);

        if (mX11ServerConnector.tryConnect()) {
            final View content = findViewById(android.R.id.content);
            content.getViewTreeObserver().addOnPreDrawListener(mOnPredrawListener);
            handler.postDelayed(() -> content.getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener), 500);
        }
        onPreferencesChanged("");

        toggleExtraKeys(false, false);

        initStylusAuxButtons();
        initMouseAuxButtons();
        setupInputController();

        if (SDK_INT >= VERSION_CODES.TIRAMISU
            && mActivity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
            && !mActivity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            mActivity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        mX11WinHandlerController.attach(lorieView);
    }

    public void destroy() {
        mX11ServerConnector.detach();
        LorieView lorieView = mX11DisplayController.getLorieView();
        if (lorieView != null) {
            lorieView.clearCallback();
            lorieView.setLorieHost(null);
        }
        mX11DisplayController.detach();
        mX11WinHandlerController.detach();
        mX11BroadcastRegistrar.unregister();
        liveInstanceCount.updateAndGet(count -> Math.max(0, count - 1));
        LorieViewRuntimeRegistry.unregister(this);
        KeyInterceptor.clearActivity(this);
    }

    @NonNull
    @Override
    public Activity getActivity() {
        return mActivity;
    }

    @Override
    public void openX11Preferences(boolean open) {
        if (termuxActivityListener != null)
            termuxActivityListener.onX11PreferenceSwitchChange(open);
    }

    @Override
    public void requestX11Focus(boolean focused) {
        setX11FocusedChanged(focused);
    }

    @Override
    public void openSoftKeyboard() {
        toggleKeyboardVisibility();
    }

    @Override
    @NonNull
    public LorieViewRuntimeApi.DisplayController getX11DisplayController() {
        return mX11DisplayController;
    }

    @NonNull
    @Override
    public X11ServerConnector getX11ServerConnector() {
        return mX11ServerConnector;
    }

    @Override
    @NonNull
    public X11InputController getX11InputController() {
        return mX11InputController;
    }

    @Override
    public void onX11PreferenceChangedFromBroadcast(String key) {
        onPreferencesChanged("");
    }

    @Override
    public void finishX11Host() {
        mActivity.finishAffinity();
    }

    public void showProcessManager() {
        mX11WinHandlerController.showProcessManagerDialog();
    }

    public void showProcessManagerDialog() {
        mX11WinHandlerController.showProcessManagerDialog();
    }

    private void setupInputController() {
        xServer = getLorieView();
        globalCursorSpeed = 1.0f;
        touchpadView = new TouchpadView(mActivity, xServer);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setVisibility(View.GONE);
//        touchpadView.setBackground(getDrawable(R.drawable.touchpad_background));
        frm.addView(touchpadView);

        inputControlsView = new InputControlsView(mActivity);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity.getBaseContext());
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setPassthroughTouchDispatcher(event -> {
            if (!LorieView.connected())
                return false;
            if (mX11InputController == null)
                return false;
            LorieView lorieView = getLorieView();
            if (lorieView == null || lorieView.getParent() == null)
                return false;
            MotionEvent forwardedEvent = MotionEvent.obtain(event);
            try {
                return mX11InputController.handleTouchEvent((View) lorieView.getParent(), lorieView, forwardedEvent);
            } finally {
                forwardedEvent.recycle();
            }
        });
        inputControlsView.setVisibility(View.GONE);
        frm.addView(inputControlsView);
        inputControlsManager = new InputControlsManager(mActivity);
        preloaderDialog = new DownloadProgressDialog(mActivity);
        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        container = new Container(0);
        if (shortcutPath != null && !shortcutPath.isEmpty())
            shortcut = new Shortcut(container, new File(shortcutPath));
    }

    //Register the needed events to handle stylus as left, middle and right click
    @SuppressLint("ClickableViewAccessibility")
    private void initStylusAuxButtons() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean stylusMenuEnabled = p.getBoolean("showStylusClickOverride", false);
        final float menuUnselectedTrasparency = 0.66f;
        final float menuSelectedTrasparency = 1.0f;
        Button left = findViewById(R.id.button_left_click);
        Button right = findViewById(R.id.button_right_click);
        Button middle = findViewById(R.id.button_middle_click);
        Button visibility = findViewById(R.id.button_visibility);
        LinearLayout overlay = findViewById(R.id.mouse_helper_visibility);
        LinearLayout buttons = findViewById(R.id.mouse_helper_secondary_layer);
        overlay.setOnTouchListener((v, e) -> true);
        overlay.setOnHoverListener((v, e) -> true);
        overlay.setOnGenericMotionListener((v, e) -> true);
        overlay.setOnCapturedPointerListener((v, e) -> true);
        overlay.setVisibility(stylusMenuEnabled ? VISIBLE : View.GONE);
        View.OnClickListener listener = view -> {
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = (view.equals(left) ? 1 : (view.equals(middle) ? 2 : (view.equals(right) ? 3 : 0)));
            left.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 1) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            middle.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 2) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            right.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 3) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            visibility.setAlpha(menuUnselectedTrasparency);
        };

        left.setOnClickListener(listener);
        middle.setOnClickListener(listener);
        right.setOnClickListener(listener);

        visibility.setOnClickListener(view -> {
            if (buttons.getVisibility() == VISIBLE) {
                buttons.setVisibility(View.GONE);
                visibility.setAlpha(menuUnselectedTrasparency);
                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                visibility.setText(m == 1 ? "L" : (m == 2 ? "M" : (m == 3 ? "R" : "U")));
            } else {
                buttons.setVisibility(VISIBLE);
                visibility.setAlpha(menuUnselectedTrasparency);
                visibility.setText("X");

                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - 4 * left.getWidth();
                float maxY = frm.getHeight() - 4 * left.getHeight();

                //Make sure the Stylus menu is fully inside the screen
                overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));

                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                listener.onClick(m == 1 ? left : (m == 2 ? middle : (m == 3 ? right : left)));
            }
        });
        //Simulated mouse click 1 = left , 2 = middle , 3 = right
        TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
        listener.onClick(left);

        visibility.setOnLongClickListener(v -> {
            v.startDragAndDrop(ClipData.newPlainText("", ""), new View.DragShadowBuilder(visibility) {
                public void onDrawShadow(Canvas canvas) {
                }
            }, null, View.DRAG_FLAG_GLOBAL);

            frm.setOnDragListener((v2, event) -> {
                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - visibility.getWidth();
                float maxY = frm.getHeight() - visibility.getHeight();

                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_LOCATION:
                        //Center touch location with btn icon
                        float dX = event.getX() - visibility.getWidth() / 2.0f;
                        float dY = event.getY() - visibility.getHeight() / 2.0f;

                        //Make sure the dragged btn is inside the view with clamp
                        overlay.setX(MathUtils.clamp(dX, 0, maxX));
                        overlay.setY(MathUtils.clamp(dY, 0, maxY));
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        //Make sure the dragged btn is inside the view
                        overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                        overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));
                        break;
                }
                return true;
            });

            return true;
        });
    }

    void setSize(View v, int width, int height) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        p.width = (int) (width * getResources().getDisplayMetrics().density);
        p.height = (int) (height * getResources().getDisplayMetrics().density);
        v.setLayoutParams(p);
        v.setMinimumWidth((int) (width * getResources().getDisplayMetrics().density));
        v.setMinimumHeight((int) (height * getResources().getDisplayMetrics().density));
    }

    @SuppressLint("ClickableViewAccessibility")
    void initMouseAuxButtons() {
        Button left = findViewById(R.id.mouse_button_left_click);
        Button right = findViewById(R.id.mouse_button_right_click);
        Button middle = findViewById(R.id.mouse_button_middle_click);
        ImageButton pos = findViewById(R.id.mouse_buttons_position);
        LinearLayout primaryLayer = findViewById(R.id.mouse_buttons);
        LinearLayout secondaryLayer = findViewById(R.id.mouse_buttons_secondary_layer);

        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean mouseHelperEnabled = p.getBoolean("showMouseHelper", false) && "1".equals(p.getString("touchMode", "1"));
        primaryLayer.setVisibility(mouseHelperEnabled ? VISIBLE : View.GONE);

        pos.setOnClickListener((v) -> {
            if (secondaryLayer.getOrientation() == LinearLayout.HORIZONTAL) {
                setSize(left, 48, 96);
                setSize(right, 48, 96);
                secondaryLayer.setOrientation(LinearLayout.VERTICAL);
            } else {
                setSize(left, 96, 48);
                setSize(right, 96, 48);
                secondaryLayer.setOrientation(LinearLayout.HORIZONTAL);
            }
            handler.postDelayed(() -> {
                int[] offset = new int[2];
                frm.getLocationOnScreen(offset);
                primaryLayer.setX(MathUtils.clamp(primaryLayer.getX(), offset[0], offset[0] + frm.getWidth() - primaryLayer.getWidth()));
                primaryLayer.setY(MathUtils.clamp(primaryLayer.getY(), offset[1], offset[1] + frm.getHeight() - primaryLayer.getHeight()));
            }, 10);
        });

        Map.of(left, InputStub.BUTTON_LEFT, middle, InputStub.BUTTON_MIDDLE, right, InputStub.BUTTON_RIGHT)
            .forEach((v, b) -> v.setOnTouchListener((__, e) -> {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN:
                        getLorieView().sendMouseEvent(0, 0, b, true, true);
                        v.setPressed(true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        getLorieView().sendMouseEvent(0, 0, b, false, true);
                        v.setPressed(false);
                        break;
                }
                return true;
            }));

        pos.setOnTouchListener(new View.OnTouchListener() {
            final int touchSlop = (int) Math.pow(ViewConfiguration.get(mActivity).getScaledTouchSlop(), 2);
            final int tapTimeout = ViewConfiguration.getTapTimeout();
            final float[] startOffset = new float[2];
            final int[] startPosition = new int[2];
            long startTime;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        primaryLayer.getLocationOnScreen(startPosition);
                        startOffset[0] = e.getX();
                        startOffset[1] = e.getY();
                        startTime = SystemClock.uptimeMillis();
                        pos.setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        int[] offset = new int[2];
                        int[] offset2 = new int[2];
                        primaryLayer.getLocationOnScreen(offset);
                        frm.getLocationOnScreen(offset2);
                        primaryLayer.setX(MathUtils.clamp(offset[0] - startOffset[0] + e.getX(), offset2[0], offset2[0] + frm.getWidth() - primaryLayer.getWidth()));
                        primaryLayer.setY(MathUtils.clamp(offset[1] - startOffset[1] + e.getY(), offset2[1], offset2[1] + frm.getHeight() - primaryLayer.getHeight()));
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        final int[] _pos = new int[2];
                        primaryLayer.getLocationOnScreen(_pos);
                        int deltaX = (int) (startOffset[0] - e.getX()) + (startPosition[0] - _pos[0]);
                        int deltaY = (int) (startOffset[1] - e.getY()) + (startPosition[1] - _pos[1]);
                        pos.setPressed(false);

                        if (deltaX * deltaX + deltaY * deltaY < touchSlop && SystemClock.uptimeMillis() - startTime <= tapTimeout) {
                            v.performClick();
                            return true;
                        }
                        break;
                    }
                }
                return true;
            }
        });
    }

    public void setX11FocusedChanged(boolean x11Focused) {
        FullscreenWorkaround.setX11Focused(x11Focused);
    }

    public boolean getX11Focus() {
        return FullscreenWorkaround.getX11Focused();
    }

    public boolean handleX11BackNavigation() {
        if (getX11Focus())
            return false;
        if (!back2PreviousMenu())
            openX11Preferences(false);
        return true;
    }

    private boolean back2PreviousMenu() {
        FragmentManager fragmentManager = mHost.getSupportFragmentManager();
        boolean isSubMenu = fragmentManager.getBackStackEntryCount() > 1;
        if (isSubMenu)
            fragmentManager.popBackStack();
        return isSubMenu;
    }

    public void releaseX11SidePanel(boolean release) {
        releaseSlider(release);
    }

    public void releaseSlider(boolean release) {
        if (termuxActivityListener != null)
            termuxActivityListener.releaseSlider(release);
    }

    public void applyX11PreferenceChange(String key) {
        onPreferencesChanged(key);
    }

    @Override
    public void onX11PreferenceChanged(String key) {
        applyX11PreferenceChange(key);
    }

    public void setX11DisplayConnected(boolean connected) {
        mX11DisplayController.setConnected(connected);
    }

    protected void onPreferencesChanged(String key) {
        mX11PreferencesController.onPreferencesChanged(key);
    }

    public void onResume() {
        if (mX11DisplayController.getLorieView() != null) {
            setTerminalToolbarView();
            getLorieView().requestFocus();
        }
    }

    public void onPause() {
    }

    public LorieView getLorieView() {
        if (mX11DisplayController.getLorieView() != null)
            return mX11DisplayController.getLorieView();
        return findViewById(R.id.lorieView);
    }

    public ViewPager getDisplayTerminalToolbarViewPager() {
        TermuxScreenView displayView = mX11DisplayController.getDisplayView();
        if (displayView != null)
            return displayView.findViewById(R.id.display_terminal_toolbar_view_pager);
        return findViewById(R.id.display_terminal_toolbar_view_pager);
    }

    @NonNull
    @Override
    public X11SoftKeyboardController getX11SoftKeyboardController() {
        return mX11SoftKeyboardController;
    }

    @Override
    public void applyX11WindowPreferences() {
        mX11WindowModeController.applyWindowPreferences(hasWindowFocus());
    }

    @Override
    public void refreshX11TerminalToolbar() {
        setTerminalToolbarView();
    }

    @Override
    public void setX11FilterOutWinKey(boolean filter) {
        filterOutWinKey = filter;
    }

    @Override
    public void setX11TermuxExtraKeysBarBehaviour(boolean useTermuxExtraKeysBarBehaviour) {
        useTermuxEKBarBehaviour = useTermuxExtraKeysBarBehaviour;
    }

    @Override
    public void setX11FloatBallMenuState(boolean enableFloatBallMenu, boolean enableGlobalFloatBallMenu) {
        mEnableFloatBallMenu = enableFloatBallMenu;
        if (termuxActivityListener != null)
            termuxActivityListener.setFloatBallMenu(mEnableFloatBallMenu, enableGlobalFloatBallMenu);
    }

    public boolean isX11FloatBallMenuEnabled() {
        return mEnableFloatBallMenu;
    }

    public void setX11FloatBallMenuEnabled(boolean enabled) {
        mEnableFloatBallMenu = enabled;
    }

    @Override
    public void setX11MouseAuxButtonsVisible(boolean visible) {
        showMouseAuxButtons(visible);
    }

    @Override
    public void setX11StylusAuxButtonsVisible(boolean visible) {
        showStylusAuxButtons(visible);
    }

    @Override
    public void setX11DisplayToolbarAlpha(float alpha) {
        getDisplayTerminalToolbarViewPager().setAlpha(alpha);
    }

    @Override
    public boolean isX11InPictureInPictureMode() {
        return isInPictureInPictureMode;
    }

    private void setTerminalToolbarView() {
        final ViewPager pager = getDisplayTerminalToolbarViewPager();

        boolean showNow = LorieView.connected() && prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get();

        pager.clearOnPageChangeListeners();

        pager.setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get()) / 100);
        pager.setVisibility(showNow ? VISIBLE : View.GONE);

        if (showNow) {
            pager.setAdapter(new X11ToolbarViewPager.PageAdapter(this, (v, k, e) -> mX11InputController.sendKeyEvent(e)));
            pager.addOnPageChangeListener(new X11ToolbarViewPager.OnPageChangeListener(this, pager));
            pager.bringToFront();
        } else {
            pager.setAdapter(null);
            if (mExtraKeys != null)
                mExtraKeys.unsetSpecialKeys();
        }

        ViewGroup.LayoutParams layoutParams = pager.getLayoutParams();
        layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *
            (TermuxX11ExtraKeys.getExtraKeysInfo() == null ? 0 : TermuxX11ExtraKeys.getExtraKeysInfo().getMatrix().length));
        pager.setLayoutParams(layoutParams);

        getLorieView().setContentInsets(0, 0, 0, prefs.adjustHeightForEK.get() && showNow ? layoutParams.height : 0);
        getLorieView().requestFocus();
    }

    public void toggleExtraKeys(boolean visible, boolean saveState) {
        boolean enabled = prefs.showAdditionalKbd.get();

        if (enabled && LorieView.connected() && saveState)
            prefs.additionalKbdVisible.put(visible);

        setTerminalToolbarView();
        getWindow().setSoftInputMode(prefs.Reseed.get() ? SOFT_INPUT_ADJUST_RESIZE : SOFT_INPUT_ADJUST_PAN);
    }

    public void toggleExtraKeys() {
        int visibility = getDisplayTerminalToolbarViewPager().getVisibility();
        toggleExtraKeys(visibility != VISIBLE, true);
        getLorieView().requestFocus();
    }

    public boolean handleKey(KeyEvent e) {
        if (filterOutWinKey && (e.getKeyCode() == KEYCODE_META_LEFT || e.getKeyCode() == KEYCODE_META_RIGHT || e.isMetaPressed()))
            return false;
        mLorieKeyListener.onKey(getLorieView(), e.getKeyCode(), e);
        return true;
    }

//    int orientation;

    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        if (newConfig.orientation != orientation) {
            mX11SoftKeyboardController.close();
        }

        orientation = newConfig.orientation;
        if (termuxActivityListener != null) {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(mActivity);
            boolean forceLandscape = p.getBoolean("forceLandscape", false);
            if (!forceLandscape) {
                termuxActivityListener.onChangeOrientation(newConfig.orientation);
            } else {
                termuxActivityListener.onChangeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        }
        setTerminalToolbarView();
//        Log.d("onConfigurationChanged","orientation:"+orientation);
    }

    public int getOrientation() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        int rotation = display.getRotation();

        switch (rotation) {
            case Surface.ROTATION_90:
            case Surface.ROTATION_270:
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            default:
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
    }

    @SuppressLint("WrongConstant")
    public void onWindowFocusChanged(boolean hasFocus) {
        mX11WindowModeController.applyWindowPreferences(hasFocus);
    }

    public static boolean hasPipPermission(@NonNull Context context) {
        AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOpsManager == null)
            return false;
        else if (Build.VERSION.SDK_INT >= VERSION_CODES.Q)
            return appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        else
            return appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    public void onUserLeaveHint() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        if (preferences.getBoolean("PIP", false) && hasPipPermission(mActivity)) {
            mActivity.enterPictureInPictureMode();
        }
    }

    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
//        toggleExtraKeys(!isInPictureInPictureMode, false);

//        frm.setPadding(0, 0, 0, 0);
        this.isInPictureInPictureMode = isInPictureInPictureMode;
        final ViewPager pager = getDisplayTerminalToolbarViewPager();
        pager.setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get()) / 100);
        findViewById(R.id.mouse_buttons).setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        findViewById(R.id.mouse_helper_visibility).setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
    }

    /**
     * Manually toggle soft keyboard visibility
     *
     * @param context calling context
     */
    public void toggleKeyboardVisibility() {
        mX11SoftKeyboardController.toggleKeyboardVisibility();
    }

    @SuppressWarnings("SameParameterValue")
    void clientConnectedStateChanged() {
        refreshConnectionState(true);
    }

    @Override
    public void onX11ServerConnectionChanged() {
        clientConnectedStateChanged();
    }

    void onRenderConnectionChanged(){
        refreshConnectionState(false);
    }

    private void refreshConnectionState(boolean reconnectIfDisconnected) {
        runOnUiThread(() -> {
            boolean connected = LorieView.connected();
            setTerminalToolbarView();
            findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && connected ? VISIBLE : View.GONE);
            findViewById(R.id.stub).setVisibility(connected ? View.INVISIBLE : VISIBLE);
            getLorieView().setVisibility(connected ? VISIBLE : View.INVISIBLE);
            updateInputControlsVisibilityForConnection(connected);
            mX11DisplayController.setConnected(connected);

            // We should recover connection in the case if file descriptor for some reason was broken...
            if (!connected && reconnectIfDisconnected) {
                mX11ServerConnector.tryConnect();
            } else if (connected) {
                getLorieView().setPointerIcon(PointerIcon.getSystemIcon(mActivity, PointerIcon.TYPE_NULL));
                openPreference(false);
            }

            onWindowFocusChanged(hasWindowFocus());
        });
    }

    public static boolean isConnected() {
        return LorieView.connected();
    }

    public boolean shouldInterceptKeys() {
        View textInput = findViewById(R.id.display_terminal_toolbar_text_input);
        if (mX11InputController == null || !hasWindowFocus() || (textInput != null && textInput.isFocused()))
            return false;

        return mX11InputController.shouldInterceptKeys();
    }

    public void setExternalKeyboardConnected(boolean connected) {
        EditText textInput = findViewById(R.id.display_terminal_toolbar_text_input);
        mX11SoftKeyboardController.setExternalKeyboardConnected(connected, textInput);
    }

    @NonNull
    @Override
    public WinHandler getWinHandler() {
        return mX11WinHandlerController.getWinHandler();
    }

    private void showStylusAuxButtons(boolean show) {
        LinearLayout buttons = findViewById(R.id.mouse_helper_visibility);
        if (LorieView.connected() && show) {
            buttons.setVisibility(VISIBLE);
            buttons.setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        } else {
            //Reset default input back to normal
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
            final float menuUnselectedTrasparency = 0.66f;
            final float menuSelectedTrasparency = 1.0f;
            findViewById(R.id.button_left_click).setAlpha(menuSelectedTrasparency);
            findViewById(R.id.button_right_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_middle_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_visibility).setAlpha(menuUnselectedTrasparency);
            buttons.setVisibility(View.GONE);
        }
    }

    private void makeSureHelpersAreVisibleAndInScreenBounds() {
        final ViewPager pager = getDisplayTerminalToolbarViewPager();
        View mouseAuxButtons = findViewById(R.id.mouse_buttons);
        View stylusAuxButtons = findViewById(R.id.mouse_helper_visibility);
        int maxYDecrement = (pager.getVisibility() == VISIBLE) ? pager.getHeight() : 0;

        mouseAuxButtons.setX(MathUtils.clamp(mouseAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - mouseAuxButtons.getWidth()));
        mouseAuxButtons.setY(MathUtils.clamp(mouseAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - mouseAuxButtons.getHeight() - maxYDecrement));

        stylusAuxButtons.setX(MathUtils.clamp(stylusAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - stylusAuxButtons.getWidth()));
        stylusAuxButtons.setY(MathUtils.clamp(stylusAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - stylusAuxButtons.getHeight() - maxYDecrement));
    }

    public void toggleStylusAuxButtons() {
        showStylusAuxButtons(findViewById(R.id.mouse_helper_visibility).getVisibility() != VISIBLE);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    private void showMouseAuxButtons(boolean show) {
        View v = findViewById(R.id.mouse_buttons);
        v.setVisibility((LorieView.connected() && show && "1".equals(prefs.touchMode.get())) ? VISIBLE : View.GONE);
        v.setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    public void toggleMouseAuxButtons() {
        showMouseAuxButtons(findViewById(R.id.mouse_buttons).getVisibility() != VISIBLE);
    }

    @Nullable
    @Override
    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    @Nullable
    @Override
    public List<ProcessInfo> getTermuxProcessorInfo(String tag) {
        return termuxActivityListener != null ? termuxActivityListener.collectProcessorInfo(tag) : null;
    }

    @NonNull
    @Override
    public DownloadProgressDialog getPreloaderDialog() {
        if (preloaderDialog == null)
            preloaderDialog = new DownloadProgressDialog(mActivity);
        return preloaderDialog;
    }

    @Nullable
    public String getControlsProfile() {
        return controlsProfile;
    }

    @Nullable
    public Shortcut getShortcut() {
        return shortcut;
    }

    public void showInputControlsDialog() {
        if (inputControlsManager == null || inputControlsView == null || touchpadView == null || xServer == null)
            return;

        final ContentDialog dialog = new ContentDialog(mActivity, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles();
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- " + mActivity.getString(R.string.disabled) + " --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile())
                    selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(mActivity, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbLockCursor = dialog.findViewById(R.id.CBLockCursor);
        cbLockCursor.setChecked(touchpadView.getTouchMode() == TouchpadView.TouchMode.LOCKED_CURSOR);

        final CheckBox cbEnableTouchScreen = dialog.findViewById(R.id.CBTouchScreen);
        cbEnableTouchScreen.setChecked(touchpadView.getTouchMode() == TouchpadView.TouchMode.TOUCH_SCREEN);
        cbEnableTouchScreen.setEnabled(!cbLockCursor.isChecked());
        cbLockCursor.setOnCheckedChangeListener((buttonView, isChecked) -> cbEnableTouchScreen.setEnabled(!isChecked));

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(mActivity, InputControllerActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            mActivity.startActivityForResult(intent, InputControllerActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            if (termuxActivityListener == null)
                return;
            TouchpadView.TouchMode touchMode = cbLockCursor.isChecked()
                ? TouchpadView.TouchMode.LOCKED_CURSOR
                : (cbEnableTouchScreen.isChecked()
                    ? TouchpadView.TouchMode.TOUCH_SCREEN
                    : TouchpadView.TouchMode.TRACK_PAD);
            xServer.cursorLocker.setEnabled(touchMode == TouchpadView.TouchMode.LOCKED_CURSOR);
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                touchpadView.setTouchMode(touchMode);
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            } else {
                hideInputControls();
            }
        });

        dialog.show();
    }

    private void showInputControls(ControlsProfile controlsProfile) {
        boolean connected = LorieView.connected();
        inputControlsView.setVisibility(connected ? View.VISIBLE : View.GONE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(controlsProfile);

        if (profile != null)
            touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setVisibility(connected ? View.VISIBLE : View.GONE);

        inputControlsView.invalidate();
        if (termuxActivityListener != null)
            termuxActivityListener.onX11PreferenceSwitchChange(false);
    }

    private void updateInputControlsVisibilityForConnection(boolean connected) {
        if (inputControlsView == null || touchpadView == null)
            return;

        boolean hasProfile = inputControlsView.getProfile() != null;
        inputControlsView.setVisibility(connected && hasProfile ? View.VISIBLE : View.GONE);
        touchpadView.setVisibility(connected && hasProfile ? View.VISIBLE : View.GONE);
    }

    public void hideInputControls() {
        if (inputControlsView == null || touchpadView == null)
            return;
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setVisibility(View.GONE);

        inputControlsView.invalidate();
    }

    public void reloadInputControlsProfiles(boolean hideCurrentControls) {
        if (hideCurrentControls)
            hideInputControls();
        if (inputControlsManager != null)
            inputControlsManager.loadProfiles(true);
    }

    @Override
    public void prepareToExit() {
        if (termuxActivityListener != null)
            termuxActivityListener.onExitApp();
    }

    @Override
    public void openPreference(boolean open) {
        if (termuxActivityListener != null)
            termuxActivityListener.onX11PreferenceSwitchChange(open);
    }

    @Override
    public void stopDesktop() {
        if (termuxActivityListener != null)
            termuxActivityListener.stopDesktop();
    }

    public void installX11ServerBridge() {
        if (termuxActivityListener != null)
            termuxActivityListener.reInstallX11StartScript(mActivity);
    }

    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == InputControllerActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && editInputControlsCallback != null) {
            editInputControlsCallback.run();
            editInputControlsCallback = null;
            return true;
        }
        return false;
    }

    //whether view include (x,y)
    private boolean isTouchPointInView(View view, int x, int y) {
        if (view == null) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int left = location[0];
        int top = location[1];
        int right = left + view.getMeasuredWidth();
        int bottom = top + view.getMeasuredHeight();
        //view.isClickable() &&
        if (y >= top && y <= bottom && x >= left
            && x <= right) {
            return true;
        }
        return false;
    }

    protected boolean extraKeyboardHandleTouchEvent(MotionEvent event) {
        if (getDisplayTerminalToolbarViewPager().getVisibility() != VISIBLE) {
            return false;
        }
        return isTouchPointInView((View) getDisplayTerminalToolbarViewPager(), (int) event.getRawX(), (int) event.getRawY());
    }
}
