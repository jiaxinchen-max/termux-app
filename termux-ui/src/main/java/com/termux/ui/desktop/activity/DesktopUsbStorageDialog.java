package com.termux.ui.desktop.activity;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.ui.R;
import com.termux.ui.activity.FrostedWindow;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.activity.PrefixSetupConsoleDialog;
import com.termux.ui.desktop.api.DesktopTasks;
import com.termux.ui.desktop.api.DesktopUsbStorageTasks;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopSessionState;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** USB storage management; all authorization remains with the Android USB permission dialog. */
final class DesktopUsbStorageDialog {
    private final AppCompatActivity activity;
    private final Context themedContext;
    private final DesktopTasks desktops;
    private final DesktopUsbStorageTasks usb;
    private final Runnable changed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LinearLayout body, devices;
    private final RadioGroup modes;
    private final RadioButton off, readOnly, readWrite;
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
        MaterialAlertDialogBuilder builder = solidBuilder();
        themedContext = builder.getContext();
        body = new LinearLayout(themedContext); body.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20); body.setPadding(padding, dp(8), padding, padding);
        text(body, activity.getString(R.string.desktop_usb_scope));
        modes = new RadioGroup(themedContext); body.addView(modes);
        off = mode(R.string.desktop_usb_mode_off);
        readOnly = mode(R.string.desktop_usb_enable_read_only);
        readWrite = mode(R.string.desktop_usb_enable_read_write);
        status = text(body, activity.getString(R.string.desktop_usb_loading));
        install = button(body, R.string.desktop_usb_install, () -> work(this::initializeDesktop));
        refresh = button(body, R.string.desktop_usb_refresh, this::reload);
        devices = new LinearLayout(themedContext); devices.setOrientation(LinearLayout.VERTICAL); body.addView(devices);
        ScrollView scroll = new ScrollView(themedContext); scroll.addView(body);
        dialog = builder.setTitle(R.string.desktop_usb_title).setView(scroll)
            .setPositiveButton(android.R.string.ok, null).create();
        FrostedWindow.applyScrim(dialog);
        setControlsEnabled(false);
        dialog.setOnDismissListener(ignored -> { closed = true; handler.removeCallbacks(refreshTick); io.shutdown(); });
        dialog.setOnShowListener(ignored -> reload());
        modes.setOnCheckedChangeListener((group, selected) -> {
            if (rendering || busy || closed) return;
            DesktopUsbStorageMode mode = selected == readWrite.getId() ? DesktopUsbStorageMode.READ_WRITE
                : selected == readOnly.getId() ? DesktopUsbStorageMode.READ_ONLY : DesktopUsbStorageMode.OFF;
            if (mode == this.distro.getUsbStorage()) return;
            if (mode == DesktopUsbStorageMode.READ_WRITE) {
                solidBuilder().setTitle(R.string.desktop_usb_enable_read_write)
                    .setMessage(R.string.desktop_usb_write_notice)
                    .setPositiveButton(android.R.string.ok, (d, which) -> work(() -> showTask(usb.setMode(this.distro, mode))))
                    .setNegativeButton(android.R.string.cancel, (d, which) -> reload())
                    .setOnCancelListener(d -> reload()).show();
            } else work(() -> showTask(usb.setMode(this.distro, mode)));
        });
    }
    private RadioButton mode(int label) {
        RadioButton option = new MaterialRadioButton(themedContext);
        option.setId(View.generateViewId()); option.setText(label); modes.addView(option);
        return option;
    }

    private boolean usable() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private MaterialAlertDialogBuilder solidBuilder() {
        return new MaterialAlertDialogBuilder(activity).setBackground(FrostedWindow.surface(
            activity, R.color.desktop_usb_dialog_surface, FrostedWindow.PANEL_RADIUS_DP));
    }
    private int unsupportedReason() {
        return R.string.desktop_usb_native_unsupported;
    }
    private void setControlsEnabled(boolean value) {
        off.setEnabled(value); readOnly.setEnabled(value);
        readWrite.setEnabled(value && distro.getRunMode() == com.termux.ui.desktop.domain.DesktopRunMode.PROOT);
        install.setEnabled(value); refresh.setEnabled(value);
        for (int index = 0; index < devices.getChildCount(); index++) devices.getChildAt(index).setEnabled(value);
    }
    private void reload() {
        handler.removeCallbacks(refreshTick);
        if (!usable() || busy) return;
        if (!DesktopUsbStorageTasks.supported(distro)) {
            setControlsEnabled(false);
            install.setVisibility(android.view.View.GONE);
            refresh.setVisibility(android.view.View.GONE);
            status.setText(unsupportedReason());
            return;
        }
        busy = true;
        setControlsEnabled(false);
        io.execute(() -> {
            DesktopUsbStorageTasks.Dependencies dependencies = null;
            DesktopSessionState state = null;
            DesktopUsbStorageTasks.Health health = null;
            String error = null;
            try {
                dependencies = usb.dependencies(distro);
                state = desktops.state(distro);
                if (distro.getUsbStorage() != DesktopUsbStorageMode.OFF
                    && state == DesktopSessionState.RUNNING) {
                    health = usb.health(distro);
                } else if (distro.getUsbStorage() != DesktopUsbStorageMode.OFF
                    && state == DesktopSessionState.FAILED) {
                    error = usb.diagnostics(distro);
                }
            } catch (Exception failure) {
                error = details(failure);
            }
            DesktopUsbStorageTasks.Dependencies files = dependencies;
            DesktopSessionState sessionState = state;
            DesktopUsbStorageTasks.Health snapshot = health;
            String failure = error;
            activity.runOnUiThread(() -> {
                busy = false;
                if (!usable()) return;
                if (files == null || sessionState == null) {
                    status.setText(activity.getString(R.string.desktop_usb_status_failed, failure));
                    refresh.setEnabled(true);
                } else {
                    render(files, sessionState, snapshot, failure);
                }
                handler.postDelayed(refreshTick, 3000);
            });
        });
    }
    private void render(DesktopUsbStorageTasks.Dependencies dependencies, DesktopSessionState state,
                        DesktopUsbStorageTasks.Health health, String error) {
        boolean supported = DesktopUsbStorageTasks.supported(distro);
        boolean active = state.isActive();
        boolean on = distro.getUsbStorage() != DesktopUsbStorageMode.OFF;
        rendering = true;
        modes.check(distro.getUsbStorage() == DesktopUsbStorageMode.READ_WRITE ? readWrite.getId()
            : on ? readOnly.getId() : off.getId());
        off.setEnabled(supported && !active && !busy); readOnly.setEnabled(supported && !active && !busy);
        readWrite.setEnabled(supported && !active && !busy
            && distro.getRunMode() == com.termux.ui.desktop.domain.DesktopRunMode.PROOT);
        rendering = false;
        install.setEnabled(supported && on && !active && !busy); refresh.setEnabled(!busy);
        StringBuilder summary = new StringBuilder();
        if (distro.getDe() == DesktopDe.KWIN_PLASMA)
            summary.append(activity.getString(R.string.desktop_usb_plasma_files)).append('\n');
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
        if (distro.getUsbStorage() == DesktopUsbStorageMode.READ_WRITE)
            summary.append('\n').append(activity.getString(R.string.desktop_usb_write_notice));
        if (health != null && !health.storageWarning.isEmpty())
            summary.append('\n').append(activity.getString(R.string.desktop_usb_release_warning, health.storageWarning));
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
        if ("mounted".equals(state) && distro.getUsbStorage() == DesktopUsbStorageMode.READ_WRITE)
            resource = R.string.desktop_usb_mounted_read_write;
        return activity.getString(resource);
    }
    private void initializeDesktop() throws Exception {
        showTask(desktops.configureUsb(this.distro, distro.getUsbStorage()));
    }
    private void showTask(String taskId) {
        activity.runOnUiThread(() -> {
            if (usable()) {
                dialog.dismiss();
                PrefixSetupConsoleDialog.show(activity, taskId);
                changed.run();
            }
        });
    }
    private interface Work { void run() throws Exception; }
    private void work(Work operation) {
        if (!usable() || busy) return;
        busy = true; handler.removeCallbacks(refreshTick);
        setControlsEnabled(false);
        status.setText(R.string.desktop_usb_working);
        io.execute(() -> {
            String failure = null;
            try { operation.run(); } catch (Exception error) { failure = details(error); }
            String result = failure;
            activity.runOnUiThread(() -> {
                if (!usable()) return;
                busy = false;
                if (result != null) solidBuilder().setTitle(R.string.desktop_usb_error)
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
            return activity.getString(unsupportedReason());
        if ("desktop_usb_read_write_requires_proot".equals(message))
            return activity.getString(R.string.desktop_usb_read_write_proot_only);
        return message == null ? error.getClass().getSimpleName() : message;
    }
    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(themedContext); view.setText(value); view.setTextIsSelectable(true);
        view.setPadding(0, dp(8), 0, dp(8)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, int label, Runnable action) {
        Button view = new MaterialButton(themedContext); view.setText(label); view.setAllCaps(false);
        view.setOnClickListener(ignored -> action.run()); parent.addView(view); return view;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
