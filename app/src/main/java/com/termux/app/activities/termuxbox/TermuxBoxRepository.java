package com.termux.app.activities.termuxbox;

import android.content.Context;
import android.os.Build;
import android.system.Os;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

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

/** Native component activation and read-only import of old container configurations. */
public final class TermuxBoxRepository {

    public interface ProgressListener {
        void onMessage(String message);
        void onProgress(int progress);
    }

    private static final String PACKAGE_INDEX_ASSET = "termux-app-packages/index-v1.json";
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
    private final File prefixDir;

    private volatile List<TermuxBoxPackageSpec> packages;

    public TermuxBoxRepository(Context context) {
        this(context, new File(TermuxConstants.TERMUX_FILES_DIR_PATH));
    }

    TermuxBoxRepository(Context context, File filesDirectory) {
        this.context = context.getApplicationContext();
        this.filesDir = filesDirectory;
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
        this.packages = loadInitialPackageIndex();
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
            // The :termux-ui module ships this asset path and it merges into this APK, so
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

    private List<TermuxBoxPackageSpec> getWinePackages() {
        List<TermuxBoxPackageSpec> result = new ArrayList<>();
        for (TermuxBoxPackageSpec spec : packages) {
            if (spec.wine) {
                result.add(spec);
            }
        }
        return result;
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

    private String resolveDefaultWinePackage() {
        for (TermuxBoxPackageSpec spec : getInstalledWinePackages()) {
            return spec.name;
        }
        for (TermuxBoxPackageSpec spec : getWinePackages()) {
            return spec.name;
        }
        return "glibc_wine_9.3-vanilla";
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

    private String readText(File file, String defaultValue) {
        try {
            return readFile(file);
        } catch (IOException e) {
            return defaultValue;
        }
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

    private void installPreparedPackage(TermuxBoxPackageSpec spec, File preparedDirectory,
                                        ProgressListener listener) throws IOException {
        File glibcSource = new File(preparedDirectory, "glibc");
        if (!glibcSource.isDirectory()) {
            throw new IOException("Invalid package archive: missing glibc directory");
        }
        if (com.termux.ui.wine.runtime.NativeBox64Versions.isBuiltIn(spec.name)) {
            installBox64Version(spec, glibcSource, listener);
            return;
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

    private void installBox64Version(TermuxBoxPackageSpec spec, File source,
                                     ProgressListener listener) throws IOException {
        File binary = new File(source, "bin/box64");
        if (!binary.isFile() || binary.length() == 0 || !binary.canExecute() || isSymlinkCompat(binary)) {
            throw new IOException("box64_package_binary_missing:" + spec.name);
        }
        String relative = com.termux.ui.wine.runtime.NativeBox64Versions.relativeDirectory(spec.name);
        File destination = new File(glibcDir, relative);
        ensureDir(destination.getParentFile());
        File stage = new File(destination.getParentFile(), spec.name + ".stage." + java.util.UUID.randomUUID());
        try {
            copyDirectory(source, stage, listener);
            File stagedBinary = new File(stage, "bin/box64");
            if (!stagedBinary.isFile() || stagedBinary.length() != binary.length() || !stagedBinary.canExecute()) {
                throw new IOException("box64_package_copy_failed:" + spec.name);
            }
            // Never remove files listed by old shared-bin receipts or another build.
            deleteRecursively(destination);
            if (!stage.renameTo(destination)) throw new IOException("box64_package_publish_failed:" + spec.name);
            writeInstalledMetadata(spec, source, "glibc/" + relative);
        } finally {
            deleteRecursively(stage);
        }
    }

    private void removePackage(TermuxBoxPackageSpec spec) throws IOException {
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

    private List<TermuxBoxPackageSpec> getInstalledWinePackages() {
        List<TermuxBoxPackageSpec> result = new ArrayList<>();
        for (TermuxBoxPackageSpec spec : getWinePackages()) {
            if (new File(glibcDir, spec.name).exists()) {
                result.add(spec);
            }
        }
        return result;
    }

    private void ensureDirs() {
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
        writeInstalledMetadata(spec, glibcSource, "glibc");
    }

    private void writeInstalledMetadata(TermuxBoxPackageSpec spec, File glibcSource,
                                        String installedRoot) throws IOException {
        ensureDirs();
        File listFile = new File(installedDir, spec.name + "_lists");
        File md5File = new File(installedDir, spec.name + "_md5");
        List<File> files = listFilesRecursively(glibcSource);
        try (OutputStreamWriter listWriter = new OutputStreamWriter(new FileOutputStream(listFile, false), StandardCharsets.UTF_8);
             OutputStreamWriter md5Writer = new OutputStreamWriter(new FileOutputStream(md5File, false), StandardCharsets.UTF_8)) {
            for (File file : files) {
                String child = relativizePath(glibcSource, file);
                String relative = child.isEmpty() ? installedRoot : installedRoot + "/" + child;
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

    private File resolvePackagePath(String relative) throws IOException {
        if (relative == null || relative.isEmpty() || new File(relative).isAbsolute() || relative.indexOf('\\') >= 0) {
            throw new IOException("Invalid package path: " + relative);
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Invalid package path: " + relative);
            }
        }
        // Do not canonicalize this path: runtime archives can contain circular and
        // dangling symlinks, and canonicalization dereferences them.
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
