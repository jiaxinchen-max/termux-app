package com.termux.ui.desktop.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.termux.ui.R;
import com.termux.ui.desktop.domain.HyprlandComponents;
import com.termux.ui.activity.AppUiActivity;
import com.termux.ui.activity.FrostedDialogBuilder;
import com.termux.ui.databinding.ActivityDesktopCreateBinding;
import com.termux.ui.databinding.ItemDesktopOptionBinding;
import com.termux.ui.databinding.ItemDesktopSelectBinding;
import com.termux.ui.desktop.api.DesktopTasks;
import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopGuestGlMode;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;
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

    static final String EXTRA_CREATED_ID = "com.termux.ui.extra.DESKTOP_CREATED_ID";

    private ActivityDesktopCreateBinding binding;
    private SaveState saveState;
    private DesktopTasks tasks;
    private DesktopRunMode mode;
    private List<DesktopDistro> existing = new ArrayList<>();

    private String container = DesktopCreationCatalog.DEFAULT_CONTAINER;
    private DesktopDe de;
    private String resolution = DesktopDistro.DEFAULT_RESOLUTION;
    private int refreshRate = DesktopDistro.DEFAULT_REFRESH_RATE;
    private DesktopGlMode hostGlMode;
    private DesktopGuestGlMode guestGlMode;
    private String hyprlandTheme = HyprlandComponents.DEFAULT_THEME;

    public static Intent createIntent(Context context, DesktopRunMode mode) {
        return new Intent(context, DesktopCreateActivity.class).putExtra(EXTRA_MODE, mode.name());
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String selectedMode = getIntent().getStringExtra(EXTRA_MODE);
        if (selectedMode == null) throw new IllegalArgumentException("desktop_creation_mode_required");
        mode = DesktopRunMode.valueOf(selectedMode);
        hostGlMode = DesktopGlMode.SYSTEM;
        guestGlMode = mode == DesktopRunMode.NATIVE ? DesktopGuestGlMode.NONE : DesktopGuestGlMode.DEFAULT;
        binding = ActivityDesktopCreateBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setTitle(mode == DesktopRunMode.NATIVE
            ? R.string.desktop_create_native_title : R.string.desktop_create_container_title);
        binding.desktopCreateToolbar.setNavigationOnClickListener(view -> {
            if (saveState == null || !saveState.saving) finish();
        });
        binding.desktopCreateToolbar.setTitle(mode == DesktopRunMode.NATIVE
            ? getString(R.string.desktop_create_native_title)
            : getString(R.string.desktop_create_container_title));
        tasks = new DesktopTasks(this);
        saveState = new ViewModelProvider(this).get(SaveState.class);
        saveState.result.observe(this, result -> {
            if (result == null) return;
            if (result.error != null) {
                saveState.saving = false;
                binding.desktopCreateAction.setEnabled(de != null);
                android.widget.Toast.makeText(this, R.string.desktop_create_save_failed,
                    android.widget.Toast.LENGTH_LONG).show();
            } else {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_CREATED_ID, result.id));
                finish();
            }
        });

        binding.desktopCreateContainersSection.setVisibility(
            View.GONE);
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
        bindSelect(binding.desktopCreateHostRenderer, R.string.desktop_renderer_host,
            this::hostRendererLabels, () -> hostLabel(this, hostGlMode), value -> hostGlMode = hostOf(this, value));
        bindSelect(binding.desktopCreateRenderer, R.string.desktop_renderer_guest,
            this::guestRendererLabels, () -> guestLabel(this, guestGlMode), value -> guestGlMode = guestOf(this, value));

        if (savedInstanceState != null) hyprlandTheme = savedInstanceState.getString("hyprland-theme", HyprlandComponents.DEFAULT_THEME);
        bindSelect(binding.desktopCreateHyprlandRuntime, R.string.desktop_hyprland_compositor,
            new String[]{HyprlandComponents.RUNTIME_ID}, () -> HyprlandComponents.RUNTIME_ID, value -> { });
        bindSelect(binding.desktopCreateHyprlandTheme, R.string.desktop_hyprland_theme,
            themeLabels(this), () -> themeLabel(this, hyprlandTheme), value -> hyprlandTheme = themeOf(this, value));
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
        binding.desktopCreateDes.removeAllViews();
        List<DesktopDe> options = mode == DesktopRunMode.NATIVE
            ? DesktopCreationCatalog.nativeOptions(existing)
            : DesktopCreationCatalog.containerOptions();
        if (de == null || !options.contains(de)) {
            de = options.isEmpty() ? null : options.get(0);
        }

        if (mode == DesktopRunMode.NATIVE && de != null && de.isGnomeSession()) refreshRate = 60;
        for (DesktopDe candidate : options) {
            boolean creatable = true;
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
        binding.desktopCreateAction.setEnabled(!saveState.saving);
        syncDynamicFields();
    }

    private void selectDe(DesktopDe selected) {
        if (selected != de) {
            hostGlMode = DesktopGlMode.SYSTEM;
            guestGlMode = mode == DesktopRunMode.NATIVE ? DesktopGuestGlMode.NONE
                : selected == DesktopDe.KWIN_PLASMA || selected.isGnomeSession() ? DesktopGuestGlMode.LLVMPIPE : DesktopGuestGlMode.DEFAULT;
        }
        de = selected;
        populate();
    }

    private void syncDynamicFields() {
        binding.desktopCreateHyprlandRuntime.getRoot().setVisibility(de == DesktopDe.HYPRLAND ? View.VISIBLE : View.GONE);
        binding.desktopCreateHyprlandTheme.getRoot().setVisibility(de == DesktopDe.HYPRLAND ? View.VISIBLE : View.GONE);
        binding.desktopCreateResolution.desktopSelectValue.setText(resolution);
        binding.desktopCreateRefresh.desktopSelectValue.setText(refreshRate + " Hz");
        binding.desktopCreateHostRenderer.desktopSelectValue.setText(hostLabel(this, hostGlMode));
        binding.desktopCreateRenderer.desktopSelectValue.setText(guestLabel(this, guestGlMode));
        binding.desktopCreateRenderer.getRoot().setVisibility(mode == DesktopRunMode.PROOT ? View.VISIBLE : View.GONE);
        binding.desktopCreateRenderer.desktopSelectRoot.setEnabled(de != DesktopDe.XFCE4);
        binding.desktopCreateRendererLabel.setText(R.string.desktop_create_section_renderer);
        binding.desktopCreateNote.setText(renderingHint(this, mode, de));
        if (mode == DesktopRunMode.PROOT) {
            container = DesktopCreationCatalog.containerBase(de);
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
        return new DesktopDistro("next", alias, DesktopRunMode.PROOT, alias, de, hostGlMode, guestGlMode,
            resolution, refreshRate, DesktopUsbStorageMode.OFF);
    }

    private void create() {
        if (de == null || saveState.saving) return;
        if (!DesktopRendererPolicy.supports(mode, de, hostGlMode, guestGlMode)) {
            android.widget.Toast.makeText(this, R.string.desktop_renderer_pair_unsupported,
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
            de, hostGlMode, guestGlMode, resolution, refreshRate, DesktopUsbStorageMode.OFF);
        if (de == DesktopDe.HYPRLAND) distro = distro.withHyprlandComponents(HyprlandComponents.RUNTIME_ID, hyprlandTheme);
        binding.desktopCreateAction.setEnabled(false);
        saveState.save(tasks, distro);
    }

    /** Retains one in-flight save across Activity recreation, without retaining its Activity. */
    public static final class SaveState extends ViewModel {
        final MutableLiveData<SaveResult> result = new MutableLiveData<>();
        boolean saving;

        public SaveState() { }

        void save(DesktopTasks tasks, DesktopDistro distro) {
            if (saving) return;
            saving = true;
            result.setValue(null);
            new Thread(() -> {
                try {
                    tasks.save(distro);
                    result.postValue(new SaveResult(distro.getId(), null));
                } catch (IOException error) {
                    result.postValue(new SaveResult(null, error));
                }
            }, "DesktopCreateSave").start();
        }
    }

    static final class SaveResult {
        final String id;
        final IOException error;

        SaveResult(String id, IOException error) {
            this.id = id;
            this.error = error;
        }
    }

    @Override public void onBackPressed() {
        if (saveState == null || !saveState.saving) super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("hyprland-theme", hyprlandTheme);
        super.onSaveInstanceState(state);
    }

    static String themeLabel(Context context, String theme) {
        HyprlandComponents.requireSupported(HyprlandComponents.RUNTIME_ID, theme);
        return context.getString(HyprlandComponents.CLASSIC.equals(theme)
            ? R.string.desktop_hyprland_classic : R.string.desktop_hyprland_m3ww);
    }
    static String[] themeLabels(Context context) {
        return new String[]{themeLabel(context, HyprlandComponents.CLASSIC), themeLabel(context, HyprlandComponents.DEFAULT_THEME)};
    }
    static String themeOf(Context context, String label) {
        for (String id : HyprlandComponents.themes()) if (themeLabel(context, id).equals(label)) return id;
        throw new IllegalArgumentException("unknown_hyprland_theme");
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
        if (mode == DesktopRunMode.NATIVE && de != null && de.isGnomeSession()) return new String[]{"60 Hz"};
        String[] labels = new String[REFRESH_RATES.length];
        for (int i = 0; i < REFRESH_RATES.length; i++) labels[i] = REFRESH_RATES[i] + " Hz";
        return labels;
    }

    private String[] hostRendererLabels() {
        List<String> labels = new ArrayList<>();
        for (DesktopGlMode renderer : DesktopRendererPolicy.hostModes(mode, de)) labels.add(hostLabel(this, renderer));
        return labels.toArray(new String[0]);
    }

    private String[] guestRendererLabels() {
        List<String> labels = new ArrayList<>();
        for (DesktopGuestGlMode renderer : DesktopRendererPolicy.guestModes(mode, de)) labels.add(guestLabel(this, renderer));
        return labels.toArray(new String[0]);
    }

    static String hostLabel(Context context, DesktopGlMode renderer) {
        if (renderer == null) return context.getString(R.string.desktop_renderer_choose);
        switch (renderer) {
            case SYSTEM: return context.getString(R.string.desktop_gl_system);
            case ZINK: return context.getString(R.string.desktop_gl_zink);
            case LLVMPIPE: return context.getString(R.string.desktop_gl_llvmpipe);
            default: return context.getString(R.string.desktop_renderer_choose);
        }
    }
    static String guestLabel(Context context, DesktopGuestGlMode renderer) {
        if (renderer == null) return context.getString(R.string.desktop_renderer_choose);
        switch (renderer) {
            case DEFAULT: return context.getString(R.string.desktop_guest_default);
            case ZINK: return context.getString(R.string.desktop_guest_zink);
            case LLVMPIPE: return context.getString(R.string.desktop_gl_llvmpipe);
            case NONE: return context.getString(R.string.desktop_guest_none);
            default: return context.getString(R.string.desktop_renderer_choose);
        }
    }
    static DesktopGlMode hostOf(Context context, String label) {
        for (DesktopGlMode value : DesktopGlMode.values())
            if (value != DesktopGlMode.AUTO && label.equals(hostLabel(context, value))) return value;
        throw new IllegalArgumentException("unknown_host_renderer");
    }
    static DesktopGuestGlMode guestOf(Context context, String label) {
        for (DesktopGuestGlMode value : DesktopGuestGlMode.values())
            if (value != DesktopGuestGlMode.UNRESOLVED && label.equals(guestLabel(context, value))) return value;
        throw new IllegalArgumentException("unknown_guest_renderer");
    }
    static int renderingHint(Context context, DesktopRunMode mode, DesktopDe de) {
        if (mode == DesktopRunMode.NATIVE) return de == DesktopDe.UBUNTU_GNOME
            ? R.string.desktop_native_ubuntu_hint : R.string.desktop_create_native_note;
        if (de == DesktopDe.XFCE4) return R.string.desktop_xfce_renderer_hint;
        if (de == DesktopDe.KWIN_PLASMA) return R.string.desktop_plasma_renderer_hint;
        if (de == DesktopDe.GNOME) return R.string.desktop_gnome_proot_renderer_hint;
        if (de == DesktopDe.UBUNTU_GNOME) return R.string.desktop_ubuntu_gnome_renderer_hint;
        return R.string.desktop_hyprland_proot_renderer_hint;
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
            default: return getString(mode == DesktopRunMode.NATIVE
                ? R.string.desktop_native_ubuntu_hint : R.string.desktop_de_ubuntu_gnome_desc);
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
