package com.termux.app.activities.termuxbox;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.tabs.TabLayout;
import com.termux.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TermuxBoxContainerFragment extends Fragment {

    private static final String ARG_INITIAL_TAB = "termux_box_initial_tab";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, TermuxBoxContainerSpec> containerMap = new LinkedHashMap<>();

    private TermuxBoxRepository repository;

    private TabLayout tabs;
    private LinearLayout pageWine;
    private LinearLayout pageLibraries;
    private LinearLayout pageEnv;
    private LinearLayout pageFolders;
    private LinearLayout pageAdvanced;
    private TermuxBoxDropdownField containerNameInput;
    private TermuxBoxDropdownField wineVersionInput;
    private TermuxBoxDropdownField graphicsDriverInput;
    private TermuxBoxDropdownField dxwrapperInput;
    private TermuxBoxDropdownField audioDriverInput;
    private TermuxBoxDropdownField resolutionInput;
    private TermuxBoxDropdownField gamepadMapperInput;
    private TermuxBoxDropdownField hudModeInput;
    private TermuxBoxDropdownField startupSelectionInput;
    private TermuxBoxDropdownField box64PresetInput;
    private int initialTab = 0;

    public static TermuxBoxContainerFragment newInstance(int initialTab) {
        TermuxBoxContainerFragment fragment = new TermuxBoxContainerFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_INITIAL_TAB, initialTab);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        repository = navigator().getRepository();
        Bundle args = getArguments();
        initialTab = args == null ? 0 : args.getInt(ARG_INITIAL_TAB, 0);
        View root = inflater.inflate(R.layout.fragment_termux_box_container, container, false);
        bindViews(root);
        setupDropdowns();
        buildPages();
        setupTabs();
        setupActions(root);
        refreshState();
        TabLayout.Tab selectedTab = tabs.getTabAt(Math.max(0, Math.min(initialTab, tabs.getTabCount() - 1)));
        if (selectedTab != null) {
            selectedTab.select();
        } else {
            showPage(0);
        }
        return root;
    }

    private void bindViews(View root) {
        tabs = root.findViewById(R.id.termux_box_tabs);
        pageWine = root.findViewById(R.id.termux_box_page_wine);
        pageLibraries = root.findViewById(R.id.termux_box_page_libraries);
        pageEnv = root.findViewById(R.id.termux_box_page_env);
        pageFolders = root.findViewById(R.id.termux_box_page_folders);
        pageAdvanced = root.findViewById(R.id.termux_box_page_advanced);
        containerNameInput = root.findViewById(R.id.termux_box_container_name_input);
        wineVersionInput = root.findViewById(R.id.termux_box_wine_version_input);
        resolutionInput = root.findViewById(R.id.termux_box_resolution_input);
        graphicsDriverInput = root.findViewById(R.id.termux_box_gpu_driver_input);
        dxwrapperInput = root.findViewById(R.id.termux_box_gpu_accel_input);
        audioDriverInput = root.findViewById(R.id.termux_box_audio_driver_input);
        if (containerNameInput != null) {
            containerNameInput.setLabel(s(R.string.termux_box_container_name));
            containerNameInput.setEditable(true);
        }
        if (graphicsDriverInput != null) {
            graphicsDriverInput.setLabel("GPU");
        }
        if (resolutionInput != null) {
            resolutionInput.setLabel(s(R.string.termux_box_container_resolution));
        }
        if (dxwrapperInput != null) {
            dxwrapperInput.setLabel("DX Wrapper");
        }
        if (audioDriverInput != null) {
            audioDriverInput.setLabel(s(R.string.termux_box_container_audio_driver));
        }
    }

    private void setupDropdowns() {
        containerMap.clear();
        List<String> containerNames = new ArrayList<>();
        for (TermuxBoxContainerSpec spec : repository.getContainers()) {
            containerMap.put(spec.name, spec);
            containerNames.add(spec.name);
        }

        TermuxBoxContainerSpec currentContainer = repository.getCurrentContainer();
        String currentContainerName = currentContainer == null ? "" : currentContainer.name;
        if (!TextUtils.isEmpty(currentContainerName) && !containerMap.containsKey(currentContainerName)) {
            containerNames.add(0, currentContainerName);
        }
        if (containerNames.isEmpty()) {
            containerNames.add(s(R.string.termux_box_container_placeholder_name_1));
            containerNames.add(s(R.string.termux_box_container_placeholder_name_2));
            containerNames.add(s(R.string.termux_box_container_placeholder_name_3));
        }

        String[] resolutionOptions = a(R.array.termux_box_container_screen_size_entries);
        String[] graphicsDriverOptions = a(R.array.termux_box_container_graphics_driver_entries);
        String[] dxwrapperOptions = a(R.array.termux_box_container_dxwrapper_entries);
        String[] audioDriverOptions = a(R.array.termux_box_container_audio_driver_entries);
        setDropdown(containerNameInput, containerNames.toArray(new String[0]),
            !TextUtils.isEmpty(currentContainerName) ? currentContainerName : containerNames.get(0));
        setDropdown(resolutionInput, resolutionOptions, currentContainer == null ? "1280x720 (16:9)" : displayResolution(currentContainer.screenSize));
        setDropdown(graphicsDriverInput, graphicsDriverOptions, currentContainer == null ? graphicsDriverOptions[0] : displayGraphicsDriver(currentContainer.graphicsDriver));
        setDropdown(dxwrapperInput, dxwrapperOptions, currentContainer == null ? dxwrapperOptions[0] : currentContainer.dxwrapper);
        setDropdown(audioDriverInput, audioDriverOptions, currentContainer == null ? audioDriverOptions[0] : currentContainer.audioDriver);

        // Wine version: populated from actually installed wine packages
        List<TermuxBoxPackageSpec> installedWines = repository.getInstalledWinePackages();
        String[] wineVersionLabels;
        String[] wineVersionValues;
        if (installedWines.isEmpty()) {
            wineVersionLabels = new String[]{"wine-9.0-staging-wow64"};
            wineVersionValues = new String[]{"wine-9.0-staging-wow64"};
        } else {
            wineVersionLabels = new String[installedWines.size()];
            wineVersionValues = new String[installedWines.size()];
            for (int i = 0; i < installedWines.size(); i++) {
                wineVersionLabels[i] = installedWines.get(i).name;
                wineVersionValues[i] = installedWines.get(i).name;
            }
        }
        String currentWine = currentContainer == null ? wineVersionValues[0] : currentContainer.wineVersion;
        if (wineVersionInput != null) {
            wineVersionInput.setLabel(s(R.string.termux_box_container_wine_version));
            wineVersionInput.setOptions(wineVersionLabels, wineVersionValues, currentWine);
        }

        if (containerNameInput != null) {
            containerNameInput.setOnSelectionChangedListener((position, label, value) -> refreshState());
        }
        if (graphicsDriverInput != null) {
            graphicsDriverInput.setOnSelectionChangedListener((position, label, value) -> refreshState());
        }
        if (dxwrapperInput != null) {
            dxwrapperInput.setOnSelectionChangedListener((position, label, value) -> refreshState());
        }
        if (audioDriverInput != null) {
            audioDriverInput.setOnSelectionChangedListener((position, label, value) -> refreshState());
        }
    }

    private String displayGraphicsDriver(String raw) {
        if (raw == null || raw.isEmpty()) return "Vortek";
        String[] parts = raw.split(",");
        if (parts.length > 0) {
            String vk = parts[0].trim();
            if (vk.equals("turnip")) return "Turnip";
            if (vk.equals("vortek")) return "Vortek";
        }
        return "Vortek";
    }

    private String displayBox64Preset(String raw) {
        if (raw == null) return "Intermediate";
        String[] values = a(R.array.termux_box_container_box64_preset_values);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(raw)) return a(R.array.termux_box_container_box64_preset_entries)[i];
        }
        return "Intermediate";
    }

    private String getBox64PresetValue(int index) {
        String[] values = a(R.array.termux_box_container_box64_preset_values);
        return index >= 0 && index < values.length ? values[index] : "INTERMEDIATE";
    }

    private void setupTabs() {
        if (tabs.getTabCount() == 0) {
            tabs.addTab(tabs.newTab().setText(s(R.string.termux_box_container_tab_wine)));
            tabs.addTab(tabs.newTab().setText(s(R.string.termux_box_container_tab_libraries)));
            tabs.addTab(tabs.newTab().setText(s(R.string.termux_box_container_tab_env)));
            tabs.addTab(tabs.newTab().setText(s(R.string.termux_box_container_tab_folders)));
            tabs.addTab(tabs.newTab().setText(s(R.string.termux_box_container_tab_advanced)));
        }
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showPage(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                showPage(tab.getPosition());
            }
        });
    }

    private void setupActions(View root) {
        FloatingActionButton fab = root.findViewById(R.id.termux_box_fab);

        if (fab != null) {
            fab.setOnClickListener(v -> applyTopSettings());
        }
    }

    private void buildPages() {
        pageWine.removeAllViews();
        pageLibraries.removeAllViews();
        pageEnv.removeAllViews();
        pageFolders.removeAllViews();
        pageAdvanced.removeAllViews();

        pageWine.addView(buildSectionHeader(R.string.termux_box_container_desktop_title, R.string.termux_box_container_desktop_summary));
        pageWine.addView(buildCard(() -> {
            addDropdownField(s(R.string.termux_box_container_theme), a(R.array.termux_box_container_theme_entries), a(R.array.termux_box_container_theme_entries)[0]);
            addDropdownField(s(R.string.termux_box_container_background), a(R.array.termux_box_container_background_entries), a(R.array.termux_box_container_background_entries)[0]);
            addDropdownField(s(R.string.termux_box_container_font), a(R.array.termux_box_container_font_entries), a(R.array.termux_box_container_font_entries)[0]);
            addSliderField(s(R.string.termux_box_container_dpi), 96f, 72f, 144f);
        }));
        pageWine.addView(buildCard(() -> {
            addDropdownField(s(R.string.termux_box_container_cursor), a(R.array.termux_box_container_cursor_entries), a(R.array.termux_box_container_cursor_entries)[0]);
            addSwitchField(s(R.string.termux_box_container_mouse_offset), s(R.string.termux_box_container_mouse_offset_summary));
        }));

        pageLibraries.addView(buildSectionHeader(R.string.termux_box_container_dx_title, R.string.termux_box_container_dx_summary));
        pageLibraries.addView(buildCard(() -> {
            String[] libraryOptions = a(R.array.termux_box_container_library_entries);
            addLibraryRow(s(R.string.termux_box_container_library_d3d_direct3d), libraryOptions, libraryOptions[0]);
            addLibraryRow(s(R.string.termux_box_container_library_directsound), libraryOptions, libraryOptions[0]);
            addLibraryRow(s(R.string.termux_box_container_library_directmusic), libraryOptions, libraryOptions[0]);
            addLibraryRow(s(R.string.termux_box_container_library_directshow), libraryOptions, libraryOptions[1]);
            addLibraryRow(s(R.string.termux_box_container_library_directplay), libraryOptions, libraryOptions[1]);
            addLibraryRow(s(R.string.termux_box_container_library_xaudio), libraryOptions, libraryOptions[1]);
        }));

        pageEnv.addView(buildSectionHeader(R.string.termux_box_container_env_title, R.string.termux_box_container_env_summary));
        pageEnv.addView(buildCard(() -> {
            addEnvRow("ZINK_DESCRIPTORS", "lazy", false);
            addEnvRow("ZINK_DEBUG", "compat", false);
            addEnvRow("MESA_SHADER_CACHE_DISABLE", s(R.string.termux_box_container_off), true);
            addEnvRow("MESA_SHADER_CACHE_MAX_SIZE", "512MB", false);
            addEnvRow("mesa_glthread", s(R.string.termux_box_container_on), true);
            addEnvRow("WINEESYNC", s(R.string.termux_box_container_on), true);
            addEnvRow("TU_DEBUG", "sysmem", false);
            addCenteredAction(R.string.termux_box_container_add, v -> Toast.makeText(requireContext(), s(R.string.termux_box_container_adding_env), Toast.LENGTH_SHORT).show());
        }));

        pageFolders.addView(buildSectionHeader(R.string.termux_box_container_folders_title, R.string.termux_box_container_folders_summary));
        pageFolders.addView(buildCard(() -> {
            addFolderRow("D:", "/storage/emulated/0/Download");
            addFolderRow("E:", "/data/data/com.termux/files/usr/glibc");
            addCenteredAction(R.string.termux_box_container_add, v -> Toast.makeText(requireContext(), s(R.string.termux_box_container_adding_folder), Toast.LENGTH_SHORT).show());
        }));

        pageAdvanced.addView(buildSectionHeader(s(R.string.termux_box_container_box64_title), ""));
        pageAdvanced.addView(buildCard(() -> {
            hudModeInput = new TermuxBoxDropdownField(requireContext());
            hudModeInput.setLabel("HUD Mode");
            hudModeInput.setOptions(a(R.array.termux_box_container_hud_mode_entries), "Disabled");
            currentCardBody().addView(hudModeInput);

            startupSelectionInput = new TermuxBoxDropdownField(requireContext());
            startupSelectionInput.setLabel("Startup");
            startupSelectionInput.setOptions(a(R.array.termux_box_container_startup_selection_entries), "Essential (Load only essential services)");
            currentCardBody().addView(startupSelectionInput);

            box64PresetInput = new TermuxBoxDropdownField(requireContext());
            box64PresetInput.setLabel("Box64 Preset");
            box64PresetInput.setOptions(a(R.array.termux_box_container_box64_preset_entries), "Intermediate");
            currentCardBody().addView(box64PresetInput);
        }));

        // ---- Gamepad input protocol ----
        pageAdvanced.addView(buildSectionHeader(s(R.string.termux_box_container_gamepad), s(R.string.termux_box_container_gamepad_mapper_desc)));
        pageAdvanced.addView(buildCard(() -> {
            gamepadMapperInput = new TermuxBoxDropdownField(requireContext());
            gamepadMapperInput.setLabel(s(R.string.termux_box_container_gamepad_mapper));
            String[] mapperOptions = a(R.array.termux_box_container_gamepad_mapper_entries);
            gamepadMapperInput.setOptions(mapperOptions, mapperOptions[1]); // Default: XInput
            currentCardBody().addView(gamepadMapperInput);
        }));

        // ---- Session lifecycle ----
        pageAdvanced.addView(buildSectionHeader(s(R.string.termux_box_container_session_auto_close), s(R.string.termux_box_container_session_auto_close_summary)));
        pageAdvanced.addView(buildCard(() -> {
            SwitchMaterial sessionAutoCloseSwitch = new SwitchMaterial(requireContext());
            sessionAutoCloseSwitch.setText(s(R.string.termux_box_container_session_auto_close));
            sessionAutoCloseSwitch.setChecked(repository.getSessionAutoClose());
            sessionAutoCloseSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    repository.setSessionAutoClose(isChecked);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
            currentCardBody().addView(sessionAutoCloseSwitch);
        }));
    }

    private View buildSectionHeader(int titleResId, int subtitleResId) {
        return buildSectionHeader(s(titleResId), s(subtitleResId));
    }

    private View buildSectionHeader(String title, String subtitle) {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, 0, 0, dp(12));

        TextView titleView = new TextView(requireContext());
        titleView.setText(title);
        titleView.setTextColor(0xFF24323F);
        titleView.setTextSize(18f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleView);

        TextView subtitleView = new TextView(requireContext());
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(0xFF60707E);
        subtitleView.setTextSize(12f);
        subtitleView.setPadding(0, dp(4), 0, 0);
        header.addView(subtitleView);
        return header;
    }

    private MaterialCardView buildCard(Runnable builder) {
        MaterialCardView card = new MaterialCardView(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setCardElevation(0f);
        card.setRadius(dp(5));
        card.setStrokeColor(0xFFD3DEE8);
        card.setStrokeWidth(dp(1));

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(body, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cardBody = body;
        builder.run();
        return card;
    }

    private void addDropdownField(String label, String[] options, String defaultValue) {
        TermuxBoxDropdownField field = new TermuxBoxDropdownField(requireContext());
        field.setLabel(label);
        field.setOptions(options, defaultValue);
        currentCardBody().addView(field);
    }

    private void addLibraryRow(String label, String[] options, String defaultValue) {
        TermuxBoxDropdownField field = new TermuxBoxDropdownField(requireContext());
        field.setLabel(label);
        field.setOptions(options, defaultValue);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        field.setLayoutParams(params);
        currentCardBody().addView(field);
    }

    private void addEnvRow(String key, String value, boolean isSwitch) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(10));

        if (isSwitch) {
            TextView name = new TextView(requireContext());
            name.setText(key);
            name.setTextColor(0xFF5A6773);
            name.setTextSize(12f);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, nameParams);

            SwitchMaterial sw = new SwitchMaterial(requireContext());
            sw.setText(TextUtils.equals(value, s(R.string.termux_box_container_on)) ? s(R.string.termux_box_container_on) : s(R.string.termux_box_container_off));
            sw.setChecked(TextUtils.equals(value, s(R.string.termux_box_container_on)));
            row.addView(sw);
        } else {
            TermuxBoxDropdownField field = new TermuxBoxDropdownField(requireContext());
            field.setLabel(key);
            field.setOptions(new String[] {"lazy", "compat", "sysmem", "512MB", "zh_CN.utf8"}, value);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(field, layoutParams);
        }

        MaterialButton remove = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        remove.setIconResource(R.drawable.ic_trash_outline);
        remove.setIconTintResource(android.R.color.darker_gray);
        remove.setText("");
        remove.setPadding(0, 0, 0, 0);
        remove.setMinimumWidth(dp(44));
        remove.setMinimumHeight(dp(44));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        removeParams.setMarginStart(dp(10));
        row.addView(remove, removeParams);

        currentCardBody().addView(row);
    }

    private void addFolderRow(String drive, String path) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(10));

        TermuxBoxDropdownField driveField = new TermuxBoxDropdownField(requireContext());
        driveField.setLabel(s(R.string.termux_box_container_drive_letter));
        driveField.setOptions(new String[] {"C:", "D:", "E:", "F:"}, drive);
        LinearLayout.LayoutParams driveParams = new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(driveField, driveParams);

        TermuxBoxDropdownField pathField = new TermuxBoxDropdownField(requireContext());
        pathField.setLabel(s(R.string.termux_box_container_target_path));
        pathField.setOptions(new String[] {path}, path);
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pathParams.setMarginStart(dp(10));
        row.addView(pathField, pathParams);

        MaterialButton browse = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        browse.setIconResource(R.drawable.ic_folder_outline);
        browse.setText("");
        LinearLayout.LayoutParams browseParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        browseParams.setMarginStart(dp(10));
        row.addView(browse, browseParams);

        MaterialButton remove = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        remove.setIconResource(R.drawable.ic_trash_outline);
        remove.setText("");
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        removeParams.setMarginStart(dp(8));
        row.addView(remove, removeParams);

        currentCardBody().addView(row);
    }

    private void addSliderField(String label, float value, float min, float max) {
        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextColor(0xFF5A6773);
        labelView.setTextSize(12f);
        currentCardBody().addView(labelView);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Slider slider = new Slider(requireContext());
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setValue(value);
        slider.setStepSize(1f);
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(slider, sliderParams);

        TextView valueView = new TextView(requireContext());
        valueView.setText(getString(R.string.termux_box_container_dpi_value_format, (int) value));
        valueView.setTextColor(0xFF60707E);
        valueView.setPadding(dp(10), 0, 0, 0);
        row.addView(valueView);
        slider.addOnChangeListener((slider1, value1, fromUser) -> valueView.setText(getString(R.string.termux_box_container_dpi_value_format, (int) value1)));

        currentCardBody().addView(row);
    }

    private void addSwitchField(String label, String subtitle) {
        SwitchMaterial switchMaterial = new SwitchMaterial(requireContext());
        switchMaterial.setText(label);
        switchMaterial.setChecked(false);
        currentCardBody().addView(switchMaterial);

        TextView note = new TextView(requireContext());
        note.setText(subtitle);
        note.setTextColor(0xFF60707E);
        note.setTextSize(12f);
        note.setPadding(0, dp(4), 0, 0);
        currentCardBody().addView(note);
    }

    private void addCenteredAction(int textResId, View.OnClickListener listener) {
        addCenteredAction(s(textResId), listener);
    }

    private SwitchMaterial addSwitchRow(String label) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(8));

        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextColor(0xFF5A6773);
        labelView.setTextSize(12f);
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SwitchMaterial sw = new SwitchMaterial(requireContext());
        row.addView(sw);

        currentCardBody().addView(row);
        return sw;
    }

    private void addCenteredAction(String text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        currentCardBody().addView(button, params);
    }

    private void addFullWidthButton(int textResId, View.OnClickListener listener) {
        addFullWidthButton(s(textResId), listener);
    }

    private void addFullWidthButton(String text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF8AA0AF));
        button.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        currentCardBody().addView(button, params);
    }

    private MaterialButton actionButton(int textResId, View.OnClickListener listener) {
        return actionButton(s(textResId), listener);
    }

    private MaterialButton actionButton(String text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    private void addDropdownActionField(String label, @Nullable String subtitle, DropdownOption[] options, String defaultValue, DropdownAction action) {
        TermuxBoxDropdownField field = new TermuxBoxDropdownField(requireContext());
        field.setLabel(label);
        String[] labels = new String[options.length];
        String[] values = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = options[i].label;
            values[i] = options[i].value;
        }
        field.setOptions(labels, values, resolveDropdownLabel(options, defaultValue));
        field.setOnSelectionChangedListener((position, selectedLabel, selectedValue) -> {
            runTask(s(R.string.termux_box_container_saving_value, label), listener -> action.run(selectedValue));
        });
        currentCardBody().addView(field);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView note = new TextView(requireContext());
            note.setText(subtitle);
            note.setTextColor(0xFF60707E);
            note.setTextSize(12f);
            note.setPadding(0, dp(4), 0, dp(8));
            currentCardBody().addView(note);
        }
    }

    private String resolveDropdownLabel(DropdownOption[] options, @Nullable String defaultValue) {
        if (options.length == 0) {
            return "";
        }
        if (TextUtils.isEmpty(defaultValue)) {
            return options[0].label;
        }
        for (DropdownOption option : options) {
            if (TextUtils.equals(option.label, defaultValue) || TextUtils.equals(option.value, defaultValue)) {
                return option.label;
            }
        }
        return options[0].label;
    }

    private void setDropdown(@Nullable TermuxBoxDropdownField view, String[] values, String defaultValue) {
        if (view == null) {
            return;
        }
        view.setOptions(values, defaultValue);
    }

    private void refreshState() {
        String selectedName = safeText(containerNameInput);
        TermuxBoxContainerSpec selected = containerMap.get(selectedName);
        if (selected != null) {
            if (wineVersionInput != null) {
                wineVersionInput.setValue(selected.wineVersion);
            }
            if (resolutionInput != null) {
                resolutionInput.setValue(displayResolution(selected.screenSize));
            }
            if (graphicsDriverInput != null) {
                graphicsDriverInput.setValue(displayGraphicsDriver(selected.graphicsDriver));
            }
            if (dxwrapperInput != null) {
                dxwrapperInput.setValue(selected.dxwrapper);
            }
            if (audioDriverInput != null) {
                audioDriverInput.setValue(selected.audioDriver);
            }
            if (gamepadMapperInput != null) {
                gamepadMapperInput.setValue(a(R.array.termux_box_container_gamepad_mapper_entries)[selected.dinputMapperType]);
            }
            if (hudModeInput != null) {
                hudModeInput.setValue(a(R.array.termux_box_container_hud_mode_entries)[selected.hudMode]);
            }
            if (startupSelectionInput != null) {
                startupSelectionInput.setValue(a(R.array.termux_box_container_startup_selection_entries)[selected.startupSelection]);
            }
            if (box64PresetInput != null) {
                box64PresetInput.setValue(displayBox64Preset(selected.box64Preset));
            }
        }
    }

    private void applyTopSettings() {
        String containerName = safeText(containerNameInput);
        String wineVersion = safeText(wineVersionInput);
        String resolution = extractResolution(safeText(resolutionInput));
        String graphicsDriver = safeText(graphicsDriverInput);
        String dxwrapper = safeText(dxwrapperInput);
        String audioDriver = safeText(audioDriverInput);
        byte dinputMapperType = (byte)(gamepadMapperInput != null ? gamepadMapperInput.getSelectedIndex() : 1);
        byte hudMode = (byte)(hudModeInput != null ? hudModeInput.getSelectedIndex() : 0);
        byte startupSelection = (byte)(startupSelectionInput != null ? startupSelectionInput.getSelectedIndex() : 1);
        String box64Preset = box64PresetInput != null ? getBox64PresetValue(box64PresetInput.getSelectedIndex()) : "INTERMEDIATE";

        runTask(R.string.termux_box_container_applying_settings, listener -> {
            if (!TextUtils.isEmpty(containerName)) {
                repository.saveContainer(containerName, wineVersion, resolution,
                    "",  // envVars (future)
                    graphicsDriver, dxwrapper, audioDriver,
                    "",  // wincomponents (future)
                    hudMode, startupSelection, box64Preset,
                    "",  // desktopTheme (future)
                    dinputMapperType);
            }
            if (!TextUtils.isEmpty(resolution)) {
                repository.setFallbackResolution(resolution);
            }
        });
        refreshState();
        // Return to TermuxActivity after saving
        requireActivity().finish();
    }

    private void runTask(int messageResId, ContainerTask task) {
        runTask(s(messageResId), task);
    }

    private void runTask(String message, ContainerTask task) {
        setStatus(message, -1);
        executor.execute(() -> {
            try {
                task.run(new TermuxBoxRepository.ProgressListener() {
                    @Override
                    public void onMessage(String message) {
                        setStatus(message, -1);
                    }

                    @Override
                    public void onProgress(int progress) {
                        setStatus(null, progress);
                    }
                });
                setStatus(s(R.string.termux_box_container_done), 100);
            } catch (Exception e) {
                setStatus(s(R.string.termux_box_container_failed, e.getMessage()), 0);
            } finally {
                if (isAdded()) {
                    requireActivity().runOnUiThread(this::refreshState);
                }
            }
        });
    }

    private void setStatus(@Nullable String message, int progress) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            // inline status UI removed from this page
        });
    }

    private void showPage(int index) {
        pageWine.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        pageLibraries.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        pageEnv.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        pageFolders.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        pageAdvanced.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
    }

    private TextView addText(int textResId) {
        return addText(s(textResId));
    }

    private TextView addText(String text) {
        TextView textView = new TextView(requireContext());
        textView.setText(text);
        textView.setTextColor(0xFF24323F);
        textView.setTextSize(14f);
        currentCardBody().addView(textView);
        return textView;
    }

    private LinearLayout currentCardBody() {
        if (cardBody == null) {
            throw new IllegalStateException("Card body not initialized");
        }
        return cardBody;
    }

    private LinearLayout cardBody;

    private String safeText(@Nullable TermuxBoxDropdownField view) {
        if (view == null) {
            return "";
        }
        return view.getValue();
    }

    private String s(int resId, Object... args) {
        return getString(resId, args);
    }

    private String[] a(int arrayResId) {
        return getResources().getStringArray(arrayResId);
    }

    private String stripUtf8(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        return value.endsWith(".utf8") ? value.substring(0, value.length() - 5) : value;
    }

    private String extractResolution(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        int spaceIndex = value.indexOf(' ');
        return spaceIndex > 0 ? value.substring(0, spaceIndex) : value;
    }

    private String displayResolution(String value) {
        String[] resolutionOptions = a(R.array.termux_box_container_screen_size_entries);
        if (TextUtils.isEmpty(value)) {
            return resolutionOptions[0];
        }
        for (String option : resolutionOptions) {
            if (option.startsWith(value)) {
                return option;
            }
        }
        return value;
    }

    private int dp(int value) {
        return Math.round(value * requireContext().getResources().getDisplayMetrics().density);
    }

    private TermuxBoxNavigator navigator() {
        if (getActivity() instanceof TermuxBoxNavigator) {
            return (TermuxBoxNavigator) getActivity();
        }
        throw new IllegalStateException("TermuxBoxNavigator not attached");
    }

    private interface ContainerTask {
        void run(TermuxBoxRepository.ProgressListener listener) throws Exception;
    }

    private interface DropdownAction {
        void run(String value) throws Exception;
    }

    private static final class DropdownOption {
        private final String label;
        private final String value;

        private DropdownOption(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private DropdownOption option(String label, String value) {
        return new DropdownOption(label, value);
    }
}
