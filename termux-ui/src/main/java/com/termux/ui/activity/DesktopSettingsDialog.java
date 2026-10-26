package com.termux.ui.activity;

import android.view.LayoutInflater;
import android.view.View;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.termux.ui.R;
import com.termux.ui.api.DesktopTasks;
import com.termux.ui.databinding.ItemDesktopSelectBinding;
import com.termux.ui.databinding.SheetDesktopSettingsBinding;
import com.termux.ui.domain.DesktopDe;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopGlMode;
import com.termux.ui.domain.DesktopRunMode;

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

        boolean container = distro.getRunMode() == DesktopRunMode.PROOT;
        sheet.desktopSettingsHint.setText(activity.getString(R.string.desktop_settings_hint,
            DesktopCreateActivity.labelOf(activity, distro.getDe())
                + (container ? " · " + distro.getProotDistro() : " · "
                    + activity.getString(R.string.desktop_tab_native))));
        sheet.desktopSettingsNameLayout.setVisibility(container ? View.VISIBLE : View.GONE);
        sheet.desktopSettingsNameNote.setVisibility(container ? View.GONE : View.VISIBLE);
        if (container) sheet.desktopSettingsName.setText(distro.getName());

        final String[] resolution = {distro.getResolution()};
        final int[] refresh = {distro.getRefreshRate()};
        final DesktopGlMode[] gl = {distro.getGlMode()};

        bind(activity, sheet.desktopSettingsResolution, R.string.desktop_select_resolution,
            RESOLUTIONS, () -> resolution[0], value -> resolution[0] = value);
        bind(activity, sheet.desktopSettingsRefresh, R.string.desktop_select_refresh,
            refreshLabels(), () -> refresh[0] + " Hz",
            value -> refresh[0] = Integer.parseInt(value.substring(0, value.length() - 3)));
        bind(activity, sheet.desktopSettingsRenderer, R.string.desktop_select_renderer,
            rendererLabels(activity, container), () -> glLabel(activity, gl[0]),
            value -> gl[0] = glOf(activity, value));

        sheet.desktopSettingsCancel.setOnClickListener(view -> dialog.dismiss());
        sheet.desktopSettingsSave.setOnClickListener(view -> {
            String name = container ? String.valueOf(sheet.desktopSettingsName.getText())
                : distro.getName();
            if (name == null || name.trim().isEmpty()) name = distro.getName();
            DesktopDistro updated = distro.withName(name.trim())
                .withGlMode(gl[0]).withOutput(resolution[0], refresh[0]);
            dialog.dismiss();
            new Thread(() -> {
                try {
                    tasks.save(updated);
                } catch (IOException ignored) { }
                activity.runOnUiThread(onSaved);
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
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
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
        return 0;
    }

    private static String[] refreshLabels() {
        String[] labels = new String[REFRESH_RATES.length];
        for (int i = 0; i < REFRESH_RATES.length; i++) labels[i] = REFRESH_RATES[i] + " Hz";
        return labels;
    }

    private static String[] rendererLabels(DesktopActivity activity, boolean container) {
        List<String> labels = new ArrayList<>();
        labels.add(glLabel(activity, DesktopGlMode.AUTO));
        if (!container) labels.add(glLabel(activity, DesktopGlMode.SYSTEM));
        labels.add(glLabel(activity, DesktopGlMode.ZINK));
        labels.add(glLabel(activity, DesktopGlMode.LLVMPIPE));
        return labels.toArray(new String[0]);
    }

    private static DesktopGlMode glOf(DesktopActivity activity, String label) {
        if (label.equals(glLabel(activity, DesktopGlMode.SYSTEM))) return DesktopGlMode.SYSTEM;
        if (label.equals(glLabel(activity, DesktopGlMode.ZINK))) return DesktopGlMode.ZINK;
        if (label.equals(glLabel(activity, DesktopGlMode.LLVMPIPE)))
            return DesktopGlMode.LLVMPIPE;
        return DesktopGlMode.AUTO;
    }

    private static String glLabel(DesktopActivity activity, DesktopGlMode mode) {
        switch (mode) {
            case SYSTEM: return activity.getString(R.string.desktop_gl_system);
            case ZINK: return activity.getString(R.string.desktop_gl_zink);
            case LLVMPIPE: return activity.getString(R.string.desktop_gl_llvmpipe);
            default: return activity.getString(R.string.desktop_gl_auto);
        }
    }
}
