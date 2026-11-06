package com.termux.ui.desktop.activity;

import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.ui.R;
import com.termux.ui.activity.FrostedDialogBuilder;
import com.termux.ui.activity.PrefixSetupConsoleDialog;
import com.termux.ui.desktop.api.DesktopTasks;
import com.termux.ui.desktop.api.DesktopUsbStorageTasks;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopSessionState;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only USB management; all authorization remains with the Android USB permission dialog. */
final class DesktopUsbStorageDialog {
    private final AppCompatActivity activity;
    private final DesktopTasks desktops;
    private final DesktopUsbStorageTasks usb;
    private final Runnable changed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LinearLayout body, devices;
    private final CheckBox enabled;
    private final TextView status;
    private final Button install, refresh;
    private AlertDialog dialog;
    private DesktopDistro distro;
    private boolean closed, busy, rendering;
    private final Runnable refreshTick = this::reload;

    static void show(AppCompatActivity activity, DesktopTasks desktops, DesktopDistro distro, Runnable changed) {
        new DesktopUsbStorageDialog(activity, desktops, distro, changed).dialog.show();
    }
    private DesktopUsbStorageDialog(AppCompatActivity activity, DesktopTasks desktops,
                                    DesktopDistro distro, Runnable changed) {
        this.activity = activity; this.desktops = desktops; this.distro = distro; this.changed = changed;
        usb = new DesktopUsbStorageTasks(activity);
        body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20); body.setPadding(padding, dp(8), padding, padding);
        text(body, activity.getString(R.string.desktop_usb_scope));
        enabled = new CheckBox(activity); enabled.setText(R.string.desktop_usb_enable_read_only); body.addView(enabled);
        if (!DesktopUsbStorageTasks.supported(distro)) text(body, activity.getString(R.string.desktop_usb_plasma_unsupported));
        status = text(body, activity.getString(R.string.desktop_usb_loading));
        install = button(body, R.string.desktop_usb_install, () -> work(() -> {
            String taskId = desktops.install(this.distro);
            activity.runOnUiThread(() -> {
                if (usable()) {
                    dialog.dismiss();
                    PrefixSetupConsoleDialog.show(activity, taskId);
                    changed.run();
                }
            });
        }));
        refresh = button(body, R.string.desktop_usb_refresh, this::reload);
        devices = new LinearLayout(activity); devices.setOrientation(LinearLayout.VERTICAL); body.addView(devices);
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        dialog = new FrostedDialogBuilder(activity).setTitle(R.string.desktop_usb_title).setView(scroll)
            .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnDismissListener(ignored -> { closed = true; handler.removeCallbacks(refreshTick); io.shutdown(); });
        dialog.setOnShowListener(ignored -> reload());
        enabled.setOnCheckedChangeListener((view, checked) -> {
            if (rendering) return;
            work(() -> {
                usb.setEnabled(this.distro, checked);
                this.distro = this.distro.withUsbStorage(checked ? DesktopUsbStorageMode.READ_ONLY : DesktopUsbStorageMode.OFF);
                activity.runOnUiThread(changed);
            });
        });
    }
    private boolean usable() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private void reload() {
        handler.removeCallbacks(refreshTick);
        if (!usable() || busy) return;
        busy = true; refresh.setEnabled(false);
        io.execute(() -> {
            DesktopUsbStorageTasks.Dependencies dependencies = usb.dependencies(distro);
            DesktopSessionState state = desktops.state(distro);
            DesktopUsbStorageTasks.Health health = null;
            String error = null;
            if (DesktopUsbStorageTasks.supported(distro) && distro.getUsbStorage() == DesktopUsbStorageMode.READ_ONLY
                && state == DesktopSessionState.RUNNING) {
                try { health = usb.health(distro); }
                catch (Exception failure) {
                    error = details(failure);
                    String diagnostics = usb.diagnostics(distro);
                    if (diagnostics != null && !diagnostics.isEmpty()) error += "\n" + diagnostics;
                }
            } else if (distro.getUsbStorage() == DesktopUsbStorageMode.READ_ONLY
                && state == DesktopSessionState.FAILED) {
                String diagnostics = usb.diagnostics(distro);
                if (diagnostics != null && !diagnostics.isEmpty()) error = diagnostics;
            }
            DesktopUsbStorageTasks.Health snapshot = health;
            String failure = error;
            activity.runOnUiThread(() -> {
                if (!usable()) return;
                busy = false;
                render(dependencies, state, snapshot, failure);
                handler.postDelayed(refreshTick, 3000);
            });
        });
    }
    private void render(DesktopUsbStorageTasks.Dependencies dependencies, DesktopSessionState state,
                        DesktopUsbStorageTasks.Health health, String error) {
        boolean supported = DesktopUsbStorageTasks.supported(distro);
        boolean active = state.isActive();
        boolean on = distro.getUsbStorage() == DesktopUsbStorageMode.READ_ONLY;
        rendering = true; enabled.setChecked(on); enabled.setEnabled(supported && !active && !busy); rendering = false;
        install.setEnabled(supported && on && !active && !busy); refresh.setEnabled(!busy);
        StringBuilder summary = new StringBuilder();
        if (state == DesktopSessionState.STOPPING)
            summary.append(activity.getString(R.string.desktop_state_stopping)).append('\n');
        if (active) summary.append(activity.getString(R.string.desktop_usb_stop_to_change)).append('\n');
        if (dependencies.api == DesktopUsbStorageTasks.ApiState.UNAVAILABLE)
            summary.append(activity.getString(R.string.desktop_usb_api_missing)).append('\n');
        if (!dependencies.missing.isEmpty()) summary.append(activity.getString(R.string.desktop_usb_dependencies_missing,
            android.text.TextUtils.join(", ", dependencies.missing))).append('\n');
        else if (dependencies.ready()) summary.append(activity.getString(R.string.desktop_usb_dependencies_ready)).append('\n');
        if (!on) summary.append(activity.getString(R.string.desktop_usb_off));
        else if (error != null) summary.append(activity.getString(R.string.desktop_usb_bridge_error, error));
        else if (!active) summary.append(activity.getString(R.string.desktop_usb_start_required));
        else if (health != null && health.enumerationError != null)
            summary.append(activity.getString(R.string.desktop_usb_enumeration_error, health.enumerationError));
        else if (health != null) summary.append(activity.getString(R.string.desktop_usb_connected));
        status.setText(summary.toString());
        devices.removeAllViews();
        if (health == null) return;
        if (health.devices.isEmpty() && health.enumerationError == null)
            text(devices, activity.getString(R.string.desktop_usb_no_storage));
        for (DesktopUsbStorageTasks.Device device : health.devices) {
            text(devices, device.name + "\n" + device.path + "\n" + stateLabel(device.status)
                + (device.error.isEmpty() ? "" : "\n" + device.error));
            if ("permission-required".equals(device.status)) {
                deviceButton(device, R.string.desktop_usb_authorize, "authorize");
            } else if (!"ejected".equals(device.status)) {
                if ("mounted".equals(device.status)) deviceButton(device, R.string.desktop_usb_unmount, "unmount");
                else deviceButton(device, R.string.desktop_usb_mount, "mount");
                deviceButton(device, R.string.desktop_usb_eject, "eject");
            }
        }
    }
    private void deviceButton(DesktopUsbStorageTasks.Device device, int label, String action) {
        button(devices, label, () -> work(() -> usb.action(distro, action, device.path)));
    }
    private String stateLabel(String state) {
        int resource;
        switch (state) {
            case "permission-required": resource = R.string.desktop_usb_permission_required; break;
            case "mounted": resource = R.string.desktop_usb_mounted; break;
            case "mounting": resource = R.string.desktop_usb_mounting; break;
            case "ejected": resource = R.string.desktop_usb_ejected; break;
            case "available": resource = R.string.desktop_usb_available; break;
            default: resource = R.string.desktop_usb_device_error;
        }
        return activity.getString(resource);
    }
    private interface Work { void run() throws Exception; }
    private void work(Work operation) {
        if (!usable() || busy) return;
        busy = true; handler.removeCallbacks(refreshTick);
        enabled.setEnabled(false); install.setEnabled(false); refresh.setEnabled(false);
        for (int index = 0; index < devices.getChildCount(); index++) devices.getChildAt(index).setEnabled(false);
        status.setText(R.string.desktop_usb_working);
        io.execute(() -> {
            String failure = null;
            try { operation.run(); } catch (Exception error) { failure = details(error); }
            String result = failure;
            activity.runOnUiThread(() -> {
                if (!usable()) return;
                busy = false;
                if (result != null) new FrostedDialogBuilder(activity).setTitle(R.string.desktop_usb_error)
                    .setMessage(result).setPositiveButton(android.R.string.ok, null).show();
                reload();
            });
        });
    }
    private String details(Exception error) {
        String message = error.getMessage();
        if ("desktop_stopping".equals(message)) return activity.getString(R.string.desktop_state_stopping);
        if ("desktop_usb_storage_requires_gio_sidebar".equals(message)
            || "desktop_usb_environment_unsupported".equals(message))
            return activity.getString(R.string.desktop_usb_plasma_unsupported);
        return message == null ? error.getClass().getSimpleName() : message;
    }
    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(activity); view.setText(value); view.setTextIsSelectable(true);
        view.setPadding(0, dp(8), 0, dp(8)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, int label, Runnable action) {
        Button view = new Button(activity); view.setText(label); view.setAllCaps(false);
        view.setOnClickListener(ignored -> action.run()); parent.addView(view); return view;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
