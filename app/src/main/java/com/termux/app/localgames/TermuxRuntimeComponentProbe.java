package com.termux.app.localgames;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** Read-only probe for components active in the TermuxBox glibc runtime. */
final class TermuxRuntimeComponentProbe {

    private final File glibcDirectory;
    private final File termuxFilesDirectory;

    TermuxRuntimeComponentProbe(File termuxFilesDirectory) {
        if (termuxFilesDirectory == null) {
            throw new IllegalArgumentException("termuxFilesDirectory required");
        }
        this.termuxFilesDirectory = termuxFilesDirectory;
        glibcDirectory = new File(termuxFilesDirectory, "usr/glibc");
    }

    boolean isAvailable(String componentId) {
        return isAvailable(componentId, null);
    }

    boolean isAvailable(String componentId, int expectedVersion) {
        if (expectedVersion < 1) return false;
        return isAvailable(componentId, Integer.valueOf(expectedVersion));
    }

    private boolean isAvailable(String componentId, Integer expectedVersion) {
        if (componentId == null || !componentId.matches("[A-Za-z0-9._-]+")) return false;
        if ("proot".equals(componentId)) {
            return nonEmpty(new File(termuxFilesDirectory, "usr/bin/proot"));
        }
        if ("proot-distro".equals(componentId)) {
            return nonEmpty(new File(termuxFilesDirectory, "usr/bin/proot-distro"));
        }
        if ("termux-x11".equals(componentId)) {
            return executable(new File(termuxFilesDirectory, "usr/bin/termux-x11")) &&
                nonEmpty(new File(termuxFilesDirectory,
                    "usr/libexec/termux-x11/loader.apk"));
        }
        if ("virgl-server".equals(componentId)) {
            return executable(new File(glibcDirectory,
                "opt/virgl/libvirgl_test_server.so"));
        }
        return hasInstallMetadata(componentId, expectedVersion) &&
            hasRuntimeCapability(componentId);
    }

    private boolean hasInstallMetadata(String componentId, Integer expectedVersion) {
        File[] roots = {
            new File(glibcDirectory, "termux-box/package-manager/installed"),
            new File(glibcDirectory, "opt/package-manager/installed")
        };
        for (File root : roots) {
            File version = new File(root, componentId);
            File list = new File(root, componentId + "_lists");
            File digest = new File(root, componentId + "_md5");
            if (matchingVersion(version, expectedVersion) && nonEmpty(list) &&
                nonEmpty(digest)) return true;
        }
        return false;
    }

    private boolean hasRuntimeCapability(String componentId) {
        switch (componentId) {
            case "scripts":
                return new File(glibcDirectory, "opt/scripts").isDirectory();
            case "glibc-prefix":
                return nonEmpty(new File(glibcDirectory, "lib/libc.so.6"));
            case "box64-binaries":
                return nonEmpty(new File(glibcDirectory, "bin/box64"));
            case "prefix-apps":
                return new File(glibcDirectory, "opt/prefix").isDirectory();
            case "libudev":
                return nonEmpty(new File(glibcDirectory, "lib/libudev.so.1"));
            case "en-ru-locale":
                return new File(glibcDirectory, "lib/locale").isDirectory();
            case "wine-fonts":
                // Shared Wine fallback fonts are published straight into GLIBC fontconfig's
                // scan directory; unlike Wine engines they do not contain a bin/wine payload.
                return nonEmpty(new File(glibcDirectory,
                    "share/fonts/NotoSansCJK-Regular.ttc"));
            case "turnip":
                return nonEmpty(new File(glibcDirectory,
                    "share/vulkan/icd.d/freedreno_icd.aarch64.json"));
            case "virgl-mesa":
                return nonEmpty(new File(glibcDirectory,
                    "opt/virgl/libvirgl_test_server.so"));
            case "dxvk":
            case "wined3d":
                return new File(glibcDirectory, "opt/libs/d3d").isDirectory() ||
                    new File(glibcDirectory, "opt/prefix/d3d").isDirectory();
            default:
                return componentId.startsWith("wine-") && nonEmpty(
                    new File(glibcDirectory, componentId + "/bin/wine"));
        }
    }

    private static boolean matchingVersion(File file, Integer expectedVersion) {
        if (!file.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String value = reader.readLine();
            if (value == null) return false;
            int actual = Integer.parseInt(value.trim());
            return actual > 0 && (expectedVersion == null || actual == expectedVersion);
        } catch (IOException | NumberFormatException error) {
            return false;
        }
    }

    private static boolean nonEmpty(File file) {
        return file.isFile() && file.length() > 0;
    }

    private static boolean executable(File file) {
        return nonEmpty(file) && file.canExecute();
    }
}
