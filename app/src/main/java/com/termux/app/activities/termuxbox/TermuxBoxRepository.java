package com.termux.app.activities.termuxbox;

import android.content.Context;
import android.os.Build;
import android.system.Os;

import com.termux.shared.termux.TermuxConstants;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TermuxBoxRepository {

    public interface ProgressListener {
        void onMessage(String message);
        void onProgress(int progress);
    }

    private static final String PACKAGE_INDEX_ASSET = "termux-box-packages/index-v1.json";
    private static final String PACKAGE_INDEX_URL =
        "https://raw.githubusercontent.com/jiaxinchen-max/termux-box-packages/main/repository/index-v1.json";
    private static final int MAX_PACKAGE_INDEX_BYTES = 1024 * 1024;

    private final Context context;
    private final File filesDir;
    private final File glibcDir;
    private final File optDir;
    private final File termuxBoxDir;
    private final File containersDir;
    private final File defaultConfigDir;
    private final File configDir;
    private final File legacyConfDir;
    private final File legacyDefaultConfDir;
    private final File dynarecDir;
    private final File packageManagerDir;
    private final File packageIndexFile;
    private final File installedDir;
    private final File tempDir;
    private final File boxDir;
    private final File prefixDir;

    private volatile List<TermuxBoxPackageSpec> packages;

    public TermuxBoxRepository(Context context) {
        this.context = context.getApplicationContext();
        this.filesDir = new File(TermuxConstants.TERMUX_FILES_DIR_PATH);
        this.prefixDir = new File(filesDir, "usr");
        this.glibcDir = new File(prefixDir, "glibc");
        this.optDir = new File(glibcDir, "opt");
        this.termuxBoxDir = new File(glibcDir, "termux-box");
        this.containersDir = new File(termuxBoxDir, "containers");
        this.defaultConfigDir = new File(termuxBoxDir, "default-conf");
        this.configDir = new File(termuxBoxDir, "config");
        this.legacyConfDir = new File(optDir, "conf");
        this.legacyDefaultConfDir = new File(optDir, "default-conf");
        this.dynarecDir = new File(configDir, "dynarec");
        this.packageManagerDir = new File(termuxBoxDir, "package-manager");
        this.packageIndexFile = new File(packageManagerDir, "index-v1.json");
        this.installedDir = new File(packageManagerDir, "installed");
        this.tempDir = new File(packageManagerDir, "temp");
        // prefix-apps ships the selectable Mobox Box64 archives here.
        this.boxDir = new File(optDir, "box");
        this.packages = loadInitialPackageIndex();
    }

    public List<TermuxBoxPackageSpec> getPackages() {
        return packages;
    }

    public void refreshPackageIndex(ProgressListener listener) throws IOException {
        ensureDirs();
        File candidate = new File(packageManagerDir, "index-v1.json.new");
        deleteFile(candidate);
        downloadFile(PACKAGE_INDEX_URL, candidate, MAX_PACKAGE_INDEX_BYTES, listener);
        List<TermuxBoxPackageSpec> parsed = parsePackageIndex(readFile(candidate));
        deleteFile(packageIndexFile);
        if (!candidate.renameTo(packageIndexFile)) {
            copyFile(packageIndexFile, candidate);
            deleteFile(candidate);
        }
        packages = parsed;
    }

    public void reloadPackageIndex() {
        if (!packageIndexFile.isFile()) {
            return;
        }
        try {
            packages = parsePackageIndex(readFile(packageIndexFile));
        } catch (Exception ignored) {
        }
    }

    private List<TermuxBoxPackageSpec> loadInitialPackageIndex() {
        if (packageIndexFile.isFile()) {
            try {
                return parsePackageIndex(readFile(packageIndexFile));
            } catch (Exception ignored) {
            }
        }
        try (InputStream input = context.getAssets().open(PACKAGE_INDEX_ASSET)) {
            return parsePackageIndex(readStream(input, MAX_PACKAGE_INDEX_BYTES));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load built-in Termux Box package index", e);
        }
    }

    private List<TermuxBoxPackageSpec> parsePackageIndex(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            // The :games module ships this asset path and it merges into this APK, so
            // both readers must track the same schema. v2 adds presentation/compatibility
            // fields that are ignored here; the delivery fields read below are the contract.
            int schemaVersion = root.optInt("schemaVersion", 0);
            if (schemaVersion != 1 && schemaVersion != 2) {
                throw new IOException("Unsupported package index schema");
            }
            JSONArray entries = root.getJSONArray("packages");
            List<TermuxBoxPackageSpec> result = new ArrayList<>();
            Map<String, Boolean> names = new LinkedHashMap<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String name = entry.getString("id");
                int version = entry.getInt("version");
                String category = entry.getString("category");
                String url = entry.getString("url");
                long size = entry.getLong("size");
                String sha256 = entry.getString("sha256").toLowerCase(Locale.US);
                if (!name.matches("[A-Za-z0-9._-]+") || names.put(name, true) != null) {
                    throw new IOException("Invalid or duplicate package id: " + name);
                }
                if (version < 1 || !("wine".equals(category) || "runtime".equals(category))) {
                    throw new IOException("Invalid package metadata: " + name);
                }
                if (!url.startsWith("https://") || size <= 0 || !sha256.matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid package source: " + name);
                }
                result.add(new TermuxBoxPackageSpec(name, version, category, url, size, sha256));
            }
            if (result.isEmpty()) {
                throw new IOException("Package index is empty");
            }
            return Collections.unmodifiableList(result);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid package index", e);
        }
    }

    public List<TermuxBoxPackageSpec> getWinePackages() {
        List<TermuxBoxPackageSpec> result = new ArrayList<>();
        for (TermuxBoxPackageSpec spec : packages) {
            if (spec.wine) {
                result.add(spec);
            }
        }
        return result;
    }

    public List<TermuxBoxPackageSpec> getNonWinePackages() {
        List<TermuxBoxPackageSpec> result = new ArrayList<>();
        for (TermuxBoxPackageSpec spec : packages) {
            if (!spec.wine) {
                result.add(spec);
            }
        }
        return result;
    }

    public List<TermuxBoxPackageSpec> getBox64OnlyPackages() {
        List<TermuxBoxPackageSpec> result = new ArrayList<>();
        for (TermuxBoxPackageSpec spec : packages) {
            if (!spec.wine && spec.name.startsWith("box64")) {
                result.add(spec);
            }
        }
        return result;
    }

    public List<TermuxBoxPackageSpec> getOtherPackages() {
        List<TermuxBoxPackageSpec> result = new ArrayList<>();
        for (TermuxBoxPackageSpec spec : packages) {
            if (!spec.wine && !spec.name.startsWith("box64")) {
                result.add(spec);
            }
        }
        return result;
    }

    public List<TermuxBoxPackageSpec> getInstalledOtherPackages() {
        List<TermuxBoxPackageSpec> result = new ArrayList<>();
        for (TermuxBoxPackageSpec spec : getOtherPackages()) {
            if (isInstalled(spec)) {
                result.add(spec);
            }
        }
        return result;
    }

    public TermuxBoxPackageSpec findPackage(String name) {
        for (TermuxBoxPackageSpec spec : packages) {
            if (spec.name.equals(name)) {
                return spec;
            }
        }
        return null;
    }

    public boolean isInstalled(TermuxBoxPackageSpec spec) {
        return getInstalledVersion(spec) != null;
    }

    public Integer getInstalledVersion(TermuxBoxPackageSpec spec) {
        File versionFile = new File(installedDir, spec.name);
        if (!versionFile.isFile()) {
            return null;
        }
        try {
            String value = readFile(versionFile).trim();
            if (value.isEmpty()) {
                return null;
            }
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    public String getInstalledVersionText(TermuxBoxPackageSpec spec) {
        Integer value = getInstalledVersion(spec);
        return value == null ? "-" : String.valueOf(value);
    }

    public boolean isUpToDate(TermuxBoxPackageSpec spec) {
        Integer version = getInstalledVersion(spec);
        return version != null && version >= spec.version;
    }

    public List<TermuxBoxContainerSpec> getContainers() {
        List<TermuxBoxContainerSpec> result = new ArrayList<>();
        File[] dirs = containersDir.listFiles(File::isDirectory);
        if (dirs == null) {
            return result;
        }
        Arrays.sort(dirs, Comparator.comparing(File::getName));
        for (File dir : dirs) {
            File conf = new File(dir, "container.conf");
            if (!conf.isFile()) {
                continue;
            }
            TermuxBoxContainerSpec spec = readContainerSpec(dir.getName(), conf);
            if (spec != null) {
                result.add(spec);
            }
        }
        return result;
    }

    public TermuxBoxContainerSpec findContainer(String idOrName) {
        if (idOrName == null) {
            return null;
        }
        for (TermuxBoxContainerSpec spec : getContainers()) {
            if (spec.id.equals(idOrName) || spec.name.equals(idOrName)) {
                return spec;
            }
        }
        return null;
    }

    public TermuxBoxContainerSpec getCurrentContainer() {
        return findContainer(readText(new File(termuxBoxDir, "current-container.conf"), "").trim());
    }

    public void setCurrentContainer(TermuxBoxContainerSpec spec) throws IOException {
        ensureDir(termuxBoxDir);
        writeFile(new File(termuxBoxDir, "current-container.conf"), spec.id + "\n");
    }

    public void clearCurrentContainer() throws IOException {
        deleteFile(new File(termuxBoxDir, "current-container.conf"));
    }

    public boolean isContainerNameAvailable(String name, String excludedContainerId) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String candidateName = name.trim();
        String candidateId = sanitizeId(candidateName);
        for (TermuxBoxContainerSpec spec : getContainers()) {
            if (excludedContainerId != null && excludedContainerId.equals(spec.id)) {
                continue;
            }
            if (spec.name.equalsIgnoreCase(candidateName) || spec.id.equals(candidateId)) {
                return false;
            }
        }
        return true;
    }

    public String findAvailableContainerName(String nameFormat) {
        String format = nameFormat == null || nameFormat.trim().isEmpty() ? "Container-%1$d" : nameFormat;
        for (int index = 1; index < Integer.MAX_VALUE; index++) {
            String candidate = String.format(Locale.getDefault(), format, index);
            if (isContainerNameAvailable(candidate, null)) {
                return candidate;
            }
        }
        return "Container-" + System.currentTimeMillis();
    }

    public TermuxBoxContainerSpec saveContainer(String editingContainerId,
                                                String name, String wineVersion, String screenSize,
                                                String envVars, String graphicsDriver, String dxwrapper,
                                                String audioDriver, String wincomponents,
                                                byte hudMode, byte startupSelection,
                                                String box64Preset, String desktopTheme,
                                                byte dinputMapperType,
                                                String launchOrientation) throws IOException {
        String safeName = name == null || name.trim().isEmpty() ? "Container 1" : name.trim();
        TermuxBoxContainerSpec existing = editingContainerId == null ? null : findContainer(editingContainerId);
        if (editingContainerId != null && existing == null) {
            throw new IOException("Container not found: " + editingContainerId);
        }
        if (!isContainerNameAvailable(safeName, editingContainerId)) {
            throw new IOException("Container name already exists: " + safeName);
        }
        String id = existing == null ? sanitizeId(safeName) : existing.id;
        String winePackage = (wineVersion != null && !wineVersion.trim().isEmpty())
            ? wineVersion.trim()
            : resolveDefaultWinePackage();
        TermuxBoxContainerSpec spec = new TermuxBoxContainerSpec(
            id,
            safeName,
            winePackage,
            emptyToDefault(screenSize, TermuxBoxContainerSpec.DEFAULT_SCREEN_SIZE),
            emptyToDefault(envVars, TermuxBoxContainerSpec.DEFAULT_ENV_VARS),
            emptyToDefault(graphicsDriver, TermuxBoxContainerSpec.DEFAULT_GRAPHICS_DRIVER),
            emptyToDefault(dxwrapper, TermuxBoxContainerSpec.DEFAULT_DXWRAPPER),
            "",  // dxwrapperConfig
            "",  // graphicsDriverConfig
            "",  // audioDriverConfig
            emptyToDefault(audioDriver, TermuxBoxContainerSpec.DEFAULT_AUDIO_DRIVER),
            emptyToDefault(wincomponents, TermuxBoxContainerSpec.DEFAULT_WINCOMPONENTS),
            TermuxBoxContainerSpec.DEFAULT_DRIVES,
            hudMode,
            startupSelection,
            null,  // cpuList
            null,  // cpuListWoW64
            emptyToDefault(box64Preset, TermuxBoxContainerSpec.DEFAULT_BOX64_PRESET),
            emptyToDefault(desktopTheme, TermuxBoxContainerSpec.DEFAULT_DESKTOP_THEME),
            dinputMapperType,
            emptyToDefault(launchOrientation, TermuxBoxContainerSpec.DEFAULT_LAUNCH_ORIENTATION)
        );
        File dir = getContainerDir(spec);
        ensureDir(new File(dir, "prefix"));
        writeContainerSpec(spec);
        setCurrentContainer(spec);
        return spec;
    }

    /**
     * Returns true if the container's Wine prefix has been bootstrapped
     * (i.e. the marker file .termux-box-bootstrap-done exists).
     */
    public boolean isContainerBootstrapped(TermuxBoxContainerSpec spec) {
        return new File(getContainerDir(spec), "prefix/.termux-box-bootstrap-done").isFile();
    }

    /**
     * Returns the path to the bootstrap script file for a container.
     * The script is deployed from the app asset {@code bootstrap_termux_box.sh}.
     */
    public File getBootstrapScriptFile(TermuxBoxContainerSpec spec) {
        return new File(getContainerDir(spec), "bootstrap.sh");
    }

    public void removeContainer(TermuxBoxContainerSpec spec) throws IOException {
        deleteRecursively(getContainerDir(spec));
        File current = new File(termuxBoxDir, "current-container.conf");
        if (readText(current, "").trim().equals(spec.id)) {
            deleteFile(current);
        }
    }

    private TermuxBoxContainerSpec readContainerSpec(String fallbackId, File conf) {
        try {
            String text = readFile(conf);
            String id = emptyToDefault(readExportValue(text, "TERMUX_BOX_CONTAINER_ID"), fallbackId);
            String name = emptyToDefault(readExportValue(text, "TERMUX_BOX_CONTAINER_NAME"), id);
            String wineVersion = emptyToDefault(readExportValue(text, "TERMUX_BOX_WINE_PACKAGE"), resolveDefaultWinePackage());
            String screenSize = emptyToDefault(readExportValue(text, "TERMUX_BOX_RESOLUTION"), TermuxBoxContainerSpec.DEFAULT_SCREEN_SIZE);
            String envVars = emptyToDefault(readExportValue(text, "TERMUX_BOX_ENV_VARS"), TermuxBoxContainerSpec.DEFAULT_ENV_VARS);
            String graphicsDriver = emptyToDefault(readExportValue(text, "TERMUX_BOX_GRAPHICS_DRIVER"), TermuxBoxContainerSpec.DEFAULT_GRAPHICS_DRIVER);
            String dxwrapper = emptyToDefault(readExportValue(text, "TERMUX_BOX_DXWRAPPER"), TermuxBoxContainerSpec.DEFAULT_DXWRAPPER);
            String dxwrapperConfig = emptyToDefault(readExportValue(text, "TERMUX_BOX_DXWRAPPER_CONFIG"), "");
            String graphicsDriverConfig = emptyToDefault(readExportValue(text, "TERMUX_BOX_GRAPHICS_DRIVER_CONFIG"), "");
            String audioDriverConfig = emptyToDefault(readExportValue(text, "TERMUX_BOX_AUDIO_DRIVER_CONFIG"), "");
            String audioDriver = emptyToDefault(readExportValue(text, "TERMUX_BOX_AUDIO_DRIVER"), TermuxBoxContainerSpec.DEFAULT_AUDIO_DRIVER);
            String wincomponents = emptyToDefault(readExportValue(text, "TERMUX_BOX_WINCOMPONENTS"), TermuxBoxContainerSpec.DEFAULT_WINCOMPONENTS);
            String drives = emptyToDefault(readExportValue(text, "TERMUX_BOX_DRIVES"), TermuxBoxContainerSpec.DEFAULT_DRIVES);
            byte hudMode = Byte.parseByte(emptyToDefault(readExportValue(text, "TERMUX_BOX_HUD_MODE"), "0"));
            byte startupSelection = Byte.parseByte(emptyToDefault(readExportValue(text, "TERMUX_BOX_STARTUP_SELECTION"), "1"));
            String cpuList = readExportValue(text, "TERMUX_BOX_CPU_LIST");
            if (cpuList.isEmpty()) cpuList = null;
            String cpuListWoW64 = readExportValue(text, "TERMUX_BOX_CPU_LIST_WOW64");
            if (cpuListWoW64.isEmpty()) cpuListWoW64 = null;
            String box64Preset = emptyToDefault(readExportValue(text, "TERMUX_BOX_BOX64_PRESET"), TermuxBoxContainerSpec.DEFAULT_BOX64_PRESET);
            String desktopTheme = emptyToDefault(readExportValue(text, "TERMUX_BOX_DESKTOP_THEME"), TermuxBoxContainerSpec.DEFAULT_DESKTOP_THEME);
            byte dinputMapperType = Byte.parseByte(emptyToDefault(readExportValue(text, "TERMUX_BOX_GAMEPAD_MAPPER"), "1"));
            String launchOrientation = emptyToDefault(readExportValue(text, "TERMUX_BOX_ORIENTATION"), TermuxBoxContainerSpec.DEFAULT_LAUNCH_ORIENTATION);
            return new TermuxBoxContainerSpec(id, name, wineVersion, screenSize, envVars,
                graphicsDriver, dxwrapper, dxwrapperConfig, graphicsDriverConfig,
                audioDriverConfig, audioDriver, wincomponents, drives,
                hudMode, startupSelection, cpuList, cpuListWoW64,
                box64Preset, desktopTheme, dinputMapperType, launchOrientation);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeContainerSpec(TermuxBoxContainerSpec spec) throws IOException {
        File dir = getContainerDir(spec);
        ensureDir(dir);
        StringBuilder sb = new StringBuilder();
        sb.append("export TERMUX_BOX_CONTAINER_ID=").append(shellQuote(spec.id)).append("\n");
        sb.append("export TERMUX_BOX_CONTAINER_NAME=").append(shellQuote(spec.name)).append("\n");
        sb.append("export TERMUX_BOX_CONTAINER_DIR=").append(shellQuote(dir.getAbsolutePath())).append("\n");
        sb.append("export TERMUX_BOX_CONTAINER_PREFIX=").append(shellQuote(new File(dir, "prefix").getAbsolutePath())).append("\n");
        sb.append("export TERMUX_BOX_WINE_PACKAGE=").append(shellQuote(spec.wineVersion)).append("\n");
        sb.append("export TERMUX_BOX_RESOLUTION=").append(shellQuote(spec.screenSize)).append("\n");
        sb.append("export TERMUX_BOX_ENV_VARS=").append(shellQuote(spec.envVars)).append("\n");
        sb.append("export TERMUX_BOX_GRAPHICS_DRIVER=").append(shellQuote(spec.graphicsDriver)).append("\n");
        sb.append("export TERMUX_BOX_DXWRAPPER=").append(shellQuote(spec.dxwrapper)).append("\n");
        if (!spec.dxwrapperConfig.isEmpty()) sb.append("export TERMUX_BOX_DXWRAPPER_CONFIG=").append(shellQuote(spec.dxwrapperConfig)).append("\n");
        if (!spec.graphicsDriverConfig.isEmpty()) sb.append("export TERMUX_BOX_GRAPHICS_DRIVER_CONFIG=").append(shellQuote(spec.graphicsDriverConfig)).append("\n");
        if (!spec.audioDriverConfig.isEmpty()) sb.append("export TERMUX_BOX_AUDIO_DRIVER_CONFIG=").append(shellQuote(spec.audioDriverConfig)).append("\n");
        sb.append("export TERMUX_BOX_AUDIO_DRIVER=").append(shellQuote(spec.audioDriver)).append("\n");
        sb.append("export TERMUX_BOX_WINCOMPONENTS=").append(shellQuote(spec.wincomponents)).append("\n");
        sb.append("export TERMUX_BOX_DRIVES=").append(shellQuote(spec.drives)).append("\n");
        sb.append("export TERMUX_BOX_HUD_MODE=").append(spec.hudMode).append("\n");
        sb.append("export TERMUX_BOX_STARTUP_SELECTION=").append(spec.startupSelection).append("\n");
        if (spec.cpuList != null) sb.append("export TERMUX_BOX_CPU_LIST=").append(shellQuote(spec.cpuList)).append("\n");
        if (spec.cpuListWoW64 != null) sb.append("export TERMUX_BOX_CPU_LIST_WOW64=").append(shellQuote(spec.cpuListWoW64)).append("\n");
        sb.append("export TERMUX_BOX_BOX64_PRESET=").append(shellQuote(spec.box64Preset)).append("\n");
        sb.append("export TERMUX_BOX_DESKTOP_THEME=").append(shellQuote(spec.desktopTheme)).append("\n");
        sb.append("export TERMUX_BOX_GAMEPAD_MAPPER=").append(spec.dinputMapperType).append("\n");
        sb.append("export TERMUX_BOX_ORIENTATION=").append(shellQuote(spec.launchOrientation)).append("\n");
        sb.append("export LC_ALL=en_US.utf8\n");
        writeFile(new File(dir, "container.conf"), sb.toString());
    }

    public File getContainerDir(TermuxBoxContainerSpec spec) {
        return new File(containersDir, spec.id);
    }

    private String resolveDefaultWinePackage() {
        for (TermuxBoxPackageSpec spec : getInstalledWinePackages()) {
            return spec.name;
        }
        for (TermuxBoxPackageSpec spec : getWinePackages()) {
            return spec.name;
        }
        return "wine-9.3-vanilla-wow64";
    }

    private String sanitizeId(String name) {
        String value = name.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9._-]+", "-");
        value = value.replaceAll("^-+", "").replaceAll("-+$", "");
        return value.isEmpty() ? "container-1" : value;
    }

    private String normalizeLocale(String locale) {
        String value = locale.trim();
        return value.endsWith(".utf8") ? value : value + ".utf8";
    }

    private String emptyToDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private String readExportValue(String text, String key) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            String prefix = "export " + key + "=";
            if (!trimmed.startsWith(prefix)) {
                continue;
            }
            String value = trimmed.substring(prefix.length()).trim();
            if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
                value = value.substring(1, value.length() - 1);
            }
            return value.replace("'\\''", "'");
        }
        return "";
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public void applyBox64Build(String buildName) throws IOException {
        File archive = new File(boxDir, buildName + ".tar.xz");
        if (!archive.isFile()) {
            throw new IOException("Archive not found: " + archive);
        }
        ensureParent(new File(glibcDir, "bin"));
        extractTarXz(archive, new File(glibcDir, "bin"));
        File box64 = new File(glibcDir, "bin/box64");
        if (box64.isFile()) {
            box64.setExecutable(true, false);
        }
        writeFile(new File(configDir, "box64-build.conf"), buildName + "\n");
    }

    /**
     * Returns all available box64 builds by scanning the box directory for .tar.xz archives.
     */
    public List<String> getAvailableBox64Builds() {
        List<String> builds = new ArrayList<>();
        File[] files = boxDir.listFiles();
        if (files == null) {
            return builds;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.endsWith(".tar.xz")) {
                builds.add(name.substring(0, name.length() - 7));
            }
        }
        Collections.sort(builds, Collections.reverseOrder());
        return builds;
    }

    /**
     * Returns the name of the currently applied box64 build, or null if unknown.
     */
    public String getCurrentBox64Build() {
        String value = readText(new File(configDir, "box64-build.conf"), "").trim();
        return value.isEmpty() ? null : value;
    }

    public void setFallbackResolution(String resolution) throws IOException {
        writeFile(new File(configDir, "last-resolution.conf"), resolution.trim() + "\n");
    }

    public String getFallbackResolution() {
        return readText(new File(configDir, "last-resolution.conf"), "1280x720");
    }

    public void setLocale(String locale) throws IOException {
        if (!locale.endsWith(".utf8")) {
            locale = locale + ".utf8";
        }
        writeFile(new File(configDir, "locale.conf"), locale.trim() + "\n");
    }

    public String getLocale() {
        String value = readText(new File(configDir, "locale.conf"), "en_US.utf8");
        return value.trim();
    }

    /**
     * Returns whether sessions should be auto-closed after script execution.
     * Defaults to false (session stays open after command completes).
     */
    public boolean getSessionAutoClose() {
        String value = readText(new File(configDir, "session_auto_close.conf"), "1").trim();
        return "1".equals(value);
    }

    /**
     * Sets whether sessions should be auto-closed after script execution.
     */
    public void setSessionAutoClose(boolean autoClose) throws IOException {
        writeFile(new File(configDir, "session_auto_close.conf"), autoClose ? "1\n" : "0\n");
    }

    public void setCorePreset(int primaryStart, int primaryEnd, int secondaryStart, int secondaryEnd) throws IOException {
        writeFile(new File(configDir, "cores.conf"),
            "export PRIMARY_CORES=" + primaryStart + "-" + primaryEnd + "\n" +
                "export SECONDARY_CORES=" + secondaryStart + "-" + secondaryEnd + "\n");
    }

    /** Online CPUs, used so core presets never name a CPU this device does not have. */
    public static int getAvailableCoreCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return cores < 1 ? 1 : cores;
    }

    /**
     * Writes a core preset scaled to this device.
     *
     * <p>{@code preset} is how many cores the container should get. The presets were authored
     * for 8-core phones, so on a 4-core device an unscaled preset would pin Wine to CPUs 6-7,
     * which do not exist. Clamp to the real core count instead.
     */
    public void setCorePresetByPreset(int preset) throws IOException {
        int cores = getAvailableCoreCount();
        int primaryCount = Math.max(1, Math.min(preset, cores));
        int primaryStart = cores - primaryCount;
        int primaryEnd = cores - 1;
        // Leave the remaining cores for background work; single-core devices share the one CPU.
        int secondaryEnd = primaryStart > 0 ? primaryStart - 1 : primaryEnd;
        setCorePreset(primaryStart, primaryEnd, 0, secondaryEnd);
    }

    public String getCorePresetSelection() {
        String text = readText(new File(configDir, "cores.conf"), "");
        // The stored range is scaled to this device, so derive the preset from its width
        // rather than matching the 8-core literals the presets used to hardcode.
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("PRIMARY_CORES=(\\d+)-(\\d+)").matcher(text);
        if (matcher.find()) {
            try {
                int start = Integer.parseInt(matcher.group(1));
                int end = Integer.parseInt(matcher.group(2));
                if (end >= start) return String.valueOf(end - start + 1);
            } catch (NumberFormatException ignored) {
                // Fall through to the default below.
            }
        }
        return String.valueOf(getAvailableCoreCount());
    }

    public String readText(File file, String defaultValue) {
        try {
            return readFile(file);
        } catch (IOException e) {
            return defaultValue;
        }
    }

    public void setHudPreset(int preset) throws IOException {
        String content;
        switch (preset) {
            case 1:
                content = "unset GALLIUM_HUD\nexport GALLIUM_HUD_PERIOD=1\nunset DXVK_HUD\n";
                break;
            case 2:
                content = "export GALLIUM_HUD=simple,fps\nexport GALLIUM_HUD_PERIOD=1\nexport DXVK_HUD=version,fps,scale=0.7\n";
                break;
            case 3:
            default:
                content = "export GALLIUM_HUD=simple,fps\nexport GALLIUM_HUD_PERIOD=1\nexport DXVK_HUD=version,fps,api,scale=0.7,devinfo,gpuload,frametimes\n";
                break;
        }
        writeFile(new File(configDir, "hud.conf"), content);
    }

    public String getHudPresetSelection() {
        String text = readText(new File(configDir, "hud.conf"), "");
        if (text.contains("DXVK_HUD=version,fps,api")) {
            return "Detailed";
        }
        if (text.contains("DXVK_HUD=version,fps,scale=0.7")) {
            return "FPS";
        }
        return "Off";
    }

    public void setTuDebugPreset(int preset) throws IOException {
        String content;
        switch (preset) {
            case 1:
                content = "export TU_DEBUG=noconform\n";
                break;
            case 2:
                content = "export TU_DEBUG=noconform,syncdraw\n";
                break;
            case 3:
            default:
                content = "export TU_DEBUG=noconform,syncdraw,flushall\n";
                break;
        }
        writeFile(new File(configDir, "tu_debug.conf"), content);
    }

    public String getTuDebugPresetSelection() {
        String text = readText(new File(configDir, "tu_debug.conf"), "");
        if (text.contains("noconform,syncdraw,flushall")) {
            return "flushall";
        }
        if (text.contains("noconform,syncdraw")) {
            return "syncdraw";
        }
        return "noconform";
    }

    public void resetSystemSettings() throws IOException {
        ensureDefaultConfigSnapshot();
        copyFile(new File(defaultConfigDir, "conf/cores.conf"), new File(configDir, "cores.conf"));
        copyFile(new File(defaultConfigDir, "conf/hud.conf"), new File(configDir, "hud.conf"));
        copyFile(new File(defaultConfigDir, "conf/tu_debug.conf"), new File(configDir, "tu_debug.conf"));
        copyFile(new File(defaultConfigDir, "conf/wsi_present.conf"), new File(configDir, "wsi_present.conf"));
        copyFile(new File(defaultConfigDir, "conf/winedevice_startup.conf"), new File(configDir, "winedevice_startup.conf"));
        copyFile(new File(defaultConfigDir, "conf/virgl.conf"), new File(configDir, "virgl.conf"));
        copyFile(new File(defaultConfigDir, "conf/force_compatibility.conf"), new File(configDir, "force_compatibility.conf"));
        copyFile(new File(defaultConfigDir, "conf/wineesync.conf"), new File(configDir, "wineesync.conf"));
        copyFile(new File(defaultConfigDir, "conf/wsi_debug.conf"), new File(configDir, "wsi_debug.conf"));
        copyFile(new File(defaultConfigDir, "conf/debug.conf"), new File(configDir, "debug.conf"));
        copyFile(new File(defaultConfigDir, "conf/path.conf"), new File(configDir, "path.conf"));
        copyFile(new File(defaultConfigDir, "last-resolution.conf"), new File(configDir, "last-resolution.conf"));
        copyFile(new File(defaultConfigDir, "locale.conf"), new File(configDir, "locale.conf"));
        copyFile(new File(defaultConfigDir, "dxvk.conf"), new File(configDir, "dxvk.conf"));
    }

    public void setDynarecPresetMode(int mode) throws IOException {
        writeFile(new File(configDir, "dynarec_preset.conf"),
            "export DYNAREC_SETTINGS_SCRIPT=" + mode + "\n" +
                "export DYNAREC_CURRENT_PRESET=" + (mode == 1 ? "none" : "4") + "\n");
    }

    public String getDynarecPresetSelection() {
        String text = readText(new File(configDir, "dynarec_preset.conf"), "export DYNAREC_SETTINGS_SCRIPT=2\nexport DYNAREC_CURRENT_PRESET=4\n");
        if (text.contains("DYNAREC_SETTINGS_SCRIPT=1") || text.contains("DYNAREC_CURRENT_PRESET=none")) {
            return "Manual";
        }
        if (text.contains("DYNAREC_CURRENT_PRESET=1")) {
            return "Preset 1";
        }
        if (text.contains("DYNAREC_CURRENT_PRESET=2")) {
            return "Preset 2";
        }
        if (text.contains("DYNAREC_CURRENT_PRESET=3")) {
            return "Preset 3";
        }
        return "Preset 4";
    }

    public void applyDynarecPreset(int preset) throws IOException {
        switch (preset) {
            case 1:
                writeFile(new File(dynarecDir, "bigblock.conf"), "export BOX64_DYNAREC_BIGBLOCK=0\n");
                writeFile(new File(dynarecDir, "aligned_atomics.conf"), "unset BOX64_DYNAREC_ALIGNED_ATOMICS\n");
                writeFile(new File(dynarecDir, "x87double.conf"), "export BOX64_DYNAREC_X87DOUBLE=1\n");
                writeFile(new File(dynarecDir, "fastnan.conf"), "export BOX64_DYNAREC_FASTNAN=0\n");
                writeFile(new File(dynarecDir, "fastround.conf"), "export BOX64_DYNAREC_FASTROUND=0\n");
                writeFile(new File(dynarecDir, "safeflags.conf"), "export BOX64_DYNAREC_SAFEFLAGS=2\n");
                writeFile(new File(dynarecDir, "strongmem.conf"), "export BOX64_DYNAREC_STRONGMEM=3\n");
                writeFile(new File(dynarecDir, "wait.conf"), "export BOX64_DYNAREC_WAIT=1\n");
                writeFile(new File(dynarecDir, "callret.conf"), "export BOX64_DYNAREC_CALLRET=0\n");
                writeFile(new File(dynarecDir, "ignoreint3.conf"), "unset BOX64_IGNOREINT3\n");
                break;
            case 2:
                writeFile(new File(dynarecDir, "bigblock.conf"), "export BOX64_DYNAREC_BIGBLOCK=2\n");
                writeFile(new File(dynarecDir, "aligned_atomics.conf"), "unset BOX64_DYNAREC_ALIGNED_ATOMICS\n");
                writeFile(new File(dynarecDir, "x87double.conf"), "unset BOX64_DYNAREC_X87DOUBLE\n");
                writeFile(new File(dynarecDir, "fastnan.conf"), "export BOX64_DYNAREC_FASTNAN=0\n");
                writeFile(new File(dynarecDir, "fastround.conf"), "unset BOX64_DYNAREC_FASTROUND\n");
                writeFile(new File(dynarecDir, "safeflags.conf"), "export BOX64_DYNAREC_SAFEFLAGS=2\n");
                writeFile(new File(dynarecDir, "strongmem.conf"), "export BOX64_DYNAREC_STRONGMEM=2\n");
                writeFile(new File(dynarecDir, "wait.conf"), "unset BOX64_DYNAREC_WAIT\n");
                writeFile(new File(dynarecDir, "callret.conf"), "export BOX64_DYNAREC_CALLRET=0\n");
                writeFile(new File(dynarecDir, "ignoreint3.conf"), "unset BOX64_IGNOREINT3\n");
                break;
            case 3:
                writeFile(new File(dynarecDir, "bigblock.conf"), "export BOX64_DYNAREC_BIGBLOCK=2\n");
                writeFile(new File(dynarecDir, "aligned_atomics.conf"), "unset BOX64_DYNAREC_ALIGNED_ATOMICS\n");
                writeFile(new File(dynarecDir, "x87double.conf"), "unset BOX64_DYNAREC_X87DOUBLE\n");
                writeFile(new File(dynarecDir, "fastnan.conf"), "unset BOX64_DYNAREC_FASTNAN\n");
                writeFile(new File(dynarecDir, "fastround.conf"), "unset BOX64_DYNAREC_FASTROUND\n");
                writeFile(new File(dynarecDir, "safeflags.conf"), "unset BOX64_DYNAREC_SAFEFLAGS\n");
                writeFile(new File(dynarecDir, "strongmem.conf"), "unset BOX64_DYNAREC_STRONGMEM\n");
                writeFile(new File(dynarecDir, "wait.conf"), "unset BOX64_DYNAREC_WAIT\n");
                writeFile(new File(dynarecDir, "callret.conf"), "export BOX64_DYNAREC_CALLRET=0\n");
                writeFile(new File(dynarecDir, "ignoreint3.conf"), "unset BOX64_IGNOREINT3\n");
                break;
            case 4:
            default:
                writeFile(new File(dynarecDir, "bigblock.conf"), "export BOX64_DYNAREC_BIGBLOCK=2\n");
                writeFile(new File(dynarecDir, "aligned_atomics.conf"), "unset BOX64_DYNAREC_ALIGNED_ATOMICS\n");
                writeFile(new File(dynarecDir, "x87double.conf"), "unset BOX64_DYNAREC_X87DOUBLE\n");
                writeFile(new File(dynarecDir, "fastnan.conf"), "unset BOX64_DYNAREC_FASTNAN\n");
                writeFile(new File(dynarecDir, "fastround.conf"), "unset BOX64_DYNAREC_FASTROUND\n");
                writeFile(new File(dynarecDir, "safeflags.conf"), "export BOX64_DYNAREC_SAFEFLAGS=0\n");
                writeFile(new File(dynarecDir, "strongmem.conf"), "unset BOX64_DYNAREC_STRONGMEM\n");
                writeFile(new File(dynarecDir, "wait.conf"), "unset BOX64_DYNAREC_WAIT\n");
                writeFile(new File(dynarecDir, "callret.conf"), "export BOX64_DYNAREC_CALLRET=0\n");
                writeFile(new File(dynarecDir, "ignoreint3.conf"), "unset BOX64_IGNOREINT3\n");
                break;
        }
        writeFile(new File(configDir, "dynarec_preset.conf"),
            "export DYNAREC_SETTINGS_SCRIPT=2\nexport DYNAREC_CURRENT_PRESET=" + preset + "\n");
    }

    public void setDynarecFlag(String key, String value) throws IOException {
        String fileName = dynarecFileNameForKey(key);
        writeFile(new File(dynarecDir, fileName), value == null || value.isEmpty() ? "unset " + key + "\n" : "export " + key + "=" + value + "\n");
    }

    public String getDynarecFlagValue(String key, String defaultValue) {
        String fileName;
        try {
            fileName = dynarecFileNameForKey(key);
        } catch (IOException e) {
            return defaultValue;
        }
        String text = readText(new File(dynarecDir, fileName), "");
        String exportPrefix = "export " + key + "=";
        String unsetPrefix = "unset " + key;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(exportPrefix)) {
                return trimmed.substring(exportPrefix.length()).trim();
            }
            if (trimmed.equals(unsetPrefix)) {
                return "unset";
            }
        }
        return defaultValue;
    }

    public void resetDynarecToDefault() throws IOException {
        ensureDefaultConfigSnapshot();
        copyFile(new File(defaultConfigDir, "conf/dynarec/aligned_atomics.conf"), new File(dynarecDir, "aligned_atomics.conf"));
        copyFile(new File(defaultConfigDir, "conf/dynarec/bigblock.conf"), new File(dynarecDir, "bigblock.conf"));
        copyFile(new File(defaultConfigDir, "conf/dynarec/fastnan.conf"), new File(dynarecDir, "fastnan.conf"));
        copyFile(new File(defaultConfigDir, "conf/dynarec/fastround.conf"), new File(dynarecDir, "fastround.conf"));
        copyFile(new File(defaultConfigDir, "conf/dynarec/safeflags.conf"), new File(dynarecDir, "safeflags.conf"));
        copyFile(new File(defaultConfigDir, "conf/dynarec/strongmem.conf"), new File(dynarecDir, "strongmem.conf"));
        copyFile(new File(defaultConfigDir, "conf/dynarec/wait.conf"), new File(dynarecDir, "wait.conf"));
        copyFile(new File(defaultConfigDir, "conf/dynarec/x87double.conf"), new File(dynarecDir, "x87double.conf"));
        copyFile(new File(defaultConfigDir, "conf/dynarec/callret.conf"), new File(dynarecDir, "callret.conf"));
        copyFile(new File(defaultConfigDir, "conf/dynarec/ignoreint3.conf"), new File(dynarecDir, "ignoreint3.conf"));
        copyFile(new File(defaultConfigDir, "conf/dynarec_preset.conf"), new File(configDir, "dynarec_preset.conf"));
    }

    public String getDynarecPresetText() {
        return readText(new File(configDir, "dynarec_preset.conf"), "export DYNAREC_SETTINGS_SCRIPT=2\nexport DYNAREC_CURRENT_PRESET=4\n");
    }

    private String dynarecFileNameForKey(String key) throws IOException {
        switch (key) {
            case "BOX64_DYNAREC_ALIGNED_ATOMICS":
                return "aligned_atomics.conf";
            case "BOX64_DYNAREC_BIGBLOCK":
                return "bigblock.conf";
            case "BOX64_DYNAREC_FASTNAN":
                return "fastnan.conf";
            case "BOX64_DYNAREC_SAFEFLAGS":
                return "safeflags.conf";
            case "BOX64_DYNAREC_STRONGMEM":
                return "strongmem.conf";
            case "BOX64_DYNAREC_WAIT":
                return "wait.conf";
            case "BOX64_DYNAREC_X87DOUBLE":
                return "x87double.conf";
            case "BOX64_DYNAREC_CALLRET":
                return "callret.conf";
            case "BOX64_DYNAREC_FASTROUND":
                return "fastround.conf";
            case "BOX64_IGNOREINT3":
                return "ignoreint3.conf";
            default:
                throw new IOException("Unknown dynarec key: " + key);
        }
    }

    public void syncPackage(TermuxBoxPackageSpec spec, boolean force, ProgressListener listener) throws IOException {
        ensureDirs();

        Integer localVersion = getInstalledVersion(spec);
        if (!force && localVersion != null && localVersion >= spec.version) {
            if (listener != null) {
                listener.onMessage(spec.name + " is already up to date");
                listener.onProgress(100);
            }
            return;
        }

        File archive = new File(tempDir, spec.name + ".tar.xz");
        File extractDir = new File(tempDir, spec.name);
        deleteRecursively(extractDir);
        deleteFile(archive);
        try {
            downloadFile(spec.url, archive, spec.size, listener);
            verifyDownloadedArchive(spec, archive, listener);
            installPackageFromArchive(spec, archive, extractDir, listener, true);
        } catch (IOException e) {
            deleteFile(archive);
            throw e;
        }
    }

    public void installPackageFromArchive(TermuxBoxPackageSpec spec, File archive, ProgressListener listener) throws IOException {
        File extractDir = new File(tempDir, spec.name + "_local");
        installPackageFromArchive(spec, archive, extractDir, listener, true);
    }

    /**
     * Installs a component already verified and safely extracted by the Games module.
     * The caller retains ownership of the immutable source directory.
     */
    public void installPackageFromPreparedDirectory(TermuxBoxPackageSpec spec,
                                                    File preparedDirectory,
                                                    ProgressListener listener)
        throws IOException {
        if (spec == null || preparedDirectory == null || !preparedDirectory.isDirectory()) {
            throw new IOException("Invalid prepared component directory");
        }
        installPreparedPackage(spec, preparedDirectory, listener);
    }

    public File newTempPackageArchive(String packageName) {
        ensureDirs();
        return new File(tempDir, packageName + "_local.tar.xz");
    }

    private void installPackageFromArchive(TermuxBoxPackageSpec spec, File archive, File extractDir, ProgressListener listener, boolean deleteArchive) throws IOException {
        deleteRecursively(extractDir);
        if (listener != null) {
            listener.onMessage("Extracting " + spec.name);
            listener.onProgress(0);
        }
        extractTarXz(archive, extractDir);
        installPreparedPackage(spec, extractDir, listener);
        if (deleteArchive) {
            deleteFile(archive);
        }
        deleteRecursively(extractDir);
    }

    private void installPreparedPackage(TermuxBoxPackageSpec spec, File preparedDirectory,
                                        ProgressListener listener) throws IOException {
        File glibcSource = new File(preparedDirectory, "glibc");
        if (!glibcSource.isDirectory()) {
            throw new IOException("Invalid package archive: missing glibc directory");
        }
        removePackage(spec);
        if (listener != null) {
            listener.onMessage("Installing " + spec.name);
        }
        // Package archives contain a top-level glibc/ directory and copy it into
        // $PREFIX, so its contents must land under $PREFIX/glibc.
        copyDirectory(glibcSource, glibcDir, listener);
        writeInstalledMetadata(spec, glibcSource);
    }

    public boolean validatePackage(TermuxBoxPackageSpec spec, ProgressListener listener) throws IOException {
        File md5File = new File(installedDir, spec.name + "_md5");
        if (!md5File.isFile()) {
            if (listener != null) {
                listener.onMessage(spec.name + " md5 not found");
            }
            return false;
        }
        Map<String, String> expected = readMd5File(md5File);
        Map<String, String> actual = computeMd5ForFiles(expected.keySet());
        boolean match = expected.equals(actual);
        if (listener != null) {
            listener.onMessage(match ? spec.name + " OK" : spec.name + " mismatch");
            listener.onProgress(match ? 100 : 0);
        }
        if (!match) {
            syncPackage(spec, true, listener);
        }
        return match;
    }

    public void validateAll(ProgressListener listener) throws IOException {
        ensureDirs();
        for (TermuxBoxPackageSpec spec : packages) {
            if (isInstalled(spec)) {
                if (listener != null) {
                    listener.onMessage("Checking " + spec.name);
                }
                validatePackage(spec, listener);
            }
        }
    }

    public void removePackage(TermuxBoxPackageSpec spec) throws IOException {
        File installedVersion = new File(installedDir, spec.name);
        File listFile = new File(installedDir, spec.name + "_lists");
        File md5File = new File(installedDir, spec.name + "_md5");
        if (listFile.isFile()) {
            List<String> lines = readLines(listFile);
            for (String line : lines) {
                String relative = line.trim();
                if (relative.isEmpty()) {
                    continue;
                }
                deleteFile(resolvePackagePath(relative));
            }
        }
        deleteFile(installedVersion);
        deleteFile(listFile);
        deleteFile(md5File);
        if (spec.wine) {
            deleteRecursively(new File(glibcDir, spec.name));
        }
    }

    public List<TermuxBoxPackageSpec> getInstalledWinePackages() {
        List<TermuxBoxPackageSpec> result = new ArrayList<>();
        for (TermuxBoxPackageSpec spec : getWinePackages()) {
            if (new File(glibcDir, spec.name).exists()) {
                result.add(spec);
            }
        }
        return result;
    }

    public File getBoxArchive(String buildName) {
        return new File(boxDir, buildName + ".tar.xz");
    }

    /**
     * Returns whether box64 is currently installed (binary exists under glibc/bin).
     */
    public boolean isBox64Installed() {
        return new File(glibcDir, "bin/box64").isFile();
    }

    /**
     * Returns the detection path for box64 binary relative to the Termux files directory.
     */
    public String getBox64DetectionPath() {
        return "usr/glibc/bin/box64";
    }

    /**
     * Returns the detection path for a wine package directory relative to the Termux files directory.
     */
    public String getWineDetectionPath(TermuxBoxPackageSpec spec) {
        return "usr/glibc/" + spec.name;
    }

    public void ensureDirs() {
        ensureDir(installedDir);
        ensureDir(tempDir);
        ensureDir(containersDir);
        try {
            ensureDefaultConfigSnapshot();
        } catch (IOException ignored) {
        }
    }

    private void ensureDefaultConfigSnapshot() throws IOException {
        ensureDir(termuxBoxDir);
        ensureDir(defaultConfigDir);
        ensureDir(new File(defaultConfigDir, "conf"));
        ensureDir(new File(defaultConfigDir, "conf/dynarec"));

        copyIfMissing(new File(defaultConfigDir, "conf/wine_path.conf"), new File(configDir, "wine_path.conf"), new File(legacyConfDir, "wine_path.conf"), new File(legacyDefaultConfDir, "conf/wine_path.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/cores.conf"), new File(configDir, "cores.conf"), new File(legacyConfDir, "cores.conf"), new File(legacyDefaultConfDir, "conf/cores.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/hud.conf"), new File(configDir, "hud.conf"), new File(legacyConfDir, "hud.conf"), new File(legacyDefaultConfDir, "conf/hud.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/tu_debug.conf"), new File(configDir, "tu_debug.conf"), new File(legacyConfDir, "tu_debug.conf"), new File(legacyDefaultConfDir, "conf/tu_debug.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/wsi_present.conf"), new File(configDir, "wsi_present.conf"), new File(legacyConfDir, "wsi_present.conf"), new File(legacyDefaultConfDir, "conf/wsi_present.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/winedevice_startup.conf"), new File(configDir, "winedevice_startup.conf"), new File(legacyConfDir, "winedevice_startup.conf"), new File(legacyDefaultConfDir, "conf/winedevice_startup.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/virgl.conf"), new File(configDir, "virgl.conf"), new File(legacyConfDir, "virgl.conf"), new File(legacyDefaultConfDir, "conf/virgl.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/force_compatibility.conf"), new File(configDir, "force_compatibility.conf"), new File(legacyConfDir, "force_compatibility.conf"), new File(legacyDefaultConfDir, "conf/force_compatibility.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/wineesync.conf"), new File(configDir, "wineesync.conf"), new File(legacyConfDir, "wineesync.conf"), new File(legacyDefaultConfDir, "conf/wineesync.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/wsi_debug.conf"), new File(configDir, "wsi_debug.conf"), new File(legacyConfDir, "wsi_debug.conf"), new File(legacyDefaultConfDir, "conf/wsi_debug.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/debug.conf"), new File(configDir, "debug.conf"), new File(legacyConfDir, "debug.conf"), new File(legacyDefaultConfDir, "conf/debug.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/path.conf"), new File(configDir, "path.conf"), new File(legacyConfDir, "path.conf"), new File(legacyDefaultConfDir, "conf/path.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec/aligned_atomics.conf"), new File(dynarecDir, "aligned_atomics.conf"), new File(new File(legacyConfDir, "dynarec"), "aligned_atomics.conf"), new File(legacyDefaultConfDir, "conf/dynarec/aligned_atomics.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec/bigblock.conf"), new File(dynarecDir, "bigblock.conf"), new File(new File(legacyConfDir, "dynarec"), "bigblock.conf"), new File(legacyDefaultConfDir, "conf/dynarec/bigblock.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec/fastnan.conf"), new File(dynarecDir, "fastnan.conf"), new File(new File(legacyConfDir, "dynarec"), "fastnan.conf"), new File(legacyDefaultConfDir, "conf/dynarec/fastnan.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec/fastround.conf"), new File(dynarecDir, "fastround.conf"), new File(new File(legacyConfDir, "dynarec"), "fastround.conf"), new File(legacyDefaultConfDir, "conf/dynarec/fastround.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec/safeflags.conf"), new File(dynarecDir, "safeflags.conf"), new File(new File(legacyConfDir, "dynarec"), "safeflags.conf"), new File(legacyDefaultConfDir, "conf/dynarec/safeflags.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec/strongmem.conf"), new File(dynarecDir, "strongmem.conf"), new File(new File(legacyConfDir, "dynarec"), "strongmem.conf"), new File(legacyDefaultConfDir, "conf/dynarec/strongmem.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec/wait.conf"), new File(dynarecDir, "wait.conf"), new File(new File(legacyConfDir, "dynarec"), "wait.conf"), new File(legacyDefaultConfDir, "conf/dynarec/wait.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec/x87double.conf"), new File(dynarecDir, "x87double.conf"), new File(new File(legacyConfDir, "dynarec"), "x87double.conf"), new File(legacyDefaultConfDir, "conf/dynarec/x87double.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec/callret.conf"), new File(dynarecDir, "callret.conf"), new File(new File(legacyConfDir, "dynarec"), "callret.conf"), new File(legacyDefaultConfDir, "conf/dynarec/callret.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec/ignoreint3.conf"), new File(dynarecDir, "ignoreint3.conf"), new File(new File(legacyConfDir, "dynarec"), "ignoreint3.conf"), new File(legacyDefaultConfDir, "conf/dynarec/ignoreint3.conf"));
        copyIfMissing(new File(defaultConfigDir, "conf/dynarec_preset.conf"), new File(configDir, "dynarec_preset.conf"), new File(legacyConfDir, "dynarec_preset.conf"), new File(legacyDefaultConfDir, "conf/dynarec_preset.conf"));
        copyIfMissing(new File(defaultConfigDir, "last-resolution.conf"), new File(configDir, "last-resolution.conf"), new File(optDir, "last-resolution.conf"), new File(legacyDefaultConfDir, "last-resolution.conf"));
        copyIfMissing(new File(defaultConfigDir, "locale.conf"), new File(configDir, "locale.conf"), new File(optDir, "locale.conf"), new File(legacyDefaultConfDir, "locale.conf"));
        copyIfMissing(new File(defaultConfigDir, "dxvk.conf"), new File(configDir, "dxvk.conf"), new File(optDir, "dxvk.conf"), new File(legacyDefaultConfDir, "dxvk.conf"));

        writeFile(new File(defaultConfigDir, "conf/path.conf"), buildPathConf());
        File runtimePathConf = new File(configDir, "path.conf");
        if (!runtimePathConf.isFile() || readText(runtimePathConf, "").contains("/glibc/opt/dxvk.conf") || !readText(runtimePathConf, "").contains("$PREFIX/glibc/termux-box/config/dxvk.conf")) {
            writeFile(runtimePathConf, buildPathConf());
        }
    }

    private void copyIfMissing(File target, File primarySource, File secondarySource, File tertiarySource) throws IOException {
        if (target.isFile()) {
            return;
        }
        if (primarySource != null && primarySource.isFile()) {
            copyFile(target, primarySource);
            return;
        }
        if (secondarySource != null && secondarySource.isFile()) {
            copyFile(target, secondarySource);
            return;
        }
        if (tertiarySource != null && tertiarySource.isFile()) {
            copyFile(target, tertiarySource);
        }
    }

    private String buildPathConf() {
        return "export LOG_PATH=/sdcard/termux-box.log\n" +
            "export VK_ICD_FILENAMES=$PREFIX/glibc/share/vulkan/icd.d/freedreno_icd.aarch64.json\n" +
            "export DXVK_CONFIG_FILE=$PREFIX/glibc/termux-box/config/dxvk.conf\n" +
            "export FONTCONFIG_PATH=$PREFIX/glibc/etc/fonts\n" +
            "export BOX64_PATH=$PREFIX/glibc/bin\n" +
            "export DXVK_ASYNC=1\n" +
            "export VKD3D_FEATURE_LEVEL=12_0\n" +
            "export BOX64_LD_LIBRARY_PATH=$WINE_PATH/lib64:$WINE_PATH/lib64/wine/x86_64-unix:$PREFIX/glibc/lib/x86_64-linux-gnu\n" +
            "export BOX64_MMAP32=1\n" +
            "export tu_allow_oob_indirect_ubo_loads=true\n";
    }

    /**
     * Returns the path to the container start script file.
     * The script is deployed from the app asset {@code start_termux_box.sh}.
     */
    public File getStartScriptFile(TermuxBoxContainerSpec spec) {
        return new File(getContainerDir(spec), "start.sh");
    }

    private void downloadFile(String url, File destination, long maxBytes, ProgressListener listener) throws IOException {
        if (listener != null) {
            listener.onMessage("Downloading " + destination.getName());
            listener.onProgress(0);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + connection.getResponseCode());
        }
        long length = Build.VERSION.SDK_INT >= 24
            ? connection.getContentLengthLong()
            : connection.getContentLength();
        if (maxBytes > 0 && length > maxBytes) {
            throw new IOException("Download exceeds declared size");
        }
        ensureParent(destination);
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new IOException("Download exceeds declared size");
                }
                output.write(buffer, 0, read);
                if (listener != null && length > 0) {
                    listener.onProgress((int) ((total * 100L) / length));
                }
            }
        }
    }

    private void verifyDownloadedArchive(TermuxBoxPackageSpec spec, File archive,
                                         ProgressListener listener) throws IOException {
        if (listener != null) {
            listener.onMessage("Verifying " + spec.name);
        }
        if (archive.length() != spec.size) {
            throw new IOException("Size mismatch for " + spec.name);
        }
        String actualSha256 = getFileDigest(archive, "SHA-256");
        if (!spec.sha256.equalsIgnoreCase(actualSha256)) {
            throw new IOException("SHA-256 mismatch for " + spec.name);
        }
    }

    private void extractTarXz(File archive, File destinationDir) throws IOException {
        ensureDir(destinationDir);
        try (InputStream fileInput = new FileInputStream(archive);
             InputStream xzInput = new XZCompressorInputStream(fileInput);
             TarArchiveInputStream tarInput = new TarArchiveInputStream(xzInput)) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = tarInput.getNextTarEntry()) != null) {
                File outFile = new File(destinationDir, entry.getName());
                if (!isInside(destinationDir, outFile)) {
                    throw new IOException("Blocked path traversal: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    ensureDir(outFile);
                    continue;
                }
                ensureParent(outFile);
                if (entry.isSymbolicLink()) {
                    try {
                        String target = entry.getLinkName();
                        if (target == null || target.isEmpty()) {
                            throw new IOException("Invalid empty symlink target: " + entry.getName());
                        }
                        deleteIfExistsCompat(outFile);
                        createSymlinkCompat(outFile, target);
                    } catch (Exception e) {
                        throw new IOException("Cannot extract symlink: " + entry.getName(), e);
                    }
                    continue;
                }
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                    int read;
                    while ((read = tarInput.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                applyMode(outFile, entry.getMode());
            }
        }
    }

    private void copyDirectory(File sourceDir, File targetRoot, ProgressListener listener) throws IOException {
        List<File> files = listFilesRecursively(sourceDir);
        int total = Math.max(files.size(), 1);
        int index = 0;
        for (File source : files) {
            String relative = relativizePath(sourceDir, source);
            File target = new File(targetRoot, relative);
            ensureParent(target);
            copyFileNoFollowCompat(source, target);
            if (!isSymlinkCompat(source)) {
                applyMode(target, source.canExecute() ? 0755 : 0644);
            }
            index++;
            if (listener != null) {
                listener.onProgress((index * 100) / total);
            }
        }
    }

    private void writeInstalledMetadata(TermuxBoxPackageSpec spec, File glibcSource)
        throws IOException {
        ensureDirs();
        File listFile = new File(installedDir, spec.name + "_lists");
        File md5File = new File(installedDir, spec.name + "_md5");
        List<File> files = listFilesRecursively(glibcSource);
        try (OutputStreamWriter listWriter = new OutputStreamWriter(new FileOutputStream(listFile, false), StandardCharsets.UTF_8);
             OutputStreamWriter md5Writer = new OutputStreamWriter(new FileOutputStream(md5File, false), StandardCharsets.UTF_8)) {
            for (File file : files) {
                String child = relativizePath(glibcSource, file);
                String relative = child.isEmpty() ? "glibc" : "glibc/" + child;
                if (relative.isEmpty()) {
                    continue;
                }
                listWriter.write(relative);
                listWriter.write('\n');
                md5Writer.write(getFileMd5(file));
                md5Writer.write("  ");
                md5Writer.write(relative);
                md5Writer.write('\n');
            }
        }
        writeFile(new File(installedDir, spec.name), String.valueOf(spec.version) + "\n");
    }

    private Map<String, String> readMd5File(File file) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> lines = readLines(file);
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            int split = line.indexOf("  ");
            if (split < 0) {
                continue;
            }
            String hash = line.substring(0, split).trim();
            String relative = line.substring(split + 2);
            result.put(relative, hash);
        }
        return result;
    }

    private Map<String, String> computeMd5ForFiles(Iterable<String> relativePaths) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String relative : relativePaths) {
            File file = resolvePackagePath(relative);
            if (!file.exists() && !isSymlinkCompat(file)) {
                result.put(relative, "");
                continue;
            }
            result.put(relative, getFileMd5(file));
        }
        return result;
    }

    private File resolvePackagePath(String relative) throws IOException {
        if (relative == null || relative.isEmpty() || new File(relative).isAbsolute() || relative.indexOf('\\') >= 0) {
            throw new IOException("Invalid package path: " + relative);
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Invalid package path: " + relative);
            }
        }
        // Do not canonicalize this path: glibc-prefix intentionally contains
        // circular and dangling symlinks, and canonicalization dereferences them.
        return new File(prefixDir, relative);
    }

    private String getFileMd5(File file) throws IOException {
        if (isSymlinkCompat(file)) {
            try {
                String target = Build.VERSION.SDK_INT >= 26
                    ? Files.readSymbolicLink(file.toPath()).toString()
                    : Os.readlink(file.getAbsolutePath());
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] bytes = digest.digest(target.getBytes(StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder();
                for (byte value : bytes) {
                    result.append(String.format(Locale.US, "%02x", value));
                }
                return result.toString();
            } catch (Exception e) {
                throw new IOException("Cannot hash symlink: " + file, e);
            }
        }
        return getFileDigest(file, "MD5");
    }

    private String getFileDigest(File file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private String readStream(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Package index is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private List<File> listFilesRecursively(File root) {
        List<File> result = new ArrayList<>();
        if (root == null || !root.exists()) {
            return result;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return result;
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (child.isDirectory() && !isSymlinkCompat(child)) {
                result.addAll(listFilesRecursively(child));
            } else {
                result.add(child);
            }
        }
        return result;
    }

    private void copyFile(File destination, File source) throws IOException {
        ensureParent(destination);
        copyFileCompat(source, destination);
    }

    private void deleteFile(File file) {
        if (file == null) {
            return;
        }
        try {
            deleteIfExistsCompat(file);
        } catch (IOException ignored) {
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || (!file.exists() && !isSymlinkCompat(file))) {
            return;
        }
        if (file.isDirectory() && !isSymlinkCompat(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        deleteFile(file);
    }

    private void writeFile(File file, String content) throws IOException {
        ensureParent(file);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readFile(File file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private void ensureDir(File dir) {
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
    }

    private void ensureParent(File file) {
        if (file != null) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
        }
    }

    private boolean isInside(File root, File child) throws IOException {
        String rootPath = root.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
    }

    private void applyMode(File file, int mode) {
        if (file == null) {
            return;
        }
        boolean executable = (mode & 0111) != 0;
        file.setReadable(true, false);
        file.setWritable(true, true);
        file.setExecutable(executable, false);
    }

    // ---- File helpers with API 21 fallbacks for java.nio.file.Files (API 26+) ----

    /** Reads all lines from a file. Uses Files.readAllLines on API 26+, fallback on older. */
    private List<String> readLines(File file) throws IOException {
        if (Build.VERSION.SDK_INT >= 26) {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    /** Copies a file. Uses Files.copy on API 26+, fallback on older. */
    private void copyFileCompat(File source, File dest) throws IOException {
        if (Build.VERSION.SDK_INT >= 26) {
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        ensureParent(dest);
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /** Copies a file, following symlinks. Uses Files.copy on API 26+, fallback on older. */
    private void copyFileNoFollowCompat(File source, File dest) throws IOException {
        if (isSymlinkCompat(source)) {
            deleteIfExistsCompat(dest);
            createSymlinkCompat(dest, readSymlinkTargetCompat(source));
        } else {
            copyFileCompat(source, dest);
        }
    }

    /** Deletes a file if it exists. Uses Files.deleteIfExists on API 26+, fallback on older. */
    private void deleteIfExistsCompat(File file) throws IOException {
        if (Build.VERSION.SDK_INT >= 26) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        if (file != null && file.exists()) file.delete();
    }

    /** Checks if a file is a symbolic link. Uses Files.isSymbolicLink on API 26+, fallback on older. */
    private boolean isSymlinkCompat(File file) {
        if (Build.VERSION.SDK_INT >= 26) {
            return Files.isSymbolicLink(file.toPath());
        }
        if (file == null) return false;
        try {
            File canon = file.getParent() == null ? file : new File(file.getParentFile().getCanonicalFile(), file.getName());
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }

    /** Creates a symbolic link. Uses Files.createSymbolicLink on API 26+, fallback on older. */
    private String readSymlinkTargetCompat(File link) throws IOException {
        if (Build.VERSION.SDK_INT >= 26) {
            return Files.readSymbolicLink(link.toPath()).toString();
        }
        try {
            return Os.readlink(link.getAbsolutePath());
        } catch (Exception e) {
            throw new IOException("Cannot read symlink: " + link, e);
        }
    }

    /** Creates a symbolic link without rewriting a relative target as an absolute staging path. */
    private void createSymlinkCompat(File link, String target) throws IOException {
        if (Build.VERSION.SDK_INT >= 26) {
            Files.createSymbolicLink(link.toPath(), new File(target).toPath());
            return;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ln", "-sf", target, link.getAbsolutePath()});
            p.waitFor();
            if (p.exitValue() != 0) throw new IOException("ln failed: " + p.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ln interrupted", e);
        }
    }

    /** Computes relative path between two files. Uses Path.relativize on API 26+, URI fallback on older. */
    private static String relativizePath(File base, File child) {
        if (Build.VERSION.SDK_INT >= 26) {
            return base.toPath().relativize(child.toPath()).toString();
        }
        return base.toURI().relativize(child.toURI()).getPath();
    }

}
