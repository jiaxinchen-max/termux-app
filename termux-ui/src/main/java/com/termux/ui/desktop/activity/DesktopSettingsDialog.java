package com.termux.ui.desktop.activity;

import android.view.LayoutInflater;
import android.view.View;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.termux.ui.R;
import com.termux.ui.desktop.domain.HyprlandComponents;
import com.termux.ui.activity.FrostedDialogBuilder;
import com.termux.ui.activity.FrostedWindow;
import com.termux.ui.databinding.ItemDesktopSelectBinding;
import com.termux.ui.databinding.SheetDesktopSettingsBinding;
import com.termux.ui.desktop.api.DesktopTasks;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopGuestGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.domain.DesktopSessionState;
import com.termux.ui.desktop.runtime.DesktopRendererPolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bottom sheet editing one desktop: rename (container desktops only) plus the dropdown parameter
 * rows. Orientation is deliberately absent: it is a global Settings entry.
 */
final class DesktopSettingsDialog {

    private static final String[] RESOLUTIONS = {"1280x720", "1600x900", "1920x1080", "2560x1440"};
    private static final int[] REFRESH_RATES = {30, 60, 90, 120};

    private DesktopSettingsDialog() {}

    static void show(DesktopActivity activity, DesktopTasks tasks, DesktopDistro distro,
                     Runnable onSaved) {
        SheetDesktopSettingsBinding sheet = SheetDesktopSettingsBinding.inflate(
            LayoutInflater.from(activity));
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(sheet.getRoot());
        FrostedWindow.applyScrim(dialog);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);

        boolean container = distro.getRunMode() == DesktopRunMode.PROOT;
        sheet.desktopSettingsHint.setText(activity.getString(R.string.desktop_settings_hint,
            DesktopCreateActivity.labelOf(activity, distro.getDe())
                + (container ? " · " + distro.getProotDistro() : " · "
                    + activity.getString(R.string.desktop_tab_native))));
        if (container) sheet.desktopSettingsHint.append("\n" + activity.getString(
            DesktopCreateActivity.renderingHint(activity, distro.getRunMode(), distro.getDe())));
        sheet.desktopSettingsNameLayout.setVisibility(container ? View.VISIBLE : View.GONE);
        sheet.desktopSettingsNameNote.setVisibility(container ? View.GONE : View.VISIBLE);
        if (container) sheet.desktopSettingsName.setText(distro.getName());

        final String[] theme = {distro.getHyprlandThemeId()};
        sheet.desktopSettingsHyprlandRuntime.getRoot().setVisibility(distro.getDe() == DesktopDe.HYPRLAND ? View.VISIBLE : View.GONE);
        sheet.desktopSettingsHyprlandTheme.getRoot().setVisibility(distro.getDe() == DesktopDe.HYPRLAND ? View.VISIBLE : View.GONE);
        if (distro.getDe() == DesktopDe.HYPRLAND) {
            bind(activity, sheet.desktopSettingsHyprlandRuntime, R.string.desktop_hyprland_compositor,
                new String[]{distro.getHyprlandRuntimeId()}, distro::getHyprlandRuntimeId, value -> { });
            bind(activity, sheet.desktopSettingsHyprlandTheme, R.string.desktop_hyprland_theme,
                DesktopCreateActivity.themeLabels(activity), () -> DesktopCreateActivity.themeLabel(activity, theme[0]),
                value -> theme[0] = DesktopCreateActivity.themeOf(activity, value));
        }
        final String[] resolution = {distro.getResolution()};
        final int[] refresh = {distro.getRefreshRate()};
        final DesktopGlMode[] host = {distro.getHostGlMode()};
        final DesktopGuestGlMode[] guest = {distro.getGuestGlMode()};

        bind(activity, sheet.desktopSettingsResolution, R.string.desktop_select_resolution,
            RESOLUTIONS, () -> resolution[0], value -> resolution[0] = value);
        bind(activity, sheet.desktopSettingsRefresh, R.string.desktop_select_refresh,
            distro.getDe().isGnomeSession() && !container ? new String[]{"60 Hz"} : refreshLabels(),
            () -> refresh[0] + " Hz",
            value -> refresh[0] = Integer.parseInt(value.substring(0, value.length() - 3)));
        List<String> hosts = new ArrayList<>();
        for (DesktopGlMode value : DesktopRendererPolicy.hostModes(distro.getRunMode(), distro.getDe()))
            hosts.add(DesktopCreateActivity.hostLabel(activity, value));
        List<String> guests = new ArrayList<>();
        for (DesktopGuestGlMode value : DesktopRendererPolicy.guestModes(distro.getRunMode(), distro.getDe()))
            guests.add(DesktopCreateActivity.guestLabel(activity, value));
        bind(activity, sheet.desktopSettingsHostRenderer, R.string.desktop_renderer_host,
            hosts.toArray(new String[0]), () -> DesktopCreateActivity.hostLabel(activity, host[0]),
            value -> host[0] = DesktopCreateActivity.hostOf(activity, value));
        bind(activity, sheet.desktopSettingsRenderer, R.string.desktop_renderer_guest,
            guests.toArray(new String[0]), () -> DesktopCreateActivity.guestLabel(activity, guest[0]),
            value -> guest[0] = DesktopCreateActivity.guestOf(activity, value));
        sheet.desktopSettingsRenderer.getRoot().setVisibility(container ? View.VISIBLE : View.GONE);
        sheet.desktopSettingsRenderer.desktopSelectRoot.setEnabled(distro.getDe() != DesktopDe.XFCE4);

        sheet.desktopSettingsCancel.setOnClickListener(view -> dialog.dismiss());
        sheet.desktopSettingsSave.setOnClickListener(view -> {
            if (!DesktopRendererPolicy.supports(distro.getRunMode(), distro.getDe(), host[0], guest[0])) {
                android.widget.Toast.makeText(activity, R.string.desktop_renderer_pair_unsupported,
                    android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            String name = container ? String.valueOf(sheet.desktopSettingsName.getText())
                : distro.getName();
            if (name == null || name.trim().isEmpty()) name = distro.getName();
            DesktopDistro edited = distro.withName(name.trim())
                .withRenderers(host[0], guest[0]).withOutput(resolution[0], refresh[0]);
            final DesktopDistro updated = distro.getDe() == DesktopDe.HYPRLAND
                ? edited.withHyprlandComponents(distro.getHyprlandRuntimeId(), theme[0]) : edited;
            dialog.dismiss();
            new Thread(() -> {
                try {
                    if (distro.getDe() == DesktopDe.HYPRLAND && !theme[0].equals(distro.getHyprlandThemeId())) {
                        String task = tasks.changeHyprlandComponents(updated);
                        activity.runOnUiThread(() -> activity.showInstallConsole(task));
                    } else {
                        tasks.save(updated);
                        if (updated.getHostGlMode() != distro.getHostGlMode()
                                || updated.getGuestGlMode() != distro.getGuestGlMode()) {
                            activity.installAndShowConsole(updated);
                        }
                    }
                    activity.runOnUiThread(onSaved);
                } catch (IOException | LaunchHostException error) {
                    android.util.Log.e("DesktopSettings", "Unable to save desktop settings", error);
                    activity.runOnUiThread(() -> android.widget.Toast.makeText(activity,
                        "desktop_stop_before_theme_change".equals(error.getMessage())
                            ? activity.getString(R.string.desktop_hyprland_stop_before_change)
                            : activity.getString(R.string.desktop_error, "desktop_settings_save_failed"),
                        android.widget.Toast.LENGTH_LONG).show());
                }
            }, "DesktopSettingsSave").start();
        });
        dialog.show();
    }

    private interface Getter { String get(); }

    private interface Setter { void set(String value); }

    private static void bind(DesktopActivity activity, ItemDesktopSelectBinding row, int labelRes,
                             String[] values, Getter current, Setter apply) {
        row.desktopSelectLabel.setText(labelRes);
        row.desktopSelectValue.setText(current.get());
        row.desktopSelectRoot.setOnClickListener(view ->
            new FrostedDialogBuilder(activity)
                .setTitle(labelRes)
                .setSingleChoiceItems(values, indexOf(values, current.get()),
                    (dialog, which) -> {
                        apply.set(values[which]);
                        row.desktopSelectValue.setText(current.get());
                        dialog.dismiss();
                    })
                .show());
    }

    private static int indexOf(String[] values, String current) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return i;
        return -1;
    }

    private static String[] refreshLabels() {
        String[] labels = new String[REFRESH_RATES.length];
        for (int i = 0; i < REFRESH_RATES.length; i++) labels[i] = REFRESH_RATES[i] + " Hz";
        return labels;
    }

}
