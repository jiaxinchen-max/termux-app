package com.termux.app.terminal;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.FragmentManager;

import com.termux.app.TermuxActivity;
import com.termux.x11.LorieViewRuntimeApi;
import com.termux.x11.Prefs;

/**
 * Implements {@link LorieViewRuntimeApi.Host} as a separate client class
 * to reduce the size of {@link TermuxActivity}.
 *
 * TermuxActivity retains the Host interface for backward compatibility
 * (required by LoriePreferences which casts the Activity to Host),
 * but delegates all methods to this client.
 */
public class TermuxX11HostClient implements LorieViewRuntimeApi.Host {

    private final TermuxActivity mActivity;

    public TermuxX11HostClient(TermuxActivity activity) {
        mActivity = activity;
    }

    @NonNull
    @Override
    public Activity getActivity() {
        return mActivity;
    }

    @NonNull
    @Override
    public FragmentManager getSupportFragmentManager() {
        return mActivity.getSupportFragmentManager();
    }

    @Override
    public void openX11Preferences(boolean open) {
        if (open) {
            if (mActivity.getMainSurfaceController() != null)
                mActivity.getMainSurfaceController().openEndDrawerExplicitly();
            else
                mActivity.getDrawer().openDrawer(GravityCompat.END);
        } else {
            mActivity.getDrawer().closeDrawer(GravityCompat.END);
        }
    }

    @Override
    public void requestX11Focus(boolean focused) {
        if (mActivity.getLorieViewRuntime() != null)
            mActivity.getLorieViewRuntime().requestX11Focus(focused);
    }

    @Override
    public void openSoftKeyboard() {
        if (mActivity.getLorieViewRuntime() != null)
            mActivity.getLorieViewRuntime().openSoftKeyboard();
    }

    @Override
    public void showProcessManager() {
        if (mActivity.getLorieViewRuntime() != null)
            mActivity.getLorieViewRuntime().showProcessManager();
    }

    @NonNull
    @Override
    public Prefs getX11Prefs() {
        return mActivity.getLorieViewRuntime().getX11Prefs();
    }

    @Nullable
    @Override
    public LorieViewRuntimeApi.ActivityIntegration getX11ActivityIntegration() {
        return mActivity.getLorieViewRuntime() != null
            ? mActivity.getLorieViewRuntime().getX11ActivityIntegration() : null;
    }

    @Override
    public void openPreference(boolean open) {
        openX11Preferences(open);
    }

    @Override
    public void showInputControlsDialog() {
        if (mActivity.getLorieViewRuntime() != null)
            mActivity.getLorieViewRuntime().showInputControlsDialog();
    }

    @Override
    public void installX11ServerBridge() {
        if (mActivity.getLorieViewRuntime() != null)
            mActivity.getLorieViewRuntime().installX11ServerBridge();
    }

    @Override
    public void stopDesktop() {
        mActivity.stopXserver();
    }

    @Override
    public void onX11PreferenceChanged(String key) {
        if (mActivity.getLorieViewRuntime() != null)
            mActivity.getLorieViewRuntime().applyX11PreferenceChange(key);
    }
}
