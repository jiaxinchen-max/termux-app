package com.termux.ui.desktop.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.ui.R;
import com.termux.ui.activity.AppUiActivity;
import com.termux.ui.activity.FrostedDialogBuilder;
import com.termux.ui.databinding.ActivityDesktopCreateBinding;
import com.termux.ui.databinding.ItemDesktopOptionBinding;
import com.termux.ui.databinding.ItemDesktopSelectBinding;
import com.termux.ui.desktop.api.DesktopTasks;
import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.runtime.DesktopCreationCatalog;
import com.termux.ui.desktop.runtime.DesktopRendererPolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Create screen of one mode tab: container choice (container mode only), desktop environment,
 * dropdown parameters and, for containers, an editable name plus the automatic instance alias.
 */
public final class DesktopCreateActivity extends AppUiActivity {

    private static final String EXTRA_MODE = "com.termux.ui.extra.DESKTOP_CREATE_MODE";

    private static final String[] RESOLUTIONS = {"1280x720", "1600x900", "1920x1080", "2560x1440"};
    private static final int[] REFRESH_RATES = {30, 60, 90, 120};

    private ActivityDesktopCreateBinding binding;
    private DesktopTasks tasks;
    private DesktopRunMode mode;
    private List<DesktopDistro> existing = new ArrayList<>();

    private String container = DesktopCreationCatalog.DEFAULT_CONTAINER;
    private DesktopDe de;
    private String resolution = DesktopDistro.DEFAULT_RESOLUTION;
    private int refreshRate = DesktopDistro.DEFAULT_REFRESH_RATE;
    private DesktopGlMode glMode;

    public static Intent createIntent(Context context, DesktopRunMode mode) {
        return new Intent(context, DesktopCreateActivity.class).putExtra(EXTRA_MODE, mode.name());
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String selectedMode = getIntent().getStringExtra(EXTRA_MODE);
        if (selectedMode == null) throw new IllegalArgumentException("desktop_creation_mode_required");
        mode = DesktopRunMode.valueOf(selectedMode);
        glMode = mode == DesktopRunMode.NATIVE ? DesktopGlMode.SYSTEM : DesktopGlMode.ZINK;
        binding = ActivityDesktopCreateBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setTitle(mode == DesktopRunMode.NATIVE
            ? R.string.desktop_create_native_title : R.string.desktop_create_container_title);
        binding.desktopCreateToolbar.setNavigationOnClickListener(view -> finish());
        binding.desktopCreateToolbar.setTitle(mode == DesktopRunMode.NATIVE
            ? getString(R.string.desktop_create_native_title)
            : getString(R.string.desktop_create_container_title));
        tasks = new DesktopTasks(this);

        binding.desktopCreateContainersSection.setVisibility(
            mode == DesktopRunMode.PROOT ? View.VISIBLE : View.GONE);
        binding.desktopCreateDeLabel.setText(mode == DesktopRunMode.NATIVE
            ? getString(R.string.desktop_create_section_de)
                + getString(R.string.desktop_create_section_de_hidden)
            : getString(R.string.desktop_create_section_de)
                + getString(R.string.desktop_create_section_de_repeat));
        binding.desktopCreateNote.setText(mode == DesktopRunMode.NATIVE
            ? R.string.desktop_create_native_note : R.string.desktop_create_container_note);
        binding.desktopCreateRendererLabel.setText(mode == DesktopRunMode.NATIVE
            ? R.string.desktop_create_section_renderer
            : R.string.desktop_create_section_renderer_container);
        binding.desktopCreateNameLayout.setVisibility(
            mode == DesktopRunMode.PROOT ? View.VISIBLE : View.GONE);
        binding.desktopCreateAlias.setVisibility(
            mode == DesktopRunMode.PROOT ? View.VISIBLE : View.GONE);

        bindSelect(binding.desktopCreateResolution, R.string.desktop_select_resolution,
            RESOLUTIONS, () -> resolution, value -> resolution = value);
        bindSelect(binding.desktopCreateRefresh, R.string.desktop_select_refresh,
            this::refreshLabels, () -> refreshRate + " Hz",
            value -> refreshRate = Integer.parseInt(value.substring(0, value.length() - 3)));
        bindSelect(binding.desktopCreateRenderer, R.string.desktop_select_renderer,
            this::rendererLabels, () -> glLabel(glMode), value -> glMode = glOf(value));

        binding.desktopCreateAction.setOnClickListener(view -> create());
        load();
    }

    private void load() {
        new Thread(() -> {
            List<DesktopDistro> loaded;
            try {
                loaded = tasks.list();
            } catch (IOException error) {
                loaded = new ArrayList<>();
            }
            final List<DesktopDistro> distros = loaded;
            runOnUiThread(() -> {
                existing = distros;
                populate();
            });
        }, "DesktopCreateLoad").start();
    }

    private void populate() {
        LayoutInflater inflater = getLayoutInflater();
        if (mode == DesktopRunMode.PROOT) {
            binding.desktopCreateContainers.removeAllViews();
            binding.desktopCreateContainers.addView(optionRow(inflater, "🌀",
                getString(R.string.desktop_container_debian),
                getString(R.string.desktop_container_debian_desc), true, true,
                view -> container = DesktopCreationCatalog.DEFAULT_CONTAINER));
            binding.desktopCreateContainers.addView(optionRow(inflater, "📦",
                getString(R.string.desktop_container_more),
                getString(R.string.desktop_container_more_desc), false, false, null));
        }
        binding.desktopCreateDes.removeAllViews();
        List<DesktopDe> options = mode == DesktopRunMode.NATIVE
            ? DesktopCreationCatalog.nativeOptions(existing)
            : DesktopCreationCatalog.containerOptions();
        if (de == null || !options.contains(de)) {
            de = options.isEmpty() ? null : options.get(0);
        }
        if (!DesktopRendererPolicy.supports(mode, de, glMode)) glMode = null;
        if (mode == DesktopRunMode.NATIVE && de == DesktopDe.GNOME) refreshRate = 60;
        for (DesktopDe candidate : DesktopCreationCatalog.environments()) {
            boolean creatable = options.contains(candidate);
            // Created native environments are hidden entirely, not greyed out.
            if (!creatable && candidate.isCreatable()) continue;
            binding.desktopCreateDes.addView(optionRow(inflater, iconOf(candidate),
                deLabel(candidate), deDesc(candidate), creatable,
                creatable && candidate == de,
                creatable ? view -> selectDe(candidate) : null));
        }
        if (de == null) {
            // Every native environment exists already.
            binding.desktopCreateNote.setText(R.string.desktop_create_native_note);
            binding.desktopCreateAction.setEnabled(false);
            return;
        }
        syncDynamicFields();
    }

    private void selectDe(DesktopDe selected) {
        // Experimental client acceleration is always an explicit Hyprland choice.
        if (selected == DesktopDe.HYPRLAND && de != selected) glMode = DesktopGlMode.SYSTEM;
        de = selected;
        populate();
    }

    private void syncDynamicFields() {
        binding.desktopCreateResolution.desktopSelectValue.setText(resolution);
        binding.desktopCreateRefresh.desktopSelectValue.setText(refreshRate + " Hz");
        binding.desktopCreateRenderer.desktopSelectValue.setText(glLabel(glMode));
        if (mode == DesktopRunMode.PROOT) {
            String alias = DesktopCreationCatalog.nextInstanceAlias(existing, container, de);
            binding.desktopCreateAlias.setText(getString(R.string.desktop_create_alias) + ": "
                + alias);
            binding.desktopCreateName.setText(deLabel(de) + " #"
                + alias.substring(alias.lastIndexOf('-') + 1));
        } else {
            binding.desktopCreateName.setText(deLabel(de));
        }
    }

    /** A stand-in entry so {@link DesktopCreationCatalog#instanceLabel} numbers the next one. */
    private DesktopDistro placeholder(String alias) {
        return new DesktopDistro("next", alias, DesktopRunMode.PROOT, alias, de, glMode,
            resolution, refreshRate);
    }

    private void create() {
        if (de == null) return;
        if (!DesktopRendererPolicy.supports(mode, de, glMode)) {
            android.widget.Toast.makeText(this, R.string.desktop_renderer_explicit_required,
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String alias = mode == DesktopRunMode.PROOT
            ? DesktopCreationCatalog.nextInstanceAlias(existing, container, de) : "";
        String name = mode == DesktopRunMode.PROOT
            ? String.valueOf(binding.desktopCreateName.getText()) : deLabel(de);
        if (name.trim().isEmpty()) name = deLabel(de);
        DesktopDistro distro = new DesktopDistro(
            "desktop-" + UUID.randomUUID().toString().substring(0, 8), name.trim(), mode, alias,
            de, glMode, resolution, refreshRate);
        new Thread(() -> {
            try {
                tasks.save(distro);
            } catch (IOException ignored) { }
            runOnUiThread(this::finish);
        }, "DesktopCreateSave").start();
    }

    private interface Getter { String get(); }

    private interface Setter { void set(String value); }

    private interface Choices { String[] get(); }

    private void bindSelect(ItemDesktopSelectBinding row, int labelRes, String[] values,
                            Getter current, Setter apply) {
        bindSelect(row, labelRes, () -> values, current, apply);
    }

    private void bindSelect(ItemDesktopSelectBinding row, int labelRes, Choices choices,
                            Getter current, Setter apply) {
        row.desktopSelectLabel.setText(labelRes);
        row.desktopSelectValue.setText(current.get());
        row.desktopSelectRoot.setOnClickListener(view -> {
            String[] values = choices.get();
            new FrostedDialogBuilder(this)
                .setTitle(labelRes)
                .setSingleChoiceItems(values, indexOf(values, current.get()),
                    (dialog, which) -> {
                        apply.set(values[which]);
                        row.desktopSelectValue.setText(current.get());
                        syncDynamicFields();
                        dialog.dismiss();
                    })
                .show();
        });
    }

    private static int indexOf(String[] values, String current) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return i;
        return -1;
    }

    private String[] refreshLabels() {
        if (mode == DesktopRunMode.NATIVE && de == DesktopDe.GNOME) return new String[]{"60 Hz"};
        String[] labels = new String[REFRESH_RATES.length];
        for (int i = 0; i < REFRESH_RATES.length; i++) labels[i] = REFRESH_RATES[i] + " Hz";
        return labels;
    }

    private String[] rendererLabels() {
        List<String> labels = new ArrayList<>();
        for (DesktopGlMode renderer : DesktopRendererPolicy.allowedModes(mode, de))
            labels.add(glLabel(renderer));
        return labels.toArray(new String[0]);
    }

    private DesktopGlMode glOf(String label) {
        if (label.equals(glLabel(DesktopGlMode.SYSTEM))) return DesktopGlMode.SYSTEM;
        if (label.equals(glLabel(DesktopGlMode.ZINK))) return DesktopGlMode.ZINK;
        if (label.equals(glLabel(DesktopGlMode.LLVMPIPE))) return DesktopGlMode.LLVMPIPE;
        throw new IllegalArgumentException("unknown_desktop_renderer:" + label);
    }

    private String glLabel(DesktopGlMode renderer) {
        return rendererLabel(this, mode, de, renderer);
    }

    static String rendererLabel(Context context, DesktopRunMode runMode, DesktopDe de, DesktopGlMode renderer) {
        if (renderer == null) return context.getString(R.string.desktop_renderer_choose);
        if (runMode == DesktopRunMode.PROOT && de == DesktopDe.HYPRLAND) {
            if (renderer == DesktopGlMode.SYSTEM) return context.getString(R.string.desktop_hyprland_gl_default);
            if (renderer == DesktopGlMode.ZINK) return context.getString(R.string.desktop_hyprland_gl_zink);
        }
        switch (renderer) {
            case SYSTEM: return context.getString(R.string.desktop_gl_system);
            case ZINK: return context.getString(R.string.desktop_gl_zink);
            case LLVMPIPE: return context.getString(R.string.desktop_gl_llvmpipe);
            default: return context.getString(R.string.desktop_gl_auto);
        }
    }

    private View optionRow(LayoutInflater inflater, String icon, String title, String desc,
                           boolean enabled, boolean selected, @Nullable View.OnClickListener click) {
        ItemDesktopOptionBinding row = ItemDesktopOptionBinding.inflate(inflater,
            binding.desktopCreateDes, false);
        row.desktopOptionIcon.setText(icon);
        row.desktopOptionTitle.setText(title);
        row.desktopOptionDesc.setText(desc);
        row.desktopOptionCheck.setVisibility(selected ? View.VISIBLE : View.GONE);
        row.desktopOptionSoon.setVisibility(enabled ? View.GONE : View.VISIBLE);
        row.desktopOptionRoot.setBackground(getDrawable(selected
            ? R.drawable.desktop_bg_option_selected : R.drawable.desktop_bg_option));
        row.desktopOptionRoot.setAlpha(enabled ? 1f : 0.45f);
        if (click != null) row.desktopOptionRoot.setOnClickListener(click);
        return row.getRoot();
    }

    static String iconOf(DesktopDe de) {
        switch (de) {
            case XFCE4: return "🐭";
            case KWIN_PLASMA: return "🪽";
            case GNOME: return "👣";
            case UBUNTU_GNOME: return "🟠";
            default: return "";
        }
    }

    String deLabel(DesktopDe de) {
        switch (de) {
            case XFCE4: return getString(R.string.desktop_de_xfce4);
            case KWIN_PLASMA: return getString(R.string.desktop_de_kwin_plasma);
            case GNOME: return getString(R.string.desktop_de_gnome);
            case HYPRLAND: return getString(R.string.desktop_de_hyprland);
            default: return getString(R.string.desktop_de_ubuntu_gnome);
        }
    }

    private String deDesc(DesktopDe de) {
        switch (de) {
            case XFCE4: return getString(R.string.desktop_de_xfce4_desc);
            case KWIN_PLASMA: return getString(R.string.desktop_de_kwin_plasma_desc);
            case GNOME: return getString(R.string.desktop_de_gnome_desc);
            case HYPRLAND: return getString(mode == DesktopRunMode.PROOT
                ? R.string.desktop_hyprland_proot_renderer_hint : R.string.desktop_de_hyprland_desc);
            default: return getString(R.string.desktop_de_ubuntu_gnome_desc);
        }
    }

    /** Shared with the settings sheet so both screens label environments identically. */
    @NonNull
    public static String labelOf(Context context, DesktopDe de) {
        switch (de) {
            case XFCE4: return context.getString(R.string.desktop_de_xfce4);
            case KWIN_PLASMA: return context.getString(R.string.desktop_de_kwin_plasma);
            case GNOME: return context.getString(R.string.desktop_de_gnome);
            case HYPRLAND: return context.getString(R.string.desktop_de_hyprland);
            default: return context.getString(R.string.desktop_de_ubuntu_gnome);
        }
    }
}
