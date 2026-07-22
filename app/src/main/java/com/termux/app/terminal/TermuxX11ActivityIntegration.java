package com.termux.app.terminal;

import android.app.Activity;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.app.TermuxActivity;
import com.termux.app.terminal.utils.CommandUtils;
import com.termux.app.terminal.utils.FileUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.x11.LoriePreferences;
import com.termux.x11.LorieViewRuntimeApi;
import com.termux.x11.controller.winhandler.ProcessInfo;

import java.io.File;
import java.util.List;

/**
 * Implements {@link LorieViewRuntimeApi.ActivityIntegration} as a separate client class
 * to reduce the size of {@link TermuxActivity}.
 *
 * Extracted from the anonymous inner class previously defined in TermuxActivity.onCreate().
 */
public class TermuxX11ActivityIntegration implements LorieViewRuntimeApi.ActivityIntegration {

    private final TermuxActivity mActivity;

    public TermuxX11ActivityIntegration(TermuxActivity activity) {
        mActivity = activity;
    }

    @Override
    public void onX11PreferenceSwitchChange(boolean isOpen) {
        mActivity.openX11Preferences(isOpen);
    }

    @Override
    public void releaseSlider(boolean open) {
        if (mActivity.getMainSurfaceController() != null
                && mActivity.getMainSurfaceController().isDisplayMode() && open) {
            mActivity.handleDisplaySidePanelUnlockBackRequest();
        } else if (mActivity.getMainSurfaceController() != null) {
            mActivity.getMainSurfaceController().restoreDrawerLockMode();
        } else {
            mActivity.getDrawer().setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END);
        }
    }

    @Override
    public void onChangeOrientation(int landscape) {
        mActivity.getLorieViewRuntime().reloadInputControlsProfiles(true);
    }

    @Override
    public void reInstallX11StartScript(Activity activity) {
        activity.runOnUiThread(() -> {
            FileUtils.copyAssetsFile2Phone(activity, "install", ".termux/tmp");
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux/tmp/install").setExecutable(true, true);
            FileUtils.copyAssetsFile2Phone(activity, "termux-x11-nightly-1.03.10-0-all.deb", ".termux/tmp");
            FileUtils.copyAssetsFile2Phone(activity, "xkeyboard-config_2.45_all.deb", ".termux/tmp");
            CommandUtils.execInPath(activity, "install", null, "/home/.termux/tmp/");
        });
    }

    @Override
    public void stopDesktop() {
        mActivity.stopXserver();
    }

    @Override
    public void openSoftwareKeyboard() {
        mActivity.getLorieViewRuntime().openSoftKeyboard();
    }

    @Override
    public void showProcessManager() {
        mActivity.getLorieViewRuntime().showProcessManager();
    }

    @Override
    public void changePreference(String key) {
        mActivity.getLorieViewRuntime().applyX11PreferenceChange(key);
    }

    @Override
    public List<ProcessInfo> collectProcessorInfo(String tag) {
        if ("1".equals(tag)) {
            return null;
        }
        return ProcessInfo.collectFromProc();
    }

    @Override
    public void setFloatBallMenu(boolean enableFloatBallMenu, boolean enableGlobalFloatBallMenu) {
        FloatBallMenuClient client = mActivity.getFloatBallMenuClient();
        if (client != null) {
            if (!enableFloatBallMenu) {
                client.onDestroy();
                mActivity.setFloatBallMenuClient(null);
            } else {
                if (enableGlobalFloatBallMenu != client.isGlobalFloatBallMenu()) {
                    client.onDestroy();
                    mActivity.setFloatBallMenuClient(null);
                    if (enableFloatBallMenu) {
                        LoriePreferences.handler.postDelayed(() -> {
                            FloatBallMenuClient newClient = new FloatBallMenuClient(mActivity);
                            newClient.onCreate();
                            mActivity.setFloatBallMenuClient(newClient);
                        }, 100);
                    }
                }
            }
        } else {
            if (enableFloatBallMenu) {
                FloatBallMenuClient newClient = new FloatBallMenuClient(mActivity);
                newClient.onCreate();
                mActivity.setFloatBallMenuClient(newClient);
            }
        }
        mActivity.lockDisplaySidePanels(false, 0);
        mActivity.updateDisplaySidePanelPolicy();
    }

    @Override
    public void onExitApp() {
        mActivity.handleDisplaySidePanelUnlockBackRequest();
    }
}
