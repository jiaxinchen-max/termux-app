package com.termux.app;

import com.termux.x11.LorieViewRuntimeApi;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static com.termux.shared.termux.TermuxConstants.TERMUX_FILES_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.FloatBallMenuClient;
import com.termux.app.terminal.MainSurfaceController;
import com.termux.app.terminal.MenuEntryClient;
import com.termux.app.terminal.StartEntryClient;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.app.terminal.utils.CommandUtils;
import com.termux.app.terminal.utils.FilePathUtils;
import com.termux.app.terminal.utils.FileUtils;
import com.termux.app.terminal.utils.ScreenUtils;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import com.termux.x11.TermuxScreenView;
import com.termux.x11.LoriePreferences;
import com.termux.x11.LorieViewRuntimeController;
import com.termux.x11.controller.winhandler.ProcessInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public class TermuxActivity extends AppCompatActivity implements ServiceConnection, LorieViewRuntimeApi.Host, PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private static final int FILE_REQUEST_BACKUP_CODE = 101;

    private MainSurfaceController mMainSurfaceController;
    private LorieViewRuntimeController mLorieViewRuntimeController;
    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     * The {@link TerminalViewClient} interface implementation to allow for communication between
     * {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     * The {@link TerminalSessionClient} interface implementation to allow for communication between
     * {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;
    private MenuEntryClient mMenuEntryClient;
    private boolean mPendingTerminalExit;
    private boolean mPendingTerminalMoveToBack;
    private boolean mPendingDisplayReturnToTerminal;
    private boolean mDisplaySidePanelsUnlocked;
    private boolean mPendingDisplaySidePanelUnlockBack;
    private long mDisplaySidePanelUnlockBackPromptTime;

    private static final long DISPLAY_SIDE_PANEL_UNLOCK_BACK_TIMEOUT_MS = 1500;
    private static final long DISPLAY_SIDE_PANEL_UNLOCK_IDLE_TIMEOUT_MS = 5000;

    private final Runnable mClearPendingDisplaySidePanelUnlockBackRunnable = () -> mPendingDisplaySidePanelUnlockBack = false;
    private final Runnable mDisplaySidePanelAutoLockRunnable = new Runnable() {
        @Override
        public void run() {
            handleDisplaySidePanelAutoLock();
        }
    };

    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_ID = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";
    private FloatBallMenuClient mFloatBallMenuClient;

    private LorieViewRuntimeController getLorieViewRuntime() {
        return mLorieViewRuntimeController;
    }

    public void onMenuOpen(boolean isOpen, int flag) {
        if (isOpen /*&& flag == 0*/) {
            getLorieViewRuntime().requestX11Focus(false);
        } else {
            getLorieViewRuntime().requestX11Focus(true);
        }
    }


    @NonNull
    @Override
    public Activity getActivity() {
        return this;
    }

    @Override
    public void openX11Preferences(boolean open) {
        if (open) {
            if (mMainSurfaceController != null)
                mMainSurfaceController.openEndDrawerExplicitly();
            else
                getDrawer().openDrawer(GravityCompat.END);
        } else {
            getDrawer().closeDrawer(GravityCompat.END);
        }
    }

    @Override
    public void requestX11Focus(boolean focused) {
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.requestX11Focus(focused);
    }

    @Override
    public void openSoftKeyboard() {
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.openSoftKeyboard();
    }

    @Override
    public void showProcessManager() {
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.showProcessManager();
    }

    public void showProcessManagerDialog() {
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.showProcessManagerDialog();
    }

    public void showInputControlsDialog() {
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.showInputControlsDialog();
    }

    @Override
    public void stopDesktop() {
        stopXserver();
    }

    @NonNull
    @Override
    public com.termux.x11.Prefs getX11Prefs() {
        return mLorieViewRuntimeController.getX11Prefs();
    }

    @Nullable
    @Override
    public LorieViewRuntimeApi.ActivityIntegration getX11ActivityIntegration() {
        return mLorieViewRuntimeController != null ? mLorieViewRuntimeController.getX11ActivityIntegration() : null;
    }

    @Override
    public void openPreference(boolean open) {
        openX11Preferences(open);
    }

    @Override
    public void installX11ServerBridge() {
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.installX11ServerBridge();
    }

    @Override
    public void onX11PreferenceChanged(String key) {
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.applyX11PreferenceChange(key);
    }

    private void showX11PreferenceFragment(PreferenceFragmentCompat fragment) {
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.id_window_preference, fragment)
            .addToBackStack(null)
            .commit();
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, @NonNull Preference pref) {
        final LoriePreferences.LoriePreferenceFragment fragment = new LoriePreferences.LoriePreferenceFragment(pref.getFragment());
        fragment.setTargetFragment(caller, 0);
        showX11PreferenceFragment(fragment);
        return true;
    }

    @Nullable
    public MainSurfaceController getMainSurfaceController() {
        return mMainSurfaceController;
    }

    private boolean isX11FloatBallMenuActive() {
        return getLorieViewRuntime().isX11FloatBallMenuEnabled();
    }

    private void updateDisplaySidePanelPolicy() {
        if (mMainSurfaceController != null)
            mMainSurfaceController.setDisplaySidePanelPolicy(isX11FloatBallMenuActive(), mDisplaySidePanelsUnlocked);
    }

    private void resetDisplaySidePanelUnlockBackState() {
        mPendingDisplaySidePanelUnlockBack = false;
        mDisplaySidePanelUnlockBackPromptTime = 0;
        LoriePreferences.handler.removeCallbacks(mClearPendingDisplaySidePanelUnlockBackRunnable);
    }

    private void scheduleDisplaySidePanelAutoLock() {
        LoriePreferences.handler.removeCallbacks(mDisplaySidePanelAutoLockRunnable);
        if (isDisplaySurfaceMode() && mDisplaySidePanelsUnlocked && !isX11FloatBallMenuActive())
            LoriePreferences.handler.postDelayed(mDisplaySidePanelAutoLockRunnable, DISPLAY_SIDE_PANEL_UNLOCK_IDLE_TIMEOUT_MS);
    }

    private void lockDisplaySidePanels(boolean showToast, int toastResId) {
        boolean wasUnlocked = mDisplaySidePanelsUnlocked;
        mDisplaySidePanelsUnlocked = false;
        resetDisplaySidePanelUnlockBackState();
        LoriePreferences.handler.removeCallbacks(mDisplaySidePanelAutoLockRunnable);
        updateDisplaySidePanelPolicy();
        if (showToast && wasUnlocked && toastResId != 0)
            Toast.makeText(this, toastResId, Toast.LENGTH_SHORT).show();
    }

    private void unlockDisplaySidePanels() {
        mDisplaySidePanelsUnlocked = true;
        resetDisplaySidePanelUnlockBackState();
        updateDisplaySidePanelPolicy();
        scheduleDisplaySidePanelAutoLock();
        Toast.makeText(this, R.string.x11_side_panels_unlocked, Toast.LENGTH_SHORT).show();
    }

    private void handleDisplaySidePanelAutoLock() {
        if (!isDisplaySurfaceMode() || !mDisplaySidePanelsUnlocked)
            return;
        if (getDrawer().isDrawerOpen(GravityCompat.START) || getDrawer().isDrawerOpen(GravityCompat.END)) {
            scheduleDisplaySidePanelAutoLock();
            return;
        }
        lockDisplaySidePanels(true, R.string.x11_side_panels_locked_timeout);
    }

    private boolean handleDisplaySidePanelUnlockBackRequest() {
        if (!isDisplaySurfaceMode())
            return false;

        long now = SystemClock.uptimeMillis();

        if (isX11FloatBallMenuActive()) {
            lockDisplaySidePanels(false, 0);
            Toast.makeText(this, R.string.x11_side_panels_float_ball_only, Toast.LENGTH_SHORT).show();
            return true;
        }

        if (mDisplaySidePanelsUnlocked) {
            scheduleDisplaySidePanelAutoLock();
            Toast.makeText(this, R.string.x11_side_panels_already_unlocked, Toast.LENGTH_SHORT).show();
            return true;
        }

        if (mPendingDisplaySidePanelUnlockBack
            && now - mDisplaySidePanelUnlockBackPromptTime <= DISPLAY_SIDE_PANEL_UNLOCK_BACK_TIMEOUT_MS) {
            unlockDisplaySidePanels();
            return true;
        }

        mPendingDisplaySidePanelUnlockBack = true;
        mDisplaySidePanelUnlockBackPromptTime = now;
        LoriePreferences.handler.removeCallbacks(mClearPendingDisplaySidePanelUnlockBackRunnable);
        LoriePreferences.handler.postDelayed(mClearPendingDisplaySidePanelUnlockBackRunnable, DISPLAY_SIDE_PANEL_UNLOCK_BACK_TIMEOUT_MS);
        Toast.makeText(this, R.string.x11_side_panels_unlock_prompt, Toast.LENGTH_SHORT).show();
        return true;
    }

    public void setTerminalCopyMode(boolean copyMode) {
        if (mMainSurfaceController != null)
            mMainSurfaceController.setTerminalCopyMode(copyMode);
        else
            getDrawer().setDrawerLockMode(copyMode ? DrawerLayout.LOCK_MODE_LOCKED_CLOSED : DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START);
    }

    public void openStartDrawerExplicitly() {
        if (mMainSurfaceController != null)
            mMainSurfaceController.openStartDrawerExplicitly();
        else
            getDrawer().openDrawer(GravityCompat.START);
    }

    public void toggleStartDrawerExplicitly() {
        if (mMainSurfaceController != null) {
            mMainSurfaceController.toggleStartDrawerExplicitly();
            return;
        }

        if (getDrawer().isDrawerOpen(GravityCompat.START))
            getDrawer().closeDrawer(GravityCompat.START);
        else
            getDrawer().openDrawer(GravityCompat.START);
    }

    public void showTerminalSurface() {
        mPendingDisplayReturnToTerminal = false;
        lockDisplaySidePanels(false, 0);
        if (mMainSurfaceController != null) {
            mMainSurfaceController.showTerminal();
            updateTerminalToolbarVisibilityForSurface();
        }
    }

    public void showDisplaySurface() {
        mPendingTerminalExit = false;
        mPendingTerminalMoveToBack = false;
        if (mMainSurfaceController != null) {
            lockDisplaySidePanels(false, 0);
            mMainSurfaceController.showDisplay();
            updateDisplaySidePanelPolicy();
            if (mMainSurfaceController.isDisplayMode()) {
                getLorieViewRuntime().refreshX11TerminalToolbar();
                updateTerminalToolbarVisibilityForSurface();
            }
        }
    }

    private boolean isDisplaySurfaceMode() {
        return mMainSurfaceController != null && mMainSurfaceController.isDisplayMode();
    }

    @SuppressLint("ResourceType")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();
        super.onCreate(savedInstanceState);
        mLorieViewRuntimeController = new LorieViewRuntimeController(this);
        setContentView(R.layout.activity_termux_main);

        showX11PreferenceFragment(new LoriePreferences.LoriePreferenceFragment(null));


        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());
        Bitmap bitmap = null;
        int width = ScreenUtils.getScreenWidth(this);
        int height = ScreenUtils.getScreenHeight(this);
        bitmap = Bitmap.createBitmap(width, height,
            Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.parseColor("#CC000000"));
        mTermuxActivityRootView.setBackground(new BitmapDrawable(getResources(), bitmap));
        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setX11PreferenceBackButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        mMenuEntryClient = new MenuEntryClient(this, mTermuxTerminalSessionActivityClient);

        registerForContextMenu(mTerminalView);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        setRecoverView();
        setX11Server();
        setBackupView();
        setFloatBallMenuClient();


        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
        getLorieViewRuntime().setX11ActivityIntegration(new LorieViewRuntimeApi.ActivityIntegration() {
            @Override
            public void onX11PreferenceSwitchChange(boolean isOpen) {
                openX11Preferences(isOpen);
            }

            @Override
            public void releaseSlider(boolean open) {
                if (mMainSurfaceController != null && mMainSurfaceController.isDisplayMode() && open) {
                    handleDisplaySidePanelUnlockBackRequest();
                } else if (mMainSurfaceController != null) {
                    mMainSurfaceController.restoreDrawerLockMode();
                } else {
                    getDrawer().setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END);
                }
            }

            @Override
            public void onChangeOrientation(int landscape) {
                getLorieViewRuntime().reloadInputControlsProfiles(true);
            }

            @Override
            public void reInstallX11StartScript(Activity activity) {
                activity.runOnUiThread(() -> {
                    FileUtils.copyAssetsFile2Phone(activity, "install");
                    FileUtils.copyAssetsFile2Phone(activity, "collect_process_info");
                    CommandUtils.exec(activity, "chmod", new ArrayList<>(Arrays.asList("+x", TERMUX_FILES_DIR_PATH + "/home/install")));
                    CommandUtils.exec(activity, "chmod", new ArrayList<>(Arrays.asList("+x", TERMUX_FILES_DIR_PATH + "/home/collect_process_info")));
                    FileUtils.copyAssetsFile2Phone(activity, "termux-x11-nightly-1.03.10-0-all.deb");
                    FileUtils.copyAssetsFile2Phone(activity, "xkeyboard-config_2.45_all.deb");
                    CommandUtils.execInPath(activity, "install", null, "/home/");
                });
            }

            @Override
            public void stopDesktop() {
                stopXserver();
            }

            @Override
            public void openSoftwareKeyboard() {
                TermuxActivity.this.getLorieViewRuntime().openSoftKeyboard();
            }

            @Override
            public void showProcessManager() {
                TermuxActivity.this.getLorieViewRuntime().showProcessManager();
            }

            @Override
            public void changePreference(String key) {
                TermuxActivity.this.getLorieViewRuntime().applyX11PreferenceChange(key);
            }

            @Override
            public List<ProcessInfo> collectProcessorInfo(String tag) {
                List<ProcessInfo> processInfoList = new ArrayList<>();
                runOnUiThread(() -> {
                    String path = String.format("%s/process_info", TERMUX_TMP_PREFIX_DIR_PATH);
                    CommandUtils.execInPath(TermuxActivity.this, "collect_process_info",
                        new ArrayList<>(Arrays.asList(tag)), "/home/");
                    if (tag.equals("1")) {
                        return;
                    }
                    File processorFile = new File(path);
                    BufferedReader reader = null;
                    String temp = null;
                    if (processorFile.exists()) {
                        try {
                            reader = new BufferedReader(new FileReader(processorFile));
                            while ((temp = reader.readLine()) != null) {
                                String[] strs = temp.split(" ");
                                if (strs.length < 4) {
                                    continue;
                                }
                                int lastIndex = strs[3].lastIndexOf("/");
                                String fileName = strs[3].substring(lastIndex + 1);
                                if (strs[0].toUpperCase().contains("PID") ||
                                    fileName.toUpperCase().contains("PS") ||
                                    fileName.toUpperCase().contains("SORT") ||
                                    fileName.toUpperCase().contains("HEAD") ||
                                    fileName.toUpperCase().contains("AWK")) {
                                    continue;
                                }

                                ProcessInfo processInfo = new ProcessInfo(Integer.parseInt(strs[0]),
                                    fileName, Long.parseLong(strs[1]), 15, false);
                                processInfoList.add(processInfo);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        } finally {
                            if (reader != null) {
                                try {
                                    reader.close();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    }
                });
                return processInfoList.isEmpty() ? null : processInfoList;
            }

            @Override
            public void setFloatBallMenu(boolean enableFloatBallMenu, boolean enableGlobalFloatBallMenu) {
                if (mFloatBallMenuClient != null) {
                    if (!enableFloatBallMenu) {
                        mFloatBallMenuClient.onDestroy();
                        mFloatBallMenuClient = null;
                    } else {
                        if (enableGlobalFloatBallMenu != mFloatBallMenuClient.isGlobalFloatBallMenu()) {
                            mFloatBallMenuClient.onDestroy();
                            mFloatBallMenuClient = null;
                            if (enableFloatBallMenu) {
                                LoriePreferences.handler.postDelayed(() -> {
                                    mFloatBallMenuClient = new FloatBallMenuClient(TermuxActivity.this);
                                    mFloatBallMenuClient.onCreate();
                                }, 100);
                            }

                        }
                    }
                } else {
                    if (enableFloatBallMenu) {
                        mFloatBallMenuClient = new FloatBallMenuClient(TermuxActivity.this);
                        mFloatBallMenuClient.onCreate();
                    }
                }
                lockDisplaySidePanels(false, 0);
                updateDisplaySidePanelPolicy();
            }

            @Override
            public void onExitApp() {
                TermuxActivity.this.returnToTerminalOrPrompt();
            }
        });
    }

    private void setFloatBallMenuClient() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        getLorieViewRuntime().setX11FloatBallMenuEnabled(preferences.getBoolean("enableFloatBallMenu", false));
        lockDisplaySidePanels(false, 0);
        updateDisplaySidePanelPolicy();
        if (getLorieViewRuntime().isX11FloatBallMenuEnabled()) {
            mFloatBallMenuClient = new FloatBallMenuClient(this);
            mFloatBallMenuClient.onCreate();
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.onResume();
        getLorieViewRuntime().reloadInputControlsProfiles(false);
        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.destroy();
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
        if (mFloatBallMenuClient != null) {
            mFloatBallMenuClient.onDestroy();
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mFloatBallMenuClient != null) {
            mFloatBallMenuClient.onAttachedToWindow();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.onConfigurationChanged(newConfig);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.onUserLeaveHint();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (mLorieViewRuntimeController != null)
            mLorieViewRuntimeController.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mFloatBallMenuClient != null) {
            {
                mFloatBallMenuClient.onDetachedFromWindow();
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }


    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }

    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }


    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }


    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    private void setX11Server() {
        findViewById(com.termux.x11.R.id.exit_button).setOnClickListener((v) -> {
            exitApp();
        });
        StartEntryClient startEntryClient = new StartEntryClient(this, mTermuxTerminalSessionActivityClient);
        startEntryClient.init();
    }

    private void exitApp() {
        if (mPendingTerminalExit) {
            mPendingTerminalExit = false;
            Intent exitIntent = new Intent(this, TermuxService.class)
                .setAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE);
            startService(exitIntent);
            finishActivityIfNotFinishing();
        } else {
            Toast.makeText(this, R.string.exit_toast_text, Toast.LENGTH_SHORT).show();
            mPendingTerminalExit = true;
            mPendingTerminalMoveToBack = false;
            mPendingDisplayReturnToTerminal = false;
            LoriePreferences.handler.postDelayed(() -> mPendingTerminalExit = false, 2000);
        }
    }

    private void moveTaskToBackOrPrompt() {
        if (mPendingTerminalMoveToBack) {
            mPendingTerminalMoveToBack = false;
            moveTaskToBack(true);
        } else {
            Toast.makeText(this, R.string.return_home_toast_text, Toast.LENGTH_SHORT).show();
            mPendingTerminalMoveToBack = true;
            mPendingTerminalExit = false;
            mPendingDisplayReturnToTerminal = false;
            LoriePreferences.handler.postDelayed(() -> mPendingTerminalMoveToBack = false, 2000);
        }
    }

    private void returnToTerminalOrPrompt() {
        if (mPendingDisplayReturnToTerminal) {
            mPendingDisplayReturnToTerminal = false;
            showTerminalSurface();
        } else {
            Toast.makeText(this, R.string.unlock_exit_toast_text, Toast.LENGTH_SHORT).show();
            mPendingDisplayReturnToTerminal = true;
            mPendingTerminalExit = false;
            mPendingTerminalMoveToBack = false;
            LoriePreferences.handler.postDelayed(() -> mPendingDisplayReturnToTerminal = false, 2000);
        }
    }

    private void startX11Display() {
        ArrayList<String> args = new ArrayList<>();
        args.add(":1");
        CommandUtils.exec(this, "termux-x11", args);
    }

    private void setRecoverView() {
        findViewById(R.id.recover_button).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, FILE_REQUEST_BACKUP_CODE);
        });
    }

    private void setBackupView() {
        findViewById(R.id.backup_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String command = "tar -zcf /sdcard/termux-backup.tar.gz -C /data/data/com.termux/files ./home ./usr \n";
                File file = new File(getFilesDir().getAbsolutePath() + File.separator + "home" + File.separator + "storage");
                if (!file.exists()) {
                    command = "termux-setup-storage;sleep 5s;tar -zcf /sdcard/termux-backup.tar.gz -C /data/data/com.termux/files ./home ./usr \n";
                }
                mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast().write(command);
                showTerminalSurface();
                closeTerminalSessionListView();
            }
        });
    }

    private void closeTerminalSessionListView() {
        getDrawer().closeDrawers();
    }

    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mMainSurfaceController = new MainSurfaceController(
            getDrawer(),
            findViewById(R.id.main_surface_container),
            findViewById(R.id.terminal_surface_container),
            mTerminalView);
        mMainSurfaceController.setSurfaceGestureListener(new MainSurfaceController.SurfaceGestureListener() {
            @Override
            public void onTerminalEndSwipe() {
                showDisplaySurface();
            }

            @Override
            public void onDisplayStartSwipe() {
                showTerminalSurface();
                Toast.makeText(TermuxActivity.this, R.string.open_terminal, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDisplayEndSwipe() {
                scheduleDisplaySidePanelAutoLock();
                Toast.makeText(TermuxActivity.this, com.termux.x11.R.string.open_x11_settings, Toast.LENGTH_SHORT).show();
            }
        });
        updateDisplaySidePanelPolicy();
        setDisplaySidePanelDrawerListener();
        TermuxScreenView termuxScreenView = new TermuxScreenView(this);
        mMainSurfaceController.attachDisplayView(termuxScreenView);
        getLorieViewRuntime().attachTermuxScreenView(termuxScreenView);
        getLorieViewRuntime().setX11ConnectionStateListener(connected -> {
            if (connected)
                showDisplaySurface();
        });
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

    }

    private void setDisplaySidePanelDrawerListener() {
        getDrawer().addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                if (isDisplaySurfaceMode() && mDisplaySidePanelsUnlocked && !isX11FloatBallMenuActive())
                    lockDisplaySidePanels(true, R.string.x11_side_panels_locked_closed);
            }
        });
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }


    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
        updateTerminalToolbarVisibilityForSurface();
    }

    private void updateTerminalToolbarVisibilityForSurface() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        terminalToolbarViewPager.setVisibility(!isDisplaySurfaceMode() && mPreferences.shouldShowTerminalToolbar() ? View.VISIBLE : View.GONE);
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        if (isDisplaySurfaceMode()) {
            getLorieViewRuntime().toggleExtraKeys();
            return;
        }

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;
        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty())
                savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }


    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setX11PreferenceBackButtonView() {
        findViewById(R.id.x11_preference_back_button).setOnClickListener(v -> {
            navigateX11PreferencesBack();
        });
    }

    private void navigateX11PreferencesBack() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 1) {
            getSupportFragmentManager().popBackStack();
        } else {
            openX11Preferences(false);
        }
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }

    @SuppressLint({"RtlHardcoded", "MissingSuperCall"})
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(GravityCompat.START)) {
            getDrawer().closeDrawers();
        } else if (getDrawer().isDrawerOpen(GravityCompat.END)) {
            navigateX11PreferencesBack();
        } else if (!isDisplaySurfaceMode()) {
            moveTaskToBackOrPrompt();
        } else {
            handleDisplaySidePanelUnlockBackRequest();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /**
     * Show a toast and dismiss the last one if still visible.
     */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }


    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean addAutoFillMenu = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AutofillManager autofillManager = getSystemService(AutofillManager.class);
            if (autofillManager != null && autofillManager.isEnabled()) {
                addAutoFillMenu = true;
            }
        }

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (addAutoFillMenu)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_ID, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /**
     * Hook system menu to show context menu instead.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_ID:
                requestAutoFill();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }

    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }

    private void requestAutoFill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AutofillManager autofillManager = getSystemService(AutofillManager.class);
            if (autofillManager != null && autofillManager.isEnabled()) {
                autofillManager.requestAutofill(mTerminalView);
            }
        }
    }


    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: " + resultCode + ", data: " + IntentUtils.getIntentString(data));
        if (mLorieViewRuntimeController != null && mLorieViewRuntimeController.onActivityResult(requestCode, resultCode, data))
            return;
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
        if (requestCode == FILE_REQUEST_BACKUP_CODE) {
            onRequestLoadBackFile(requestCode, resultCode, data);
        }
    }

    public void reInstallCustomStartScript(Integer mode) {
        runOnUiThread(() -> {
            FileUtils.copyAssetsFile2Phone(this, "setMoBoxEnv");
            FileUtils.copyAssetsFile2Phone(this, "winhandler.exe");
            FileUtils.copyAssetsFile2Phone(this, "wfm.exe");
            FileUtils.copyAssetsFile2Phone(this, "wine.tar");
            String command = "chmod +x " + TERMUX_HOME_DIR_PATH + "/setMoBoxEnv && " + TERMUX_HOME_DIR_PATH + "/setMoBoxEnv ";
            if (mode != null) {
                command = command + mode;
            }
            command = command + "\n";
            mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast().write(command);
        });
    }

    private void onRequestLoadBackFile(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode == RESULT_OK) {
            Uri uri = data.getData();
            String realPath = FilePathUtils.getPath(this, uri);
//            Log.d(LOG_TAG,realPath);
//            ArrayList<String> args = new ArrayList<>();
//            args.add("-zxf");
//            args.add(realPath);
//            args.add("-C");
//            args.add(TERMUX_FILES_DIR_PATH);
//            args.add("--recursive-unlink");
//            args.add("--preserve-permissions");
//            CommandUtils.exec(this, "tar", args);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    File file = new File(getFilesDir().getAbsolutePath() + File.separator + "home" + File.separator + "storage");
                    String command = "termux-setup-storage;sleep 5s;tar -zxf " + realPath + " -C " + TERMUX_FILES_DIR_PATH + " --recursive-unlink" + " --preserve-permissions && exit \n";
                    if (file.exists()) {
                        command = "tar -zxf " + realPath + " -C " + TERMUX_FILES_DIR_PATH + " --recursive-unlink" + " --preserve-permissions && exit \n";
                    }
                    mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast().write(command);
                }
            });
            showTerminalSurface();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: " + Arrays.toString(permissions) + ", grantResults: " + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }


    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }


    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }


    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }


    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private void stopXserver(){
        final AlertDialog.Builder b = new AlertDialog.Builder(this );
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.stop_desktop_title);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            openX11Preferences(false);
            LoriePreferences.handler.postDelayed(() -> {
                getLorieViewRuntime().setX11DisplayConnected(false);
                CommandUtils.exec(this, "stopserver", null);
            }, 500);
            getLorieViewRuntime().setX11DisplayConnected(false);
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }
}
