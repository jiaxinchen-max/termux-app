package com.termux.app.localgames;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.termux.ui.api.PendingTasksOnAppExit;

import java.io.File;
import java.util.List;

/** Separate process: stops sticky services before terminating all same-UID runtime processes. */
public final class TermuxExitActivity extends Activity {
    private static final String TAG = "TermuxAppExit";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        stopOwnedServices();
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            if (task.getTaskInfo().id != getTaskId()) task.finishAndRemoveTask();
        }
        new Thread(this::cleanup, "app-exit").start();
    }

    private void stopOwnedServices() {
        PackageManager packages = getPackageManager();
        String[] names = packages.getPackagesForUid(Process.myUid());
        if (names == null) throw new IllegalStateException("No packages for application UID");
        for (String name : names) {
            try {
                PackageInfo info = packages.getPackageInfo(name, PackageManager.GET_SERVICES);
                if (info.services == null) continue;
                for (ServiceInfo service : info.services) {
                    stopService(new Intent().setComponent(new ComponentName(name, service.name)));
                }
            } catch (PackageManager.NameNotFoundException | SecurityException error) {
                Log.e(TAG, "Cannot stop services in " + name, error);
            }
        }
    }

    private void cleanup() {
        try {
            // Let onDestroy release bindings, wake locks and foreground notifications.
            SystemClock.sleep(300);
            File proc = new File("/proc");
            signal(proc, 15);
            SystemClock.sleep(200);
            // Repeat to include children spawned while their parents were shutting down.
            boolean stopped = false;
            for (int attempt = 0; attempt < 15; attempt++) {
                List<AppUidProcesses.Entry> remaining = AppUidProcesses.scan(proc, Process.myUid(), Process.myPid());
                if (remaining.isEmpty()) { stopped = true; break; }
                for (AppUidProcesses.Entry entry : remaining) {
                    signal(proc, entry, 9);
                }
                SystemClock.sleep(100);
            }
            if (!stopped) throw new IllegalStateException("Application processes still running after cleanup");
            // A closing page may have queued one last service start before its process died.
            stopOwnedServices();
            // No surviving writers can overwrite the cancelled task records.
            PendingTasksOnAppExit.cancel(getFilesDir());
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancelAll();
            Log.i(TAG, "All application services, runtime processes and pending tasks stopped");
        } catch (Exception error) {
            Log.e(TAG, "Application exit cleanup failed", error);
        } finally {
            runOnUiThread(() -> {
                finishAndRemoveTask();
                Process.killProcess(Process.myPid());
            });
        }
    }

    private void signal(File proc, int number) throws java.io.IOException, ErrnoException {
        for (AppUidProcesses.Entry entry : AppUidProcesses.scan(proc, Process.myUid(), Process.myPid())) {
            signal(proc, entry, number);
        }
    }

    private void signal(File proc, AppUidProcesses.Entry entry, int number) throws ErrnoException {
        if (!AppUidProcesses.matches(proc, entry, Process.myUid())) return;
        try {
            Os.kill(entry.pid, number);
        } catch (ErrnoException error) {
            if (error.errno != OsConstants.ESRCH) throw error;
        }
    }
}
