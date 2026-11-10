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
        if ("xlorie".equals(componentId)) {
            // The independent Xlorie X server package; the kwin/termux-render pieces it talks to
            // are separate packages, the executable itself is what a launch needs.
            return executable(new File(termuxFilesDirectory, "usr/bin/Xlorie"));
        }
        if ("virgl-server".equals(componentId)) {
            return executable(new File(glibcDirectory,
                "opt/virgl/libvirgl_test_server.so"));
        }
        if ("termux-glibc".equals(componentId)) {
            for (String tool : new String[] {"7z", "zip", "unzip", "tar", "gzip", "bzip2", "xz", "zstd"}) {
                if (!executable(new File(termuxFilesDirectory, "usr/bin/" + tool))) return false;
            }
            // Wine's X11 driver needs libX11/libXrender/XRandR/FreeType beyond the base glibc
            // loader to render a visible window against Xlorie; require them here so an
            // incomplete runtime is reported as not-ready instead of appearing available.
            return com.termux.ui.wine.runtime.NativeGlibcLocale.isReady(glibcDirectory) &&
                executable(new File(termuxFilesDirectory, "usr/bin/python")) &&
                nonEmpty(new File(glibcDirectory, "lib/ld-linux-aarch64.so.1")) &&
                nonEmpty(new File(glibcDirectory, "lib/libc.so.6")) &&
                executable(new File(termuxFilesDirectory, "usr/bin/grun")) &&
                nonEmpty(new File(glibcDirectory, "lib/libX11.so.6")) &&
                nonEmpty(new File(glibcDirectory, "lib/libXrender.so.1")) &&
                nonEmpty(new File(glibcDirectory, "lib/libXrandr.so.2")) &&
                nonEmpty(new File(glibcDirectory, "lib/libfreetype.so.6"));
        }
        String graphicsArchive = com.termux.ui.components.install.ComponentMaintenance.variantArchive(componentId);
        if (graphicsArchive != null) return nonEmpty(new File(termuxFilesDirectory, "usr/" + graphicsArchive));
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
            case "glibc_scripts_29":
                return new File(glibcDirectory, "opt/scripts").isDirectory();
            case "glibc_box64_0.2.8":
            case "glibc_box64_0.3.8":
            case "glibc_box64_0.4.4":
                return executable(new File(glibcDirectory,
                    com.termux.ui.wine.runtime.NativeBox64Versions.relativeDirectory(componentId) + "/bin/box64"));
            case "glibc_prefix-apps_3":
                return new File(glibcDirectory, "opt/prefix").isDirectory();
            case "libudev":
                return nonEmpty(new File(glibcDirectory, "lib/libudev.so.1"));
            case "en-ru-locale":
                return new File(glibcDirectory, "lib/locale").isDirectory();
            case "glibc_fonts_1":
                // Shared Wine fallback fonts are published straight into GLIBC fontconfig's
                // scan directory; unlike Wine engines they do not contain a bin/wine payload.
                return nonEmpty(new File(glibcDirectory,
                    "share/fonts/NotoSansCJK-Regular.ttc"));
            case "virgl-mesa":
                return nonEmpty(new File(glibcDirectory,
                    "opt/virgl/libvirgl_test_server.so"));
            case "wined3d":
                return new File(glibcDirectory, "opt/libs/d3d").isDirectory() ||
                    new File(glibcDirectory, "opt/prefix/d3d").isDirectory();
            default:
                return (componentId.startsWith("glibc_wine_") ||
                    componentId.startsWith("glibc_proton_")) && nonEmpty(
                    new File(glibcDirectory, componentId + "/bin/wine"));
        }
    }

    private static boolean matchingVersion(File file, Integer expectedVersion) {
        if (!file.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String value = reader.readLine();
            if (value == null) return false;
            int actual = Integer.parseInt(value.trim());
            return actual > 0;
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
