package com.termux.ui.wine.service;

import android.app.*;
import android.content.Intent;
import android.os.*;
import com.termux.ui.R;
import com.termux.ui.wine.api.WineCDriveBackupTasks;
import java.util.concurrent.*;

/** Foreground owner of C-drive archive I/O, independent of the menu/dialog lifetime. */
public final class WineCDriveBackupService extends Service {
    private static final String CHANNEL = "wine_cdrive_backup";
    private static final int NOTIFICATION = 23105;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pending, latestStart;
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
            .createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.wine_cdrive_title), NotificationManager.IMPORTANCE_LOW));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        startForeground(NOTIFICATION, builder.setSmallIcon(R.drawable.local_games_ic_folder)
            .setContentTitle(getString(R.string.wine_cdrive_title)).setContentText(getString(R.string.desktop_archive_preparing))
            .setOngoing(true).setProgress(0, 0, true).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStart = startId;
        String task = intent == null ? null : intent.getStringExtra("taskId");
        if (task == null) { if (pending == 0) stopSelf(startId); return START_NOT_STICKY; }
        pending++;
        worker.execute(() -> {
            try { new WineCDriveBackupTasks(this).execute(task); }
            finally { handler.post(() -> { if (--pending == 0) stopSelf(latestStart); }); }
        });
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
