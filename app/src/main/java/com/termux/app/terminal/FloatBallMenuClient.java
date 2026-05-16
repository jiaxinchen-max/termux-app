package com.termux.app.terminal;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.floatball.FloatBallManager;
import com.termux.floatball.menu.FloatMenuCfg;
import com.termux.floatball.menu.MenuItem;
import com.termux.floatball.permission.FloatPermissionManager;
import com.termux.floatball.utils.DensityUtil;
import com.termux.floatball.widget.FloatBallCfg;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class FloatBallMenuClient {
    private static final long CPU_LOAD_UPDATE_INTERVAL_MS = 2000;
    private static final int[] CPU_LOAD_COLORS = {
        0xff9bcf45,
        0xffb8c947,
        0xffd5bf45,
        0xffe7a941,
        0xffef8b3c,
        0xffe96b39,
        0xffd94b38,
        0xffbd3038,
        0xff9a2230,
        0xff72151f
    };

    private FloatBallManager mFloatballManager;
    private FloatPermissionManager mFloatPermissionManager;
    private ActivityLifeCycleListener mActivityLifeCycleListener = new ActivityLifeCycleListener();
    private int resumed;
    private TermuxActivity mTermuxActivity;
    private boolean mAppNotOnFront = false;
    private boolean mShowKeyboard = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final CpuLoadSampler mCpuLoadSampler = new CpuLoadSampler();
    private int mLastCpuUsagePercent = -1;
    private final Runnable mCpuLoadIconUpdater = new Runnable() {
        @Override
        public void run() {
            updateFloatBallLoadColor();
            mHandler.postDelayed(this, CPU_LOAD_UPDATE_INTERVAL_MS);
        }
    };

    private FloatBallMenuClient() {
    }

    public FloatBallMenuClient(TermuxActivity termuxActivity) {
        mTermuxActivity = termuxActivity;
    }

    public void onCreate() {
        init();
        mFloatballManager.show();
        startCpuLoadIconUpdater();
        //5 set float ball click handler
        if (mFloatballManager.getMenuItemSize() == 0) {
            toast(mTermuxActivity.getString(R.string.add_menu_item));
        } else {
            mFloatballManager.setOnFloatBallClickListener(() -> {
                if (mAppNotOnFront) {
                    PackageManager packageManager = mTermuxActivity.getPackageManager();
                    Intent intent = packageManager.getLaunchIntentForPackage("com.termux");
                    if (intent != null) {
//                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        mTermuxActivity.startActivity(intent);
                        toast(mTermuxActivity.getString(R.string.raise_termux_app));
                    }
                }
            });
        }
        //     6 if only float ball within app, register it to Application(out data, actually, it is enough within activity )
        mTermuxActivity.getApplication().registerActivityLifecycleCallbacks(mActivityLifeCycleListener);
    }

    public void onAttachedToWindow() {
        try {
            mFloatballManager.show();
            mFloatballManager.onFloatBallClick();
        } catch (RuntimeException e) {
            e.printStackTrace();
            toast(mTermuxActivity.getString(R.string.apply_display_over_other_app_permission));
        }

    }

    public void onDetachedFromWindow() {
        mFloatballManager.hide();
    }

    private void init() {
//      1 set position of float ball, set size, icon and drawable
        int ballSize = DensityUtil.dip2px(mTermuxActivity, 42);
        Drawable ballIcon = createFloatBallIcon(0);
//      different config below
//      FloatBallCfg ballCfg = new FloatBallCfg(ballSize, ballIcon);
//      FloatBallCfg ballCfg = new FloatBallCfg(ballSize, ballIcon, FloatBallCfg.Gravity.LEFT_CENTER,false);
//      FloatBallCfg ballCfg = new FloatBallCfg(ballSize, ballIcon, FloatBallCfg.Gravity.LEFT_BOTTOM, -100);
//      FloatBallCfg ballCfg = new FloatBallCfg(ballSize, ballIcon, FloatBallCfg.Gravity.RIGHT_TOP, 100);
        FloatBallCfg ballCfg = new FloatBallCfg(ballSize, ballIcon, FloatBallCfg.Gravity.RIGHT_CENTER);
//      set float ball weather hide
        ballCfg.setHideHalfLater(true);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mTermuxActivity);
        boolean floatBallOverOtherApp = preferences.getBoolean("enableGlobalFloatBallMenu", false);
        Context ctx = mTermuxActivity;
        if (floatBallOverOtherApp) {
            ctx = mTermuxActivity.getApplicationContext();
        }
        //2 display float ball menu
        //2.1 init float ball menu config, every size of menu item and number of item
        int menuSize = DensityUtil.dip2px(mTermuxActivity, 124);
        int menuItemSize = DensityUtil.dip2px(mTermuxActivity, 28);
        FloatMenuCfg menuCfg = new FloatMenuCfg(menuSize, menuItemSize);
        //3 create float ball Manager
        mFloatballManager = new FloatBallManager(ctx, ballCfg, menuCfg);
        addFloatMenuItem();
        mFloatballManager.setFloatBallOverOtherApp(floatBallOverOtherApp);
        if (floatBallOverOtherApp) {
            setFloatPermission();
        }
    }

    private void setFloatPermission() {
        // set 'display over other app' permission of float bal menu
        //once permission, float ball never show
        mFloatPermissionManager = new FloatPermissionManager();
        mFloatballManager.setPermission(new FloatBallManager.IFloatBallPermission() {
            @Override
            public boolean onRequestFloatBallPermission() {
                requestFloatBallPermission(mTermuxActivity);
                return true;
            }

            @Override
            public boolean hasFloatBallPermission(Context context) {
                return mFloatPermissionManager.checkPermission(context);
            }

            @Override
            public void requestFloatBallPermission(Activity activity) {
                mFloatPermissionManager.applyPermission(activity);
            }

        });
    }

    public class ActivityLifeCycleListener implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            ++resumed;
            setFloatBallVisible(true);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            --resumed;
            if (!isApplicationInForeground()) {
                setFloatBallVisible(false);
            }
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }
    }

    private void toast(String msg) {
        Toast.makeText(mTermuxActivity, msg, Toast.LENGTH_SHORT).show();
    }

    private void addFloatMenuItem() {
        MenuItem terminalItem = new MenuItem(mTermuxActivity.getDrawable(R.drawable.icon_menu_start_terminal_shape)) {
            @Override
            public void action() {
                mTermuxActivity.showTerminalSurface();
                toast(mTermuxActivity.getString(R.string.open_terminal));
                mFloatballManager.closeMenu();
            }
        };
        MenuItem stopItem = new MenuItem(mTermuxActivity.getDrawable(R.drawable.icon_menu_kill_current_process_shape)) {
            @Override
            public void action() {
                MainSurfaceController surfaceController = mTermuxActivity.getMainSurfaceController();
                if (surfaceController != null && !surfaceController.isDisplayMode()) {
                    mTermuxActivity.showDisplaySurface();
                } else {
                    mTermuxActivity.stopDesktop();
                    toast(mTermuxActivity.getString(R.string.terminate_current_process));
                }
                mFloatballManager.closeMenu();
            }
        };
        MenuItem gamePadItem = new MenuItem(mTermuxActivity.getDrawable(R.drawable.icon_menu_game_pad_shape)) {
            @Override
            public void action() {
                mTermuxActivity.showInputControlsDialog();
                mFloatballManager.closeMenu();
            }
        };
        MenuItem orientationItem = new MenuItem(mTermuxActivity.getDrawable(R.drawable.icon_menu_toggle_orientation_shape)) {
            @Override
            public void action() {
                String orientation = mTermuxActivity.toggleX11ForceOrientation();
                if (orientation != null)
                    toast(orientation);
                mFloatballManager.closeMenu();
            }
        };
        MenuItem keyboardItem = new MenuItem(mTermuxActivity.getDrawable(R.drawable.icon_menu_show_keyboard_shape)) {
            @Override
            public void action() {
                if (mShowKeyboard) {
                    mDrawable = mTermuxActivity.getDrawable(R.drawable.icon_menu_show_keyboard_open_shape);
                } else {
                    mDrawable = mTermuxActivity.getDrawable(R.drawable.icon_menu_show_keyboard_shape);
                }
                mShowKeyboard = !mShowKeyboard;
                mTermuxActivity.openSoftKeyboard();
                mFloatballManager.closeMenu();
            }
        };
        MenuItem taskManagerItem = new MenuItem(mTermuxActivity.getDrawable(R.drawable.icon_menu_show_task_manager_shape)) {
            @Override
            public void action() {
                mTermuxActivity.showProcessManagerDialog();
                toast(mTermuxActivity.getString(com.termux.x11.R.string.task_manager));
                mFloatballManager.closeMenu();
            }
        };
        MenuItem settingItem = new MenuItem(mTermuxActivity.getDrawable(R.drawable.icon_menu_show_setting_shape)) {
            @Override
            public void action() {
                mTermuxActivity.openX11Preferences(true);
                toast(mTermuxActivity.getString(com.termux.x11.R.string.open_x11_settings));
                mFloatballManager.closeMenu();
            }
        };
        mFloatballManager.addMenuItem(terminalItem)
            .addMenuItem(stopItem)
            .addMenuItem(keyboardItem)
            .addMenuItem(gamePadItem)
            .addMenuItem(orientationItem)
            .addMenuItem(taskManagerItem)
            .addMenuItem(settingItem)
            .buildMenu();
    }

    private void setFloatBallVisible(boolean visible) {
        if (visible) {
//            mFloatballManager.show();
            mAppNotOnFront = false;
        } else {
//            mFloatballManager.hide();
            mAppNotOnFront = true;
        }
    }

    public boolean isApplicationInForeground() {
        return resumed > 0;
    }

    public void onDestroy() {
        stopCpuLoadIconUpdater();
        onDetachedFromWindow();
        //unregister ActivityLifeCycle listener once register it, in case of memory leak
        mTermuxActivity.getApplication().unregisterActivityLifecycleCallbacks(mActivityLifeCycleListener);
    }

    public boolean isGlobalFloatBallMenu() {
        return mFloatballManager.isFloatBallOverOtherApp();
    }

    public boolean isFloatMenuShowing() {
        return mFloatballManager != null && mFloatballManager.isFloatMenuShowing();
    }

    private void startCpuLoadIconUpdater() {
        mLastCpuUsagePercent = -1;
        mHandler.removeCallbacks(mCpuLoadIconUpdater);
        mCpuLoadIconUpdater.run();
    }

    private void stopCpuLoadIconUpdater() {
        mHandler.removeCallbacks(mCpuLoadIconUpdater);
    }

    private void updateFloatBallLoadColor() {
        if (mFloatballManager == null)
            return;

        int usagePercent = mCpuLoadSampler.sampleUsagePercent();
        if (usagePercent == mLastCpuUsagePercent)
            return;

        mLastCpuUsagePercent = usagePercent;
        mFloatballManager.setFloatBallIcon(createFloatBallIcon(usagePercent));
    }

    private Drawable createFloatBallIcon(int usagePercent) {
        int level = Math.max(0, Math.min(CPU_LOAD_COLORS.length - 1, usagePercent / 10));
        return new CpuLoadDrawable(usagePercent, CPU_LOAD_COLORS[level]);
    }

    private static int darken(int color) {
        return Color.rgb(
            Math.max(0, (int) (Color.red(color) * 0.55f)),
            Math.max(0, (int) (Color.green(color) * 0.55f)),
            Math.max(0, (int) (Color.blue(color) * 0.55f)));
    }

    private static final class CpuLoadSampler {
        int sampleUsagePercent() {
            int numProcessors = Runtime.getRuntime().availableProcessors();
            if (numProcessors <= 0)
                return 0;

            float totalRatio = 0;
            int sampledProcessors = 0;
            for (int i = 0; i < numProcessors; i++) {
                long maxFreq = readLong(
                    "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq",
                    "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_max_freq");
                if (maxFreq <= 0)
                    continue;

                long minFreq = readLong(
                    "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_min_freq",
                    "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_min_freq");
                long currentFreq = readLong(
                    "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq",
                    "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_cur_freq");
                long availableFreqRange = maxFreq - minFreq;
                float ratio = currentFreq > 0 && availableFreqRange > 0
                    ? (currentFreq - minFreq) / (float) availableFreqRange
                    : 0;
                ratio = Math.max(0, Math.min(1.0f, ratio));
                totalRatio += ratio;
                sampledProcessors++;
            }

            if (sampledProcessors <= 0)
                return 0;

            return Math.max(0, Math.min(100, Math.round((totalRatio / sampledProcessors) * 100)));
        }

        private long readLong(String... paths) {
            for (String path : paths) {
                try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                    String line = reader.readLine();
                    if (line != null)
                        return Long.parseLong(line.trim());
                } catch (IOException | NumberFormatException e) {
                    // Try the next kernel-exposed cpufreq path.
                }
            }
            return 0;
        }
    }

    private static final class CpuLoadDrawable extends Drawable {
        private final int usagePercent;
        private final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        CpuLoadDrawable(int usagePercent, int color) {
            this.usagePercent = usagePercent;
            this.color = color;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float width = bounds.width();
            float height = bounds.height();
            float size = Math.min(width, height);
            float cx = bounds.left + width / 2f;
            float cy = bounds.top + height / 2f;
            float radius = size / 2f;
            int textColor = usagePercent >= 60 ? Color.WHITE : 0xff101510;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(color, 58));
            canvas.drawCircle(cx, cy, radius * 0.98f, paint);
            paint.setColor(withAlpha(color, 92));
            canvas.drawCircle(cx, cy, radius * 0.86f, paint);
            paint.setColor(withAlpha(0xff000000, 66));
            canvas.drawCircle(cx, cy, radius * 0.73f, paint);

            paint.setColor(color);
            canvas.drawCircle(cx, cy, radius * 0.68f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, size * 0.035f));
            paint.setColor(withAlpha(Color.WHITE, 105));
            canvas.drawCircle(cx, cy, radius * 0.58f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(textColor);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            String value = String.valueOf(usagePercent);
            float textSize = size * (value.length() >= 3 ? 0.32f : 0.40f);
            paint.setTextSize(textSize);
            Paint.FontMetrics fontMetrics = paint.getFontMetrics();
            float baseline = cy - (fontMetrics.ascent + fontMetrics.descent) / 2f;
            canvas.drawText(value, cx - size * 0.03f, baseline, paint);

            paint.setFakeBoldText(false);
            paint.setTextSize(size * 0.16f);
            canvas.drawText("%", cx + size * 0.22f, cy - size * 0.06f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, size * 0.025f));
            paint.setColor(withAlpha(darken(color), 150));
            rect.set(cx - radius * 0.67f, cy - radius * 0.67f, cx + radius * 0.67f, cy + radius * 0.67f);
            canvas.drawOval(rect, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        private static int withAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }
    }
}
