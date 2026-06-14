package com.termux.app.activities.termuxbox;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
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

    private static final String[] RESOLUTION_OPTIONS = {
        "1920x1080 (16:9)",
        "1600x900 (16:9)",
        "1366x768 (16:9)",
        "1280x720 (16:9)",
        "960x540 (16:9)"
    };
    private static final String[] GPU_DRIVER_OPTIONS = {
        "Turnip (Adreno)",
        "VirGL",
        "PanVK",
        "Zink",
        "Wined3D"
    };
    private static final String[] GPU_ACCEL_OPTIONS = {
        "DXVK",
        "VKD3D",
        "Wined3D",
        "禁用"
    };
    private static final String[] AUDIO_DRIVER_OPTIONS = {
        "ALSA",
        "PulseAudio",
        "PipeWire",
        "OpenAL"
    };
    private static final String[] THEME_OPTIONS = {"浅色", "深色", "跟随系统"};
    private static final String[] BACKGROUND_OPTIONS = {"图片", "纯色", "桌面"};
    private static final String[] FONT_OPTIONS = {"Tahoma", "Segoe UI", "Noto Sans", "Liberation Sans"};
    private static final String[] LIBRARY_OPTIONS = {"第三方 (Windows)", "内置 (Wine)", "禁用"};
    private static final String[] BOX64_PRESETS = {"均衡模式", "性能模式", "兼容模式"};
    private static final String[] STARTUP_OPTIONS = {
        "基本（仅加载核心服务进程）",
        "完整桌面",
        "仅命令行"
    };
    private static final String[] WINDOWS_VERSIONS = {"Windows 7", "Windows 10", "Windows 11"};
    private static final String[] LOCALE_OPTIONS = {"en_US", "zh_CN", "ja_JP", "ko_KR"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, TermuxBoxPackageSpec> wineContainerMap = new LinkedHashMap<>();

    private TermuxBoxRepository repository;

    private TabLayout tabs;
    private LinearLayout pageWine;
    private LinearLayout pageLibraries;
    private LinearLayout pageEnv;
    private LinearLayout pageFolders;
    private LinearLayout pageAdvanced;
    private TermuxBoxDropdownField containerNameInput;
    private TermuxBoxDropdownField gpuDriverInput;
    private TermuxBoxDropdownField gpuAccelInput;
    private TermuxBoxDropdownField audioDriverInput;
    private TermuxBoxDropdownField localeInput;
    private TermuxBoxDropdownField resolutionInput;
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
        resolutionInput = root.findViewById(R.id.termux_box_resolution_input);
        localeInput = root.findViewById(R.id.termux_box_locale_input);
        gpuDriverInput = root.findViewById(R.id.termux_box_gpu_driver_input);
        gpuAccelInput = root.findViewById(R.id.termux_box_gpu_accel_input);
        audioDriverInput = root.findViewById(R.id.termux_box_audio_driver_input);
        if (containerNameInput != null) {
            containerNameInput.setLabel("容器名称");
        }
        if (gpuDriverInput != null) {
            gpuDriverInput.setLabel("图形驱动");
        }
        if (resolutionInput != null) {
            resolutionInput.setLabel("Fallback Resolution");
        }
        if (localeInput != null) {
            localeInput.setLabel("Locale");
        }
        if (gpuAccelInput != null) {
            gpuAccelInput.setLabel("图形加速");
        }
        if (audioDriverInput != null) {
            audioDriverInput.setLabel("声音驱动");
        }
    }

    private void setupDropdowns() {
        wineContainerMap.clear();
        List<String> containerNames = new ArrayList<>();
        for (TermuxBoxPackageSpec spec : repository.getInstalledWinePackages()) {
            wineContainerMap.put(spec.name, spec);
            containerNames.add(spec.name);
        }

        String currentContainer = repository.getCurrentWineContainerName();
        if (!TextUtils.isEmpty(currentContainer) && !wineContainerMap.containsKey(currentContainer)) {
            containerNames.add(0, currentContainer);
        }
        if (containerNames.isEmpty()) {
            containerNames.add("容器-1");
            containerNames.add("容器-2");
            containerNames.add("容器-3");
        }

        setDropdown(containerNameInput, containerNames.toArray(new String[0]),
            !TextUtils.isEmpty(currentContainer) ? currentContainer : containerNames.get(0));
        setDropdown(resolutionInput, RESOLUTION_OPTIONS, displayResolution(repository.getFallbackResolution()));
        setDropdown(localeInput, LOCALE_OPTIONS, stripUtf8(repository.getLocale()));
        setDropdown(gpuDriverInput, GPU_DRIVER_OPTIONS, GPU_DRIVER_OPTIONS[0]);
        setDropdown(gpuAccelInput, GPU_ACCEL_OPTIONS, GPU_ACCEL_OPTIONS[0]);
        setDropdown(audioDriverInput, AUDIO_DRIVER_OPTIONS, AUDIO_DRIVER_OPTIONS[0]);

        if (containerNameInput != null) {
            containerNameInput.setOnSelectionChangedListener((position, label, value) -> refreshState());
        }
        if (gpuDriverInput != null) {
            gpuDriverInput.setOnSelectionChangedListener((position, label, value) -> refreshState());
        }
        if (gpuAccelInput != null) {
            gpuAccelInput.setOnSelectionChangedListener((position, label, value) -> refreshState());
        }
        if (audioDriverInput != null) {
            audioDriverInput.setOnSelectionChangedListener((position, label, value) -> refreshState());
        }
    }

    private void setupTabs() {
        if (tabs.getTabCount() == 0) {
            tabs.addTab(tabs.newTab().setText("WINE配置"));
            tabs.addTab(tabs.newTab().setText("函数库"));
            tabs.addTab(tabs.newTab().setText("环境变量"));
            tabs.addTab(tabs.newTab().setText("设置文件夹"));
            tabs.addTab(tabs.newTab().setText("高级"));
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

        pageWine.addView(buildSectionHeader("Desktop", "主题、背景、字体和 DPI"));
        pageWine.addView(buildCard(() -> {
            addDropdownField("主题设置", THEME_OPTIONS, "浅色");
            addDropdownField("背景设置", BACKGROUND_OPTIONS, "图片");
            addDropdownField("系统字体", FONT_OPTIONS, "Tahoma");
            addSliderField("DPI (字体大小)", 96f, 72f, 144f);
        }));
        pageWine.addView(buildCard(() -> {
            addDropdownField("游标输入", new String[] {"Disable", "Absolute", "Relative"}, "Disable");
            addSwitchField("鼠标覆盖偏移", "影响鼠标移动");
        }));

        pageLibraries.addView(buildSectionHeader("DX 函数设置", "运行库映射"));
        pageLibraries.addView(buildCard(() -> {
            addLibraryRow("D3D 运行库(direct3d)", LIBRARY_OPTIONS, "第三方 (Windows)");
            addLibraryRow("声音播放(directsound)", LIBRARY_OPTIONS, "第三方 (Windows)");
            addLibraryRow("音乐播放(directmusic)", LIBRARY_OPTIONS, "第三方 (Windows)");
            addLibraryRow("视频解码(directshow)", LIBRARY_OPTIONS, "内置 (Wine)");
            addLibraryRow("通讯工具(directplay)", LIBRARY_OPTIONS, "内置 (Wine)");
            addLibraryRow("XAudio 运行库", LIBRARY_OPTIONS, "内置 (Wine)");
        }));

        pageEnv.addView(buildSectionHeader("环境变量", "直接编辑一组运行时开关"));
        pageEnv.addView(buildCard(() -> {
            addEnvRow("ZINK_DESCRIPTORS", "lazy", false);
            addEnvRow("ZINK_DEBUG", "compat", false);
            addEnvRow("MESA_SHADER_CACHE_DISABLE", "关闭", true);
            addEnvRow("MESA_SHADER_CACHE_MAX_SIZE", "512MB", false);
            addEnvRow("mesa_glthread", "开启", true);
            addEnvRow("WINEESYNC", "开启", true);
            addEnvRow("TU_DEBUG", "sysmem", false);
            addCenteredAction("添加", v -> Toast.makeText(requireContext(), "添加环境变量：待接入", Toast.LENGTH_SHORT).show());
        }));

        pageFolders.addView(buildSectionHeader("设置文件夹", "模拟盘符映射"));
        pageFolders.addView(buildCard(() -> {
            addFolderRow("D:", "/storage/emulated/0/Download");
            addFolderRow("E:", "/data/data/com.termux/files/usr/glibc");
            addCenteredAction("添加", v -> Toast.makeText(requireContext(), "添加文件夹映射：待接入", Toast.LENGTH_SHORT).show());
        }));

        pageAdvanced.addView(buildSectionHeader("Box64", "启动选项与兼容性"));
        pageAdvanced.addView(buildCard(() -> {
            addDropdownField("Box64 预设模式(影响程序的速度和稳定性)", BOX64_PRESETS, "均衡模式");
        }));
        pageAdvanced.addView(buildCard(() -> {
            addDropdownField("启动选项(程序无响应请选择“停用”)", STARTUP_OPTIONS, "基本（仅加载核心服务进程）");
            addDropdownField("Windows 版本", WINDOWS_VERSIONS, "Windows 7");
        }));
        pageAdvanced.addView(buildSectionHeader("Dynarec", "Preset 与兼容性开关"));
        pageAdvanced.addView(buildCard(() -> {
            addDropdownActionField(
                "Dynarec 预设模式",
                "选择后直接写入 dynarec_preset.conf",
                new DropdownOption[] {
                    option("Preset 1", "1"),
                    option("Preset 2", "2"),
                    option("Preset 3", "3"),
                    option("Preset 4", "4"),
                    option("Manual", "manual")
                },
                repository.getDynarecPresetSelection(),
                value -> {
                    if ("manual".equals(value)) {
                        repository.setDynarecPresetMode(1);
                    } else {
                        repository.applyDynarecPreset(Integer.parseInt(value));
                    }
                });
        }));
        pageAdvanced.addView(buildCard(() -> {
            addText("Compatibility flags");
            addDropdownActionField(
                "FASTNAN",
                null,
                new DropdownOption[] {
                    option("unset", "unset"),
                    option("0", "0"),
                    option("1", "1")
                },
                repository.getDynarecFlagValue("BOX64_DYNAREC_FASTNAN", "unset"),
                value -> repository.setDynarecFlag("BOX64_DYNAREC_FASTNAN", value));
            addDropdownActionField(
                "X87DOUBLE",
                null,
                new DropdownOption[] {
                    option("unset", "unset"),
                    option("0", "0"),
                    option("1", "1")
                },
                repository.getDynarecFlagValue("BOX64_DYNAREC_X87DOUBLE", "unset"),
                value -> repository.setDynarecFlag("BOX64_DYNAREC_X87DOUBLE", value));
            addDropdownActionField(
                "STRONGMEM",
                null,
                new DropdownOption[] {
                    option("unset", "unset"),
                    option("1", "1"),
                    option("2", "2"),
                    option("3", "3")
                },
                repository.getDynarecFlagValue("BOX64_DYNAREC_STRONGMEM", "unset"),
                value -> repository.setDynarecFlag("BOX64_DYNAREC_STRONGMEM", value));
            addDropdownActionField(
                "SAFEFLAGS",
                null,
                new DropdownOption[] {
                    option("unset", "unset"),
                    option("0", "0"),
                    option("2", "2")
                },
                repository.getDynarecFlagValue("BOX64_DYNAREC_SAFEFLAGS", "unset"),
                value -> repository.setDynarecFlag("BOX64_DYNAREC_SAFEFLAGS", value));
            addDropdownActionField(
                "CALLRET",
                null,
                new DropdownOption[] {
                    option("unset", "unset"),
                    option("0", "0"),
                    option("1", "1")
                },
                repository.getDynarecFlagValue("BOX64_DYNAREC_CALLRET", "unset"),
                value -> repository.setDynarecFlag("BOX64_DYNAREC_CALLRET", value));
            addDropdownActionField(
                "FASTROUND",
                null,
                new DropdownOption[] {
                    option("unset", "unset"),
                    option("0", "0"),
                    option("1", "1")
                },
                repository.getDynarecFlagValue("BOX64_DYNAREC_FASTROUND", "unset"),
                value -> repository.setDynarecFlag("BOX64_DYNAREC_FASTROUND", value));
            addDropdownActionField(
                "BIGBLOCK",
                null,
                new DropdownOption[] {
                    option("unset", "unset"),
                    option("0", "0"),
                    option("2", "2")
                },
                repository.getDynarecFlagValue("BOX64_DYNAREC_BIGBLOCK", "unset"),
                value -> repository.setDynarecFlag("BOX64_DYNAREC_BIGBLOCK", value));
            addDropdownActionField(
                "IGNOREINT3",
                null,
                new DropdownOption[] {
                    option("unset", "unset"),
                    option("0", "0"),
                    option("1", "1")
                },
                repository.getDynarecFlagValue("BOX64_IGNOREINT3", "unset"),
                value -> repository.setDynarecFlag("BOX64_IGNOREINT3", value));
            addCenteredAction("恢复默认", v -> runTask("Resetting dynarec", listener -> repository.resetDynarecToDefault()));
        }));
        pageAdvanced.addView(buildSectionHeader("系统预设", "与 mobox 脚本里的 system-settings 对应"));
        pageAdvanced.addView(buildCard(() -> {
            addDropdownActionField(
                "关联 CPU 核心",
                "PRIMARY_CORES / SECONDARY_CORES",
                new DropdownOption[] {
                    option("2", "2"),
                    option("3", "3"),
                    option("4", "4"),
                    option("5", "5"),
                    option("6", "6"),
                    option("7", "7"),
                    option("8", "8")
                },
                repository.getCorePresetSelection(),
                value -> repository.setCorePresetByPreset(Integer.parseInt(value)));
            addDropdownActionField(
                "HUD 预设",
                "GALLIUM_HUD / DXVK_HUD",
                new DropdownOption[] {
                    option("Off", "1"),
                    option("FPS", "2"),
                    option("Detailed", "3")
                },
                repository.getHudPresetSelection(),
                value -> repository.setHudPreset(Integer.parseInt(value)));
            addDropdownActionField(
                "TU_DEBUG",
                "Mesa / TU 调试开关",
                new DropdownOption[] {
                    option("noconform", "1"),
                    option("syncdraw", "2"),
                    option("flushall", "3")
                },
                repository.getTuDebugPresetSelection(),
                value -> repository.setTuDebugPreset(Integer.parseInt(value)));
        }));
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
            sw.setText(TextUtils.equals(value, "开启") ? "开启" : "关闭");
            sw.setChecked(TextUtils.equals(value, "开启"));
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
        driveField.setLabel("盘符");
        driveField.setOptions(new String[] {"C:", "D:", "E:", "F:"}, drive);
        LinearLayout.LayoutParams driveParams = new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(driveField, driveParams);

        TermuxBoxDropdownField pathField = new TermuxBoxDropdownField(requireContext());
        pathField.setLabel("目标路径");
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
        valueView.setText((int) value + " dpi");
        valueView.setTextColor(0xFF60707E);
        valueView.setPadding(dp(10), 0, 0, 0);
        row.addView(valueView);
        slider.addOnChangeListener((slider1, value1, fromUser) -> valueView.setText(((int) value1) + " dpi"));

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

    private void addCoreButtons() {
        addText("关联 CPU 核心");
        ChipGroup group = new ChipGroup(requireContext());
        group.setSingleLine(false);
        group.setChipSpacing(dp(8));
        for (int i = 0; i < 8; i++) {
            Chip chip = new Chip(requireContext());
            chip.setText("CPU" + i);
            chip.setCheckable(true);
            chip.setChecked(true);
            group.addView(chip);
        }
        currentCardBody().addView(group);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        addPresetButton(row, "2", 6, 7, 0, 5);
        addPresetButton(row, "3", 5, 7, 0, 4);
        addPresetButton(row, "4", 4, 7, 0, 3);
        currentCardBody().addView(row);
    }

    private void addHudButtons() {
        addText("HUD 预设");
        currentCardBody().addView(horizontalButtons(
            new ButtonSpec("Off", v -> runTask("Saving HUD preset", listener -> repository.setHudPreset(1))),
            new ButtonSpec("FPS", v -> runTask("Saving HUD preset", listener -> repository.setHudPreset(2))),
            new ButtonSpec("Detailed", v -> runTask("Saving HUD preset", listener -> repository.setHudPreset(3)))
        ));
    }

    private void addTuDebugButtons() {
        addText("TU_DEBUG");
        currentCardBody().addView(horizontalButtons(
            new ButtonSpec("noconform", v -> runTask("Saving TU_DEBUG", listener -> repository.setTuDebugPreset(1))),
            new ButtonSpec("syncdraw", v -> runTask("Saving TU_DEBUG", listener -> repository.setTuDebugPreset(2))),
            new ButtonSpec("flushall", v -> runTask("Saving TU_DEBUG", listener -> repository.setTuDebugPreset(3)))
        ));
    }

    private LinearLayout horizontalButtons(ButtonSpec... specs) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);
        for (int i = 0; i < specs.length; i++) {
            if (i > 0) {
                View spacer = new View(requireContext());
                LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(dp(8), 1);
                row.addView(spacer, spacerParams);
            }
            MaterialButton button = new MaterialButton(requireContext());
            button.setText(specs[i].text);
            button.setAllCaps(false);
            button.setOnClickListener(specs[i].listener);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE6EDF3));
            button.setTextColor(0xFF24323F);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(button, params);
        }
        return row;
    }

    private void addPresetButton(LinearLayout row, String label, int primaryStart, int primaryEnd, int secondaryStart, int secondaryEnd) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE6EDF3));
        button.setTextColor(0xFF24323F);
        button.setOnClickListener(v -> runTask("Saving cores", listener -> repository.setCorePreset(primaryStart, primaryEnd, secondaryStart, secondaryEnd)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) {
            View spacer = new View(requireContext());
            row.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
        }
        row.addView(button, params);
    }

    private void addContainerEnvField(String label, String[] options, String defaultValue, String subtitle, boolean isResolution) {
        TermuxBoxDropdownField field = new TermuxBoxDropdownField(requireContext());
        field.setLabel(label);
        field.setOptions(options, defaultValue);
        currentCardBody().addView(field);

        MaterialButton save = actionButton(isResolution ? "保存分辨率" : "保存语言", v -> {
            String value = field.getValue();
            if (isResolution) {
                runTask("Saving resolution", listener -> repository.setFallbackResolution(extractResolution(value)));
            } else {
                runTask("Saving locale", listener -> repository.setLocale(value));
            }
        });
        currentCardBody().addView(save);
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
            runTask("Saving " + label, listener -> action.run(selectedValue));
        });
        currentCardBody().addView(field);
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
        if (resolutionInput != null) {
            resolutionInput.setValue(displayResolution(repository.getFallbackResolution()));
        }
        if (localeInput != null) {
            localeInput.setValue(stripUtf8(repository.getLocale()));
        }
    }

    private void applyTopSettings() {
        String containerName = safeText(containerNameInput);
        String resolution = extractResolution(safeText(resolutionInput));
        String locale = safeText(localeInput);

        runTask("Applying settings", listener -> {
            if (!TextUtils.isEmpty(containerName)) {
                TermuxBoxPackageSpec spec = wineContainerMap.get(containerName);
                if (spec != null) {
                    repository.setWineContainer(spec);
                }
            }
            if (!TextUtils.isEmpty(resolution)) {
                repository.setFallbackResolution(resolution);
            }
            if (!TextUtils.isEmpty(locale)) {
                repository.setLocale(locale);
            }
        });
        refreshState();
        Toast.makeText(requireContext(), "已应用容器设置", Toast.LENGTH_SHORT).show();
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
                setStatus("Done", 100);
            } catch (Exception e) {
                setStatus("Failed: " + e.getMessage(), 0);
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
        if (TextUtils.isEmpty(value)) {
            return RESOLUTION_OPTIONS[0];
        }
        for (String option : RESOLUTION_OPTIONS) {
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

    private static final class ButtonSpec {
        private final String text;
        private final View.OnClickListener listener;

        private ButtonSpec(String text, View.OnClickListener listener) {
            this.text = text;
            this.listener = listener;
        }
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
