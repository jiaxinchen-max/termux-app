package com.termux.ui.desktop.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;

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
import com.termux.ui.desktop.runtime.DesktopDisplayPolicy;

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

    static final String EXTRA_CREATED_ID = "com.termux.ui.extra.DESKTOP_CREATED_ID";

    private ActivityDesktopCreateBinding binding;
    private SaveState saveState;
    private DesktopTasks tasks;
    private DesktopRunMode mode;
    private List<DesktopDistro> existing = new ArrayList<>();

    private String container = DesktopCreationCatalog.DEFAULT_CONTAINER;
    private DesktopDe de;
    private String restoredName;
    private DesktopDe namedDe;
    private String suggestedName;
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

        DesktopDisplaySettings.bindResolution(this, binding.desktopCreateResolution,
            () -> resolution, value -> { if (!saveState.saving) resolution = value; });
        DesktopDisplaySettings.bindRefresh(this, binding.desktopCreateRefresh, () -> de,
            () -> refreshRate, value -> { if (!saveState.saving) refreshRate = value; });
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
        binding.desktopCreateDes.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) populate();
        });
        if (savedInstanceState != null) {
            String selected = savedInstanceState.getString("desktop-de");
            if (selected != null) de = DesktopDe.valueOf(selected);
            resolution = savedInstanceState.getString("desktop-resolution", resolution);
            refreshRate = savedInstanceState.getInt("desktop-refresh", refreshRate);
            hostGlMode = DesktopGlMode.valueOf(savedInstanceState.getString("desktop-host", hostGlMode.name()));
            guestGlMode = DesktopGuestGlMode.valueOf(savedInstanceState.getString("desktop-guest", guestGlMode.name()));
            restoredName = savedInstanceState.getString("desktop-name");
        }
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
                if (isFinishing() || isDestroyed()) return;
                existing = distros;
                populate();
            });
        }, "DesktopCreateLoad").start();
    }

    private void populate() {
        LayoutInflater inflater = getLayoutInflater();
        binding.desktopCreateDes.removeAllViews();
        List<DesktopDe> options = DesktopCreationCatalog.environments();
        List<DesktopDe> available = mode == DesktopRunMode.NATIVE
            ? DesktopCreationCatalog.nativeOptions(existing) : options;
        if (de == null || !available.contains(de)) {
            de = available.isEmpty() ? null : available.get(0);
            hostGlMode = DesktopGlMode.SYSTEM;
            guestGlMode = mode == DesktopRunMode.NATIVE ? DesktopGuestGlMode.NONE
                : de == DesktopDe.KWIN_PLASMA || de == DesktopDe.UBUNTU_GNOME
                    ? DesktopGuestGlMode.LLVMPIPE : DesktopGuestGlMode.DEFAULT;
        }
        float density = getResources().getDisplayMetrics().density;
        int width = binding.desktopCreateDes.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels
            - 2 * getResources().getDimensionPixelSize(R.dimen.local_games_page_horizontal_padding);
        int availableDp = Math.max(1, Math.round(width / density));
        float scale = getResources().getConfiguration().fontScale;
        int columns = DesktopCardLayout.columns(availableDp, scale);
        binding.desktopCreateDes.setColumnCount(columns);
        for (int index = 0; index < options.size(); index++) {
            DesktopDe candidate = options.get(index);
            boolean creatable = available.contains(candidate);
            View card = optionCard(inflater, candidate, creatable, candidate == de);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = Math.round(DesktopCardLayout.widthDp(availableDp, scale) * density);
            // Decorative match-parent layers must not size themselves to the whole viewport.
            params.height = Math.round(getResources().getDimensionPixelSize(
                R.dimen.desktop_card_cover_height) * Math.max(1f, scale));
            params.columnSpec = GridLayout.spec(index % columns);
            params.rowSpec = GridLayout.spec(index / columns);
            int margin = Math.round(3 * density);
            params.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(params);
            binding.desktopCreateDes.addView(card);
        }
        if (de == null) {
            // Every native environment exists already.
            binding.desktopCreateNote.setText(R.string.desktop_card_all_created);
            binding.desktopCreateAction.setEnabled(false);
            return;
        }
        binding.desktopCreateAction.setEnabled(!saveState.saving);
        syncDynamicFields();
        if (restoredName != null) {
            binding.desktopCreateName.setText(restoredName);
            restoredName = null;
        }
    }

    private void selectDe(DesktopDe selected) {
        if (saveState.saving) return;
        if (selected != de) {
            hostGlMode = DesktopGlMode.SYSTEM;
            guestGlMode = mode == DesktopRunMode.NATIVE ? DesktopGuestGlMode.NONE
                : selected == DesktopDe.KWIN_PLASMA || selected.isGnomeSession() ? DesktopGuestGlMode.LLVMPIPE : DesktopGuestGlMode.DEFAULT;
        }
        de = selected;
        populate();
    }

    private void syncDynamicFields() {
        if (de == null) return;
        binding.desktopCreateHyprlandRuntime.getRoot().setVisibility(de == DesktopDe.HYPRLAND ? View.VISIBLE : View.GONE);
        binding.desktopCreateHyprlandTheme.getRoot().setVisibility(de == DesktopDe.HYPRLAND ? View.VISIBLE : View.GONE);
        DesktopDisplaySettings.updateResolution(this, binding.desktopCreateResolution, resolution);
        DesktopDisplaySettings.updateRefresh(this, binding.desktopCreateRefresh, de, refreshRate);
        binding.desktopCreateHostRenderer.desktopSelectValue.setText(hostLabel(this, hostGlMode));
        binding.desktopCreateRenderer.desktopSelectValue.setText(guestLabel(this, guestGlMode));
        binding.desktopCreateRenderer.getRoot().setVisibility(mode == DesktopRunMode.PROOT ? View.VISIBLE : View.GONE);
        DesktopDisplaySettings.setSelectable(binding.desktopCreateHostRenderer, hostRendererLabels().length > 1);
        DesktopDisplaySettings.setSelectable(binding.desktopCreateRenderer, guestRendererLabels().length > 1);
        binding.desktopCreateRendererLabel.setText(R.string.desktop_create_section_renderer);
        binding.desktopCreateNote.setText(renderingHint(this, mode, de));
        if (mode == DesktopRunMode.PROOT) {
            container = DesktopCreationCatalog.containerBase(de);
            String alias = DesktopCreationCatalog.nextInstanceAlias(existing, container, de);
            binding.desktopCreateAlias.setText(getString(R.string.desktop_create_alias) + ": "
                + alias);
            String nextName = deLabel(de) + " #" + alias.substring(alias.lastIndexOf('-') + 1);
            if (namedDe != de || String.valueOf(binding.desktopCreateName.getText()).equals(suggestedName))
                binding.desktopCreateName.setText(nextName);
            suggestedName = nextName;
        } else {
            binding.desktopCreateName.setText(deLabel(de));
        }
        namedDe = de;
    }

    /** A stand-in entry so {@link DesktopCreationCatalog#instanceLabel} numbers the next one. */
    private DesktopDistro placeholder(String alias) {
        return new DesktopDistro("next", alias, DesktopRunMode.PROOT, alias, de, hostGlMode, guestGlMode,
            resolution, DesktopDisplayPolicy.effectiveRefreshRate(de, refreshRate), DesktopUsbStorageMode.OFF);
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
            de, hostGlMode, guestGlMode, resolution, DesktopDisplayPolicy.effectiveRefreshRate(de, refreshRate), DesktopUsbStorageMode.OFF);
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
        if (de != null) state.putString("desktop-de", de.name());
        state.putString("desktop-resolution", resolution);
        state.putInt("desktop-refresh", refreshRate);
        state.putString("desktop-host", hostGlMode.name());
        state.putString("desktop-guest", guestGlMode.name());
        state.putString("desktop-name", String.valueOf(binding.desktopCreateName.getText()));
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
        DesktopDisplaySettings.setSelectable(row, choices.get().length > 1);
        row.desktopSelectRoot.setOnClickListener(view -> {
            if (saveState.saving) return;
            String[] values = choices.get();
            if (values.length <= 1) return;
            new FrostedDialogBuilder(this)
                .setTitle(labelRes)
                .setSingleChoiceItems(values, indexOf(values, current.get()),
                    (dialog, which) -> {
                        if (saveState.saving) { dialog.dismiss(); return; }
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

    private View optionCard(LayoutInflater inflater, DesktopDe candidate, boolean enabled,
                            boolean selected) {
        ItemDesktopOptionBinding row = ItemDesktopOptionBinding.inflate(inflater,
            binding.desktopCreateDes, false);
        row.desktopOptionTitle.setText(deLabel(candidate));
        row.desktopOptionIcon.setImageResource(DesktopCardAppearance.art(candidate));
        row.desktopOptionRoot.setBackgroundResource(DesktopCardAppearance.cover(candidate));
        row.desktopOptionCheck.setVisibility(selected ? View.VISIBLE : View.GONE);
        row.desktopOptionSelection.setVisibility(selected ? View.VISIBLE : View.GONE);
        row.desktopOptionSoon.setVisibility(enabled ? View.GONE : View.VISIBLE);
        row.desktopOptionRoot.setAlpha(enabled ? 1f : 0.65f);
        row.desktopOptionRoot.setSelected(selected);
        row.desktopOptionRoot.setEnabled(enabled && !saveState.saving);
        row.desktopOptionRoot.setContentDescription(deLabel(candidate)
            + (enabled ? "" : ", " + getString(R.string.desktop_card_created)));
        if (enabled) row.desktopOptionRoot.setOnClickListener(view -> selectDe(candidate));
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
