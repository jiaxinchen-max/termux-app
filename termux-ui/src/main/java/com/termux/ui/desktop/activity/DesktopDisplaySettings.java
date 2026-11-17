package com.termux.ui.desktop.activity;

import android.content.Context;
import android.view.View;
import com.termux.ui.R;
import com.termux.ui.activity.FrostedDialogBuilder;
import com.termux.ui.databinding.ItemDesktopSelectBinding;
import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.runtime.DesktopDisplayPolicy;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** One binding for display rows on both creation and settings screens. */
final class DesktopDisplaySettings {
    private DesktopDisplaySettings() { }

    static String resolutionLabel(Context context, String value) {
        return DesktopDistro.NATIVE_RESOLUTION.equals(value)
            ? context.getString(R.string.desktop_resolution_native) : value;
    }

    static void bindResolution(Context context, ItemDesktopSelectBinding row,
                               Supplier<String> current, Consumer<String> apply) {
        row.desktopSelectLabel.setText(R.string.desktop_select_resolution);
        updateResolution(context, row, current.get());
        row.desktopSelectRoot.setOnClickListener(view -> {
            String[] values = DesktopDisplayPolicy.resolutions(current.get());
            String[] labels = new String[values.length];
            int selected = -1;
            for (int i = 0; i < values.length; i++) {
                labels[i] = resolutionLabel(context, values[i]);
                if (values[i].equals(current.get())) selected = i;
            }
            new FrostedDialogBuilder(context).setTitle(R.string.desktop_select_resolution)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    apply.accept(values[which]);
                    updateResolution(context, row, current.get());
                    dialog.dismiss();
                }).show();
        });
    }

    static void updateResolution(Context context, ItemDesktopSelectBinding row, String resolution) {
        row.desktopSelectValue.setText(resolutionLabel(context, resolution));
        row.desktopSelectHint.setVisibility(DesktopDistro.NATIVE_RESOLUTION.equals(resolution) ? View.VISIBLE : View.GONE);
        row.desktopSelectHint.setText(R.string.desktop_resolution_native_hint);
    }

    static void bindRefresh(Context context, ItemDesktopSelectBinding row, Supplier<DesktopDe> de,
                            IntSupplier current, IntConsumer apply) {
        row.desktopSelectLabel.setText(R.string.desktop_select_refresh);
        updateRefresh(context, row, de.get(), current.getAsInt());
        row.desktopSelectRoot.setOnClickListener(view -> {
            if (DesktopDisplayPolicy.isRefreshFixed(de.get())) return;
            int[] values = DesktopDisplayPolicy.refreshRates(de.get(), current.getAsInt());
            String[] labels = new String[values.length];
            int selected = -1;
            for (int i = 0; i < values.length; i++) {
                labels[i] = values[i] + " Hz";
                if (values[i] == current.getAsInt()) selected = i;
            }
            new FrostedDialogBuilder(context).setTitle(R.string.desktop_select_refresh)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    apply.accept(values[which]);
                    updateRefresh(context, row, de.get(), current.getAsInt());
                    dialog.dismiss();
                }).show();
        });
    }

    static void updateRefresh(Context context, ItemDesktopSelectBinding row, DesktopDe de, int requested) {
        boolean fixed = DesktopDisplayPolicy.isRefreshFixed(de);
        row.desktopSelectValue.setText(DesktopDisplayPolicy.effectiveRefreshRate(de, requested) + " Hz");
        setSelectable(row, !fixed);
        row.desktopSelectHint.setVisibility(fixed ? View.VISIBLE : View.GONE);
        row.desktopSelectHint.setText(R.string.desktop_refresh_gnome_fixed_hint);
    }

    static void setSelectable(ItemDesktopSelectBinding row, boolean selectable) {
        row.desktopSelectRoot.setEnabled(selectable);
        row.desktopSelectRoot.setFocusable(selectable);
        row.desktopSelectArrow.setVisibility(selectable ? View.VISIBLE : View.GONE);
    }
}
