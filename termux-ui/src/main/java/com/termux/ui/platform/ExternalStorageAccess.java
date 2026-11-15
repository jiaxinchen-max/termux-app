package com.termux.ui.platform;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.termux.shared.android.PermissionUtils;
import com.termux.ui.R;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** One permission round trip shared by all external-storage operations in the UI. */
public final class ExternalStorageAccess {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Pending> PENDING = new ArrayList<>();
    private static boolean requesting;
    private static boolean prepared;
    private ExternalStorageAccess() { }

    public static boolean hasPermission(Context context) {
        return PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(context, -1, false);
    }

    /** Services/providers never start an unsolicited Activity from a background thread. */
    public static void requirePermission(Context context) throws IOException {
        if (!hasPermission(context)) throw new IOException(context.getString(
            R.string.external_storage_permission_required,
            android.os.Environment.getExternalStorageDirectory().getAbsolutePath()));
    }

    public static void ensure(Activity activity, Runnable operation) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> ensure(activity, operation));
            return;
        }
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (!requesting && prepared && hasPermission(activity) && linksPresent(activity)) {
            operation.run();
            return;
        }
        PENDING.add(new Pending(activity, operation));
        if (requesting) return;
        requesting = true;
        try { activity.startActivity(new Intent(activity, ExternalStoragePermissionActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
        catch (RuntimeException error) {
            complete(false);
            android.widget.Toast.makeText(activity, error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    static void complete(boolean success) {
        prepared = success;
        List<Pending> pending = new ArrayList<>(PENDING);
        PENDING.clear();
        requesting = false;
        if (success) for (Pending request : pending) {
            Activity owner = request.owner.get();
            if (owner != null && !owner.isFinishing() && !owner.isDestroyed()) {
                try { request.operation.run(); }
                catch (RuntimeException error) {
                    android.widget.Toast.makeText(owner, error.toString(), android.widget.Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    static void markRequesting() { requesting = true; }

    /** Re-probe real shared-directory access on each fresh UI entry. */
    public static void checkOnEntry(Activity activity) {
        prepared = false;
        ensure(activity, () -> {});
    }

    private static boolean linksPresent(Context context) {
        return SharedStorageSetup.linksPresent(context);
    }

    /** A background task waits for an explicit notification tap instead of opening UI over another app. */
    public static boolean deferServiceIfNeeded(Context context, Intent command) {
        if (hasPermission(context)) return false;
        if (command == null) return true;
        String channel = "shared_storage_permission";
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(
            channel, context.getString(R.string.storage_access_title), NotificationManager.IMPORTANCE_DEFAULT));
        Intent approval = serviceApprovalIntent(context, command);
        // Distinguish different pending commands; the complete explicit service intent survives process death.
        String key = command.getComponent() + ":" + command.getAction() + ":" + command.toUri(0);
        approval.setData(android.net.Uri.parse("termux-storage:approve/" + android.net.Uri.encode(key)));
        PendingIntent target = PendingIntent.getActivity(context, key.hashCode(), approval,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(context, channel) : new Notification.Builder(context);
        manager.notify(key.hashCode(), builder.setSmallIcon(R.drawable.local_games_ic_folder)
            .setContentTitle(context.getString(R.string.storage_access_title))
            .setContentText(context.getString(R.string.storage_access_waiting))
            .setContentIntent(target).setAutoCancel(true).build());
        return true;
    }

    /** A notification command enters the permission UI before dispatching its private service. */
    public static Intent serviceApprovalIntent(Context context, Intent service) {
        return new Intent(context, ExternalStoragePermissionActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ExternalStoragePermissionActivity.EXTRA_SERVICE, service);
    }

    static void startService(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private static final class Pending {
        final WeakReference<Activity> owner;
        final Runnable operation;
        Pending(Activity owner, Runnable operation) {
            this.owner = new WeakReference<>(owner);
            this.operation = operation;
        }
    }
}
