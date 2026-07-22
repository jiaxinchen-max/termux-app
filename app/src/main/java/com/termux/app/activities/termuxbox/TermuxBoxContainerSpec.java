package com.termux.app.activities.termuxbox;

import androidx.annotation.NonNull;

/**
 * Container specification matching Winlator's Container.java parameter model.
 * All field names, types, and defaults are aligned with Winlator for compatibility.
 */
public final class TermuxBoxContainerSpec {

    // ---- Winlator-aligned defaults ----
    public static final String DEFAULT_ENV_VARS =
        "ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false " +
        "MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 TU_DEBUG=noconform";
    public static final String DEFAULT_SCREEN_SIZE = "1280x720";
    public static final String DEFAULT_GRAPHICS_DRIVER = "vortek,gladio";
    public static final String DEFAULT_DXWRAPPER = "dxvk";
    public static final String DEFAULT_AUDIO_DRIVER = "alsa";
    public static final String DEFAULT_WINCOMPONENTS =
        "direct3d=1,directsound=1,directmusic=1,directshow=0,directplay=0,xaudio=1," +
        "vcrun2005=0,vcrun2010=1,wmdecoder=1";
    public static final String DEFAULT_DRIVES =
        "D:/storage/emulated/0/DownloadE:/storage/emulated/0";
    public static final String DEFAULT_BOX64_PRESET = "INTERMEDIATE";
    public static final String DEFAULT_DESKTOP_THEME = "LIGHT,IMAGE,#0277bd";

    // ---- Startup selection constants (matching Winlator Container.java) ----
    public static final byte STARTUP_SELECTION_NORMAL = 0;
    public static final byte STARTUP_SELECTION_ESSENTIAL = 1;
    public static final byte STARTUP_SELECTION_AGGRESSIVE = 2;

    // ---- Fields (aligned with Winlator Container.java) ----
    public final String id;
    public final String name;
    public final String wineVersion;          // Winlator: wineVersion
    public final String screenSize;           // Winlator: screenSize (was resolution)
    public final String envVars;              // Winlator: envVars
    public final String graphicsDriver;       // Winlator: graphicsDriver ("vulkan,opengl")
    public final String dxwrapper;            // Winlator: dxwrapper (was gpuAccel)
    public final String dxwrapperConfig;      // Winlator: dxwrapperConfig
    public final String graphicsDriverConfig; // Winlator: graphicsDriverConfig
    public final String audioDriverConfig;    // Winlator: audioDriverConfig
    public final String audioDriver;          // Winlator: audioDriver
    public final String wincomponents;        // Winlator: wincomponents (was libraries)
    public final String drives;               // Winlator: drives
    public final byte   hudMode;             // Winlator: hudMode (0=off, 1=simple, 2=full)
    public final byte   startupSelection;     // Winlator: startupSelection
    public final String cpuList;              // Winlator: cpuList
    public final String cpuListWoW64;         // Winlator: cpuListWoW64
    public final String box64Preset;          // Winlator: box64Preset
    public final String desktopTheme;         // Winlator: desktopTheme
    public final byte   dinputMapperType;     // Winlator: dinputMapperType (0=Standard, 1=XInput)

    public TermuxBoxContainerSpec(@NonNull String id,
                                  @NonNull String name,
                                  @NonNull String wineVersion,
                                  @NonNull String screenSize,
                                  @NonNull String envVars,
                                  @NonNull String graphicsDriver,
                                  @NonNull String dxwrapper,
                                  @NonNull String dxwrapperConfig,
                                  @NonNull String graphicsDriverConfig,
                                  @NonNull String audioDriverConfig,
                                  @NonNull String audioDriver,
                                  @NonNull String wincomponents,
                                  @NonNull String drives,
                                  byte hudMode,
                                  byte startupSelection,
                                  String cpuList,
                                  String cpuListWoW64,
                                  @NonNull String box64Preset,
                                  @NonNull String desktopTheme,
                                  byte dinputMapperType) {
        this.id = id;
        this.name = name;
        this.wineVersion = wineVersion;
        this.screenSize = screenSize;
        this.envVars = envVars;
        this.graphicsDriver = graphicsDriver;
        this.dxwrapper = dxwrapper;
        this.dxwrapperConfig = dxwrapperConfig;
        this.graphicsDriverConfig = graphicsDriverConfig;
        this.audioDriverConfig = audioDriverConfig;
        this.audioDriver = audioDriver;
        this.wincomponents = wincomponents;
        this.drives = drives;
        this.hudMode = hudMode;
        this.startupSelection = startupSelection;
        this.cpuList = cpuList;
        this.cpuListWoW64 = cpuListWoW64;
        this.box64Preset = box64Preset;
        this.desktopTheme = desktopTheme;
        this.dinputMapperType = dinputMapperType;
    }
}
