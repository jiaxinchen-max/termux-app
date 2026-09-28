package com.termux.app.localgames;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class TermuxRuntimeComponentProbeTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void recognizesLegacyMetadataOnlyWhenRuntimeCapabilityExists() throws Exception {
        File files = temporary.newFolder("files");
        installMetadata(files, "opt/package-manager/installed", "box64-binaries", "10");
        installMetadata(files, "opt/package-manager/installed", "box64-proot-v0.4.4", "1");
        TermuxRuntimeComponentProbe probe = new TermuxRuntimeComponentProbe(files);

        assertFalse(probe.isAvailable("box64-binaries"));
        assertFalse(probe.isAvailable("box64-proot-v0.4.4"));

        write(new File(files, "usr/glibc/bin/box64"), "binary");
        assertTrue(probe.isAvailable("box64-binaries"));
        assertTrue(probe.isAvailable("box64-binaries", 10));
        assertFalse(probe.isAvailable("box64-binaries", 9));
        assertTrue(probe.isAvailable("box64-proot-v0.4.4"));
        assertTrue(probe.isAvailable("box64-proot-v0.4.4", 1));
    }

    @Test
    public void recognizesCurrentMetadataAndWineCapability() throws Exception {
        File files = temporary.newFolder("files");
        installMetadata(files, "termux-box/package-manager/installed",
            "wine-9.3-vanilla-wow64", "1");
        write(new File(files, "usr/glibc/wine-9.3-vanilla-wow64/bin/wine"), "binary");

        assertTrue(new TermuxRuntimeComponentProbe(files)
            .isAvailable("wine-9.3-vanilla-wow64"));
    }

    @Test
    public void recognizesOfficialTermuxGlibcRuntimeWithoutComponentMetadata() throws Exception {
        File files = temporary.newFolder("glibc-files");
        TermuxRuntimeComponentProbe probe = new TermuxRuntimeComponentProbe(files);

        assertFalse(probe.isAvailable("termux-glibc-runtime"));
        write(new File(files, "usr/glibc/lib/ld-linux-aarch64.so.1"), "loader");
        write(new File(files, "usr/glibc/lib/libc.so.6"), "libc");
        File runner = new File(files, "usr/bin/grun");
        write(runner, "runner");
        assertTrue(runner.setExecutable(true, false));

        // Wine's X11 driver also needs libX11/libXrender/FreeType to render a visible
        // window; the base loader alone is not a ready runtime.
        assertFalse(probe.isAvailable("termux-glibc-runtime"));
        write(new File(files, "usr/glibc/lib/libX11.so.6"), "x11");
        write(new File(files, "usr/glibc/lib/libXrender.so.1"), "xrender");
        assertFalse(probe.isAvailable("termux-glibc-runtime"));
        write(new File(files, "usr/glibc/lib/libfreetype.so.6"), "freetype");

        assertTrue(probe.isAvailable("termux-glibc-runtime"));
        assertTrue(probe.isAvailable("termux-glibc-runtime", 1));
    }

    @Test
    public void rejectsIncompleteMetadataAndInvalidId() throws Exception {
        File files = temporary.newFolder("files");
        File installed = new File(files,
            "usr/glibc/opt/package-manager/installed");
        write(new File(installed, "scripts"), "28\n");
        new File(files, "usr/glibc/opt/scripts").mkdirs();
        TermuxRuntimeComponentProbe probe = new TermuxRuntimeComponentProbe(files);

        assertFalse(probe.isAvailable("scripts"));
        assertFalse(probe.isAvailable("../scripts"));
    }

    @Test
    public void recognizesTermuxProotDistroBuilderCapability() throws Exception {
        File files = temporary.newFolder("builder-files");
        TermuxRuntimeComponentProbe probe = new TermuxRuntimeComponentProbe(files);

        assertFalse(probe.isAvailable("proot-distro"));
        write(new File(files, "usr/bin/proot-distro"), "#!/data/data/com.termux/files/usr/bin/sh\n");
        assertTrue(probe.isAvailable("proot-distro"));
    }

    @Test
    public void virglServerRequiresExecutablePayload() throws Exception {
        File files = temporary.newFolder("virgl-files");
        File server = new File(files,
            "usr/glibc/opt/virgl/libvirgl_test_server.so");
        write(server, "binary");
        server.setExecutable(false, false);
        TermuxRuntimeComponentProbe probe = new TermuxRuntimeComponentProbe(files);

        assertFalse(probe.isAvailable("virgl-server"));
        assertTrue(server.setExecutable(true, false));
        assertTrue(probe.isAvailable("virgl-server"));
    }

    @Test
    public void turnipActivatesOnceTheBundledDriverArchiveIsPresent() throws Exception {
        File files = temporary.newFolder("turnip-files");
        installMetadata(files, "termux-box/package-manager/installed", "turnip", "8");
        TermuxRuntimeComponentProbe probe = new TermuxRuntimeComponentProbe(files);

        assertFalse(probe.isAvailable("turnip", 8));

        // The catalog release only contains .7z-bundled driver builds; extraction happens later
        // in bootstrap_termux_box.sh with the real `7z` binary, so activation only requires the
        // bundle directory to be published, not an already-extracted driver.
        write(new File(files, "usr/glibc/opt/libs/mesa/turnip-v6.5.7z"), "bundle");
        assertTrue(probe.isAvailable("turnip", 8));
    }

    @Test
    public void x11BridgeRequiresLauncherAndEmbeddedLoader() throws Exception {
        File files = temporary.newFolder("x11-files");
        File launcher = new File(files, "usr/bin/termux-x11");
        write(launcher, "#!/data/data/com.termux/files/usr/bin/sh\n");
        assertTrue(launcher.setExecutable(true, false));
        TermuxRuntimeComponentProbe probe = new TermuxRuntimeComponentProbe(files);

        assertFalse(probe.isAvailable("termux-x11"));
        write(new File(files, "usr/libexec/termux-x11/loader.apk"), "apk");
        assertTrue(probe.isAvailable("termux-x11"));
    }

    private static void installMetadata(File files, String relativeRoot,
                                        String componentId, String version) throws IOException {
        File root = new File(files, "usr/glibc/" + relativeRoot);
        write(new File(root, componentId), version + "\n");
        write(new File(root, componentId + "_lists"), "glibc/example\n");
        write(new File(root, componentId + "_md5"), "digest  glibc/example\n");
    }

    private static void write(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir failed");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
