package com.termux.ui.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import com.termux.ui.R;
import com.termux.ui.activity.DesktopActivity;
import com.termux.ui.api.DesktopArchiveTasks;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the archive stream independently of Activity lifetime. Never replays interrupted jobs. */
public final class DesktopArchiveForegroundService extends Service {
    private static final String CHANNEL = "desktop_archives";
    private static final int NOTIFICATION = 23104;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    // These counters are owned by the main thread, including worker completion callbacks.
    private int latestStartId;
    private int pendingJobs;
    private volatile boolean destroyed;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.desktop_title), NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(NOTIFICATION, notification(getString(R.string.desktop_archive_preparing), true));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String task = intent == null ? null : intent.getStringExtra("taskId");
        if (task == null) {
            if (pendingJobs == 0) stopSelf(startId);
            return START_NOT_STICKY;
        }
        pendingJobs++;
        worker.execute(() -> {
            try {
                // The manager validates the current owner. Queue duplicates as no-ops there,
                // rather than dropping a cleanup retry that deliberately reuses its task ID.
                new DesktopArchiveTasks(this).execute(task, snapshot -> {
                    if (!destroyed) {
                        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,
                            notification(label(snapshot), snapshot.status == DesktopArchiveTasks.Status.RUNNING));
                    }
                });
            } finally {
                handler.post(() -> {
                    pendingJobs--;
                    if (!destroyed && pendingJobs == 0) stopSelf(latestStartId);
                });
            }
        });
        return START_NOT_STICKY;
    }

    private String label(DesktopArchiveTasks.Snapshot snapshot) {
        int id = getResources().getIdentifier(snapshot.message, "string", getPackageName());
        return id == 0 ? snapshot.message : getString(id);
    }

    private Notification notification(String message, boolean busy) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        PendingIntent target = PendingIntent.getActivity(this, 0,
            DesktopActivity.createIntent(this), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder.setSmallIcon(R.drawable.local_games_ic_component)
            .setContentTitle(getString(R.string.desktop_title)).setContentText(message)
            .setContentIntent(target).setOnlyAlertOnce(true).setOngoing(busy)
            .setCategory(Notification.CATEGORY_PROGRESS).setProgress(0, 0, busy).build();
    }

    @Override public void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
