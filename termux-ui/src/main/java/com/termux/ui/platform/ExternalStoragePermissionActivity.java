package com.termux.ui.platform;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.termux.shared.android.PermissionUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hosts the system storage permission UI while the caller's operation waits. Not exported. */
public final class ExternalStoragePermissionActivity extends Activity {
    static final String EXTRA_SERVICE = "storage_service";
    private static final int REQUEST = 847;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private boolean preparing, completed;
    private final java.util.ArrayList<Intent> services = new java.util.ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ExternalStorageAccess.markRequesting();
        if (state != null) {
            java.util.ArrayList<Intent> restored = state.getParcelableArrayList("services");
            if (restored != null) services.addAll(restored);
        } else addService(getIntent());
        if (state != null && state.getBoolean("requested") && !ExternalStorageAccess.hasPermission(this)) return;
        if (ExternalStorageAccess.hasPermission(this)) prepare();
        else PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(this, REQUEST, true);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("requested", !completed);
        state.putParcelableArrayList("services", services);
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        addService(intent);
    }

    private void addService(Intent intent) {
        Intent service = intent.getParcelableExtra(EXTRA_SERVICE);
        if (service == null) return;
        for (Intent queued : services) if (queued.toUri(0).equals(service.toUri(0))) return;
        services.add(service);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST) permissionReturned();
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQUEST) permissionReturned();
    }

    private void permissionReturned() {
        if (ExternalStorageAccess.hasPermission(this)) prepare();
        else finishRequest(false, getString(com.termux.ui.R.string.external_storage_permission_required,
            android.os.Environment.getExternalStorageDirectory().getAbsolutePath()));
    }

    private void prepare() {
        if (preparing || completed) return;
        preparing = true;
        IO.execute(() -> {
            String failure = null;
            try { SharedStorageSetup.prepare(getApplicationContext()); }
            catch (Exception error) {
                failure = error.getMessage() == null ? error.toString() : error.getMessage();
                String conflict = "storage_link_conflict: ";
                if (failure.startsWith(conflict)) failure = getString(com.termux.ui.R.string.storage_link_conflict,
                    failure.substring(conflict.length()));
            }
            String message = failure;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isDestroyed()) finishRequest(message == null, message);
            });
        });
    }

    private void finishRequest(boolean success, String message) {
        if (completed) return;
        completed = true;
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (success) for (Intent service : services) {
            if (service.getComponent() == null || !getPackageName().equals(service.getComponent().getPackageName())) continue;
            try { ExternalStorageAccess.startService(this, service); }
            catch (RuntimeException error) { Toast.makeText(this, error.toString(), Toast.LENGTH_LONG).show(); }
        }
        finish();
        ExternalStorageAccess.complete(success);
    }

    @Override public void onBackPressed() { finishRequest(false, null); }

    @Override protected void onDestroy() {
        if (isFinishing() && !completed) ExternalStorageAccess.complete(false);
        super.onDestroy();
    }
}
