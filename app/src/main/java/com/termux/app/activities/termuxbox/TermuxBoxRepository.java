package com.termux.app.activities.termuxbox;

import com.termux.shared.termux.TermuxConstants;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    private static final String PROJECT_ID = "54240888";

    private final File filesDir;
    private final File glibcDir;
    private final File optDir;
    private final File confDir;
    private final File dynarecDir;
    private final File packageManagerDir;
    private final File installedDir;
    private final File tempDir;
    private final File boxDir;
    private final File prefixDir;

    private final List<TermuxBoxPackageSpec> packages;

    public TermuxBoxRepository() {
        this.filesDir = new File(TermuxConstants.TERMUX_FILES_DIR_PATH);
        this.glibcDir = new File(filesDir, "usr/glibc");
        this.optDir = new File(glibcDir, "opt");
        this.confDir = new File(optDir, "conf");
        this.dynarecDir = new File(confDir, "dynarec");
        this.packageManagerDir = new File(optDir, "package-manager");
        this.installedDir = new File(packageManagerDir, "installed");
        this.tempDir = new File(packageManagerDir, "temp");
        this.boxDir = new File(optDir, "box");
        this.prefixDir = glibcDir;
        this.packages = Collections.unmodifiableList(Arrays.asList(
            new TermuxBoxPackageSpec("box64-binaries", 10, false),
            new TermuxBoxPackageSpec("dxvk", 2, false),
            new TermuxBoxPackageSpec("glibc-prefix", 2, false),
            new TermuxBoxPackageSpec("prefix-apps", 2, false),
            new TermuxBoxPackageSpec("scripts", 27, false),
            new TermuxBoxPackageSpec("turnip", 8, false),
            new TermuxBoxPackageSpec("virgl-mesa", 1, false),
            new TermuxBoxPackageSpec("wined3d", 1, false),
            new TermuxBoxPackageSpec("wine-9.0-staging-wow64", 1, true),
            new TermuxBoxPackageSpec("wine-8.18-staging-wow64", 1, true),
            new TermuxBoxPackageSpec("wine-8.18-vanilla-wow64", 1, true),
            new TermuxBoxPackageSpec("wine-9.1-vanilla-wow64", 2, true),
            new TermuxBoxPackageSpec("wine-9.2-vanilla-wow64", 1, true),
            new TermuxBoxPackageSpec("wine-9.3-vanilla-wow64", 1, true),
            new TermuxBoxPackageSpec("libudev", 1, false),
            new TermuxBoxPackageSpec("en-ru-locale", 1, false)
        ));
    }

    public List<TermuxBoxPackageSpec> getPackages() {
        return packages;
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

    public String getCurrentWineContainerName() {
        File conf = new File(confDir, "wine_path.conf");
        if (!conf.isFile()) {
            return null;
        }
        try {
            String text = readFile(conf);
            int index = text.indexOf("/glibc/");
            if (index < 0) {
                return null;
            }
            String tail = text.substring(index + "/glibc/".length());
            int end = tail.indexOf('\n');
            if (end >= 0) {
                tail = tail.substring(0, end);
            }
            if (tail.contains("/")) {
                tail = tail.substring(0, tail.indexOf('/'));
            }
            return tail.trim().isEmpty() ? null : tail.trim();
        } catch (IOException e) {
            return null;
        }
    }

    public void setWineContainer(TermuxBoxPackageSpec spec) throws IOException {
        ensureParent(confDir);
        writeFile(new File(confDir, "wine_path.conf"),
            "export WINE_PATH=" + filesDir.getAbsolutePath() + "/usr/glibc/" + spec.name + "\n" +
                "export WINEPREFIX=" + filesDir.getAbsolutePath() + "/usr/glibc/" + spec.name + "/.wine\n");
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
    }

    public void setFallbackResolution(String resolution) throws IOException {
        writeFile(new File(optDir, "last-resolution.conf"), resolution.trim() + "\n");
    }

    public String getFallbackResolution() {
        return readText(new File(optDir, "last-resolution.conf"), "1280x720");
    }

    public void setLocale(String locale) throws IOException {
        if (!locale.endsWith(".utf8")) {
            locale = locale + ".utf8";
        }
        writeFile(new File(optDir, "locale.conf"), locale.trim() + "\n");
    }

    public String getLocale() {
        String value = readText(new File(optDir, "locale.conf"), "en_US.utf8");
        return value.trim();
    }

    public void setCorePreset(int primaryStart, int primaryEnd, int secondaryStart, int secondaryEnd) throws IOException {
        writeFile(new File(confDir, "cores.conf"),
            "export PRIMARY_CORES=" + primaryStart + "-" + primaryEnd + "\n" +
                "export SECONDARY_CORES=" + secondaryStart + "-" + secondaryEnd + "\n");
    }

    public void setCorePresetByPreset(int preset) throws IOException {
        switch (preset) {
            case 2:
                setCorePreset(6, 7, 0, 5);
                return;
            case 3:
                setCorePreset(5, 7, 0, 4);
                return;
            case 4:
                setCorePreset(4, 7, 0, 3);
                return;
            case 5:
                setCorePreset(3, 7, 0, 2);
                return;
            case 6:
                setCorePreset(2, 7, 0, 1);
                return;
            case 7:
                setCorePreset(1, 7, 0, 1);
                return;
            case 8:
            default:
                setCorePreset(0, 7, 0, 1);
        }
    }

    public String getCorePresetSelection() {
        String text = readText(new File(confDir, "cores.conf"), "");
        if (text.contains("PRIMARY_CORES=6-7") && text.contains("SECONDARY_CORES=0-5")) {
            return "2";
        }
        if (text.contains("PRIMARY_CORES=5-7") && text.contains("SECONDARY_CORES=0-4")) {
            return "3";
        }
        if (text.contains("PRIMARY_CORES=4-7") && text.contains("SECONDARY_CORES=0-3")) {
            return "4";
        }
        if (text.contains("PRIMARY_CORES=3-7") && text.contains("SECONDARY_CORES=0-2")) {
            return "5";
        }
        if (text.contains("PRIMARY_CORES=2-7") && text.contains("SECONDARY_CORES=0-1")) {
            return "6";
        }
        if (text.contains("PRIMARY_CORES=1-7") && text.contains("SECONDARY_CORES=0-1")) {
            return "7";
        }
        return "8";
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
        writeFile(new File(confDir, "hud.conf"), content);
    }

    public String getHudPresetSelection() {
        String text = readText(new File(confDir, "hud.conf"), "");
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
        writeFile(new File(confDir, "tu_debug.conf"), content);
    }

    public String getTuDebugPresetSelection() {
        String text = readText(new File(confDir, "tu_debug.conf"), "");
        if (text.contains("noconform,syncdraw,flushall")) {
            return "flushall";
        }
        if (text.contains("noconform,syncdraw")) {
            return "syncdraw";
        }
        return "noconform";
    }

    public void resetSystemSettings() throws IOException {
        copyFile(new File(confDir, "cores.conf"), new File(optDir, "default-conf/conf/cores.conf"));
        copyFile(new File(confDir, "hud.conf"), new File(optDir, "default-conf/conf/hud.conf"));
        copyFile(new File(confDir, "tu_debug.conf"), new File(optDir, "default-conf/conf/tu_debug.conf"));
        copyFile(new File(confDir, "wsi_present.conf"), new File(optDir, "default-conf/conf/wsi_present.conf"));
        copyFile(new File(confDir, "winedevice_startup.conf"), new File(optDir, "default-conf/conf/winedevice_startup.conf"));
        copyFile(new File(confDir, "virgl.conf"), new File(optDir, "default-conf/conf/virgl.conf"));
        copyFile(new File(confDir, "force_compatibility.conf"), new File(optDir, "default-conf/conf/force_compatibility.conf"));
        copyFile(new File(confDir, "wineesync.conf"), new File(optDir, "default-conf/conf/wineesync.conf"));
        copyFile(new File(confDir, "wsi_debug.conf"), new File(optDir, "default-conf/conf/wsi_debug.conf"));
        copyFile(new File(confDir, "debug.conf"), new File(optDir, "default-conf/conf/debug.conf"));
        copyFile(new File(confDir, "path.conf"), new File(optDir, "default-conf/conf/path.conf"));
        copyFile(new File(optDir, "last-resolution.conf"), new File(optDir, "default-conf/last-resolution.conf"));
        copyFile(new File(optDir, "locale.conf"), new File(optDir, "default-conf/locale.conf"));
        copyFile(new File(optDir, "dxvk.conf"), new File(optDir, "default-conf/dxvk.conf"));
    }

    public void setDynarecPresetMode(int mode) throws IOException {
        writeFile(new File(confDir, "dynarec_preset.conf"),
            "export DYNAREC_SETTINGS_SCRIPT=" + mode + "\n" +
                "export DYNAREC_CURRENT_PRESET=" + (mode == 1 ? "none" : "4") + "\n");
    }

    public String getDynarecPresetSelection() {
        String text = readText(new File(confDir, "dynarec_preset.conf"), "export DYNAREC_SETTINGS_SCRIPT=2\nexport DYNAREC_CURRENT_PRESET=4\n");
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
        writeFile(new File(confDir, "dynarec_preset.conf"),
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
        copyFile(new File(dynarecDir, "aligned_atomics.conf"), new File(optDir, "default-conf/conf/dynarec/aligned_atomics.conf"));
        copyFile(new File(dynarecDir, "bigblock.conf"), new File(optDir, "default-conf/conf/dynarec/bigblock.conf"));
        copyFile(new File(dynarecDir, "fastnan.conf"), new File(optDir, "default-conf/conf/dynarec/fastnan.conf"));
        copyFile(new File(dynarecDir, "fastround.conf"), new File(optDir, "default-conf/conf/dynarec/fastround.conf"));
        copyFile(new File(dynarecDir, "safeflags.conf"), new File(optDir, "default-conf/conf/dynarec/safeflags.conf"));
        copyFile(new File(dynarecDir, "strongmem.conf"), new File(optDir, "default-conf/conf/dynarec/strongmem.conf"));
        copyFile(new File(dynarecDir, "wait.conf"), new File(optDir, "default-conf/conf/dynarec/wait.conf"));
        copyFile(new File(dynarecDir, "x87double.conf"), new File(optDir, "default-conf/conf/dynarec/x87double.conf"));
        copyFile(new File(dynarecDir, "callret.conf"), new File(optDir, "default-conf/conf/dynarec/callret.conf"));
        copyFile(new File(dynarecDir, "ignoreint3.conf"), new File(optDir, "default-conf/conf/dynarec/ignoreint3.conf"));
        copyFile(new File(confDir, "dynarec_preset.conf"), new File(optDir, "default-conf/conf/dynarec_preset.conf"));
    }

    public String getDynarecPresetText() {
        return readText(new File(confDir, "dynarec_preset.conf"), "export DYNAREC_SETTINGS_SCRIPT=2\nexport DYNAREC_CURRENT_PRESET=4\n");
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
        downloadArchive(spec.name + ".tar.xz", archive, listener);
        extractTarXz(archive, extractDir);
        removePackage(spec);
        if (listener != null) {
            listener.onMessage("Installing " + spec.name);
        }
        File glibcSource = new File(extractDir, "glibc");
        if (glibcSource.exists()) {
            copyDirectory(glibcSource, filesDir, listener);
        }
        writeInstalledMetadata(spec, extractDir);
        deleteFile(archive);
        deleteRecursively(extractDir);
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
            List<String> lines = Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String relative = line.trim();
                if (relative.isEmpty()) {
                    continue;
                }
                deleteFile(new File(filesDir, relative));
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

    public String getPatchNotes() {
        return "Mar 3\n" +
            "Updated box64, box64rc, dxvk.conf, locale.conf, path.conf\n" +
            "Added en-ru-locale package that fixes unicode issues in wine\n" +
            "Locale is set to en_US.utf8 by default\n" +
            "Wine is 9.3 by default\n" +
            "Added turnip v6.5, it's default now, no flickering, no mem leaks\n" +
            "Removed turnip v5.5 and v6\n" +
            "Fixed some games that needed nouboopt debug option\n" +
            "Added feb 14 box64\n" +
            "Updated glibc to 2.39\n" +
            "Fixed GTA V memory leak";
    }

    public File getBoxArchive(String buildName) {
        return new File(boxDir, buildName + ".tar.xz");
    }

    public void ensureDirs() {
        ensureDir(installedDir);
        ensureDir(tempDir);
    }

    private void downloadArchive(String fileName, File destination, ProgressListener listener) throws IOException {
        String encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
        String url = "https://gitlab.com/api/v4/projects/" + PROJECT_ID + "/repository/files/" + encoded + "/raw?ref=main";
        if (listener != null) {
            listener.onMessage("Downloading " + fileName);
            listener.onProgress(0);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + connection.getResponseCode());
        }
        int length = connection.getContentLength();
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                total += read;
                if (listener != null && length > 0) {
                    listener.onProgress((int) ((total * 100L) / length));
                }
            }
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
                    Path link = outFile.toPath();
                    Path target = Paths.get(entry.getLinkName());
                    try {
                        Files.deleteIfExists(link);
                        Files.createSymbolicLink(link, target);
                    } catch (Exception e) {
                        writeFile(outFile, entry.getLinkName() + "\n");
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
            String relative = sourceDir.toPath().relativize(source.toPath()).toString();
            File target = new File(targetRoot, relative);
            ensureParent(target);
            Files.copy(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            applyMode(target, source.canExecute() ? 0755 : 0644);
            index++;
            if (listener != null) {
                listener.onProgress((index * 100) / total);
            }
        }
    }

    private void writeInstalledMetadata(TermuxBoxPackageSpec spec, File extractDir) throws IOException {
        ensureDirs();
        File listFile = new File(installedDir, spec.name + "_lists");
        File md5File = new File(installedDir, spec.name + "_md5");
        List<File> files = listFilesRecursively(extractDir);
        try (OutputStreamWriter listWriter = new OutputStreamWriter(new FileOutputStream(listFile, false), StandardCharsets.UTF_8);
             OutputStreamWriter md5Writer = new OutputStreamWriter(new FileOutputStream(md5File, false), StandardCharsets.UTF_8)) {
            for (File file : files) {
                String relative = extractDir.toPath().relativize(file.toPath()).toString();
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
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
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
            File file = new File(filesDir, relative);
            if (!file.exists()) {
                result.put(relative, "");
                continue;
            }
            result.put(relative, getFileMd5(file));
        }
        return result;
    }

    private String getFileMd5(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
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
            if (child.isDirectory() && !Files.isSymbolicLink(child.toPath())) {
                result.addAll(listFilesRecursively(child));
            } else {
                result.add(child);
            }
        }
        return result;
    }

    private void copyFile(File destination, File source) throws IOException {
        ensureParent(destination);
        Files.copy(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void deleteFile(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
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
}
