package com.termux.ui.wine.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Executes the shipped shell functions against small fixtures; no Android/runtime downloads. */
public final class RuntimeComponentLaunchContractTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nativeBox64UsesSelectedBinaryThroughTheGlibcLoader() throws Exception {
        File root = temporary.newFolder();
        executable(root, "glibc/lib/ld-linux-aarch64.so.1", "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
        executable(root, "glibc/components/custom-box64-a/bin/box64", "#!/bin/sh\nexit 0\n");
        receipt(root, "glibc/components/custom-box64-a", "glibc_termux_box", "box64", "custom-box64-a");
        Result result = run(root, false, "GAMES_CUSTOM_BOX64_ID=custom-box64-a; resolve_native_box64; native_run_box64 wine.exe --test");
        assertEquals(result.output, 0, result.code);
        assertEquals("--library-path\n" + new File(root, "glibc/lib").getCanonicalPath() + "\n" +
            new File(root, "glibc/components/custom-box64-a/bin/box64").getCanonicalPath() +
            "\nwine.exe\n--test\n", result.output);
    }

    @Test public void missingSelectedBox64DoesNotRunTheAvailableDefault() throws Exception {
        File root = temporary.newFolder();
        executable(root, "glibc/lib/ld-linux-aarch64.so.1", "#!/bin/sh\necho wrong-loader-executed\n");
        executable(root, "glibc/bin/box64", "#!/bin/sh\necho wrong-default-executed\n");
        Result result = run(root, false, "GAMES_CUSTOM_BOX64_ID=custom-box64-missing; resolve_native_box64; native_run_box64 wine.exe");
        assertNotEquals(0, result.code);
        assertTrue(result.output.contains("custom_component_receipt_invalid:custom-box64-missing"));
        assertFalse(result.output.contains("wrong-default-executed"));
        assertFalse(result.output.contains("wrong-loader-executed"));
    }

    @Test public void twoGamesExecuteDifferentInstalledBox64BuildsAndKeepTheirSelection() throws Exception {
        File root = temporary.newFolder();
        executable(root, "glibc/lib/ld-linux-aarch64.so.1", "#!/bin/sh\nshift 2\nexec \"$@\"\n");
        executable(root, "glibc/components/glibc_box64_0.2.8/bin/box64", "#!/bin/sh\nprintf '0.2.8|%s\\n' \"$1\"\n");
        executable(root, "glibc/components/glibc_box64_0.4.4/bin/box64", "#!/bin/sh\nprintf '0.4.4|%s\\n' \"$1\"\n");
        executable(root, "glibc/bin/box64", "#!/bin/sh\necho wrong-global\n");
        Result result = run(root, false,
            "GAMES_CUSTOM_BOX64_ID=glibc_box64_0.2.8; resolve_native_box64; native_run_box64 game-a.exe; " +
            "GAMES_CUSTOM_BOX64_ID=glibc_box64_0.4.4; resolve_native_box64; native_run_box64 game-b.exe; " +
            "GAMES_CUSTOM_BOX64_ID=glibc_box64_0.2.8; resolve_native_box64; native_run_box64 game-a.exe; " +
            "GAMES_CUSTOM_BOX64_ID=; resolve_native_box64; native_run_box64 default-game.exe");
        assertEquals(result.output, 0, result.code);
        assertEquals("0.2.8|game-a.exe\n0.4.4|game-b.exe\n0.2.8|game-a.exe\n0.2.8|default-game.exe\n", result.output);
    }

    @Test public void missingBuiltInVersionNeverUsesTheOtherVersionOrSharedBinary() throws Exception {
        File root = temporary.newFolder();
        executable(root, "glibc/lib/ld-linux-aarch64.so.1", "#!/bin/sh\necho wrong-loader-executed\n");
        executable(root, "glibc/components/glibc_box64_0.2.8/bin/box64", "#!/bin/sh\necho wrong-default-executed\n");
        executable(root, "glibc/bin/box64", "#!/bin/sh\necho wrong-shared-executed\n");
        Result result = run(root, false, "GAMES_CUSTOM_BOX64_ID=glibc_box64_0.4.4; resolve_native_box64; native_run_box64 game.exe");
        assertNotEquals(0, result.code);
        assertTrue(result.output.contains("box64_selected_binary_missing:glibc_box64_0.4.4"));
        assertFalse(result.output.contains("executed"));
    }

    @Test public void nativeDirect3dVersionsStayInTheirOwnProfileAndNeverFollowGlobalDllLinks() throws Exception {
        File root = temporary.newFolder();
        dxvk(root, "custom-dxvk-a", "version-a");
        dxvk(root, "custom-dxvk-b", "version-b");
        File global = new File(root, "global-d3d11.dll");
        CustomComponentSelectionTest.write(global, "global-original");
        File link = new File(root, "prefix-a/drive_c/windows/system32/d3d11.dll");
        assertTrue(link.getParentFile().mkdirs());
        Files.createSymbolicLink(link.toPath(), global.toPath());
        Result result = run(root, false, "WINEPREFIX=\"$TEST_ROOT/prefix-a\"; TERMUX_BOX_DXWRAPPER=custom-dxvk-a; apply_native_dx_component; " +
            "WINEPREFIX=\"$TEST_ROOT/prefix-b\"; TERMUX_BOX_DXWRAPPER=custom-dxvk-b; apply_native_dx_component");
        assertEquals(result.output, 0, result.code);
        assertEquals("version-a", read(new File(root, "prefix-a/drive_c/windows/system32/d3d11.dll")));
        assertEquals("version-b", read(new File(root, "prefix-b/drive_c/windows/system32/d3d11.dll")));
        assertEquals("global-original", read(global));
        assertFalse(Files.isSymbolicLink(link.toPath()));
    }

    @Test public void switchingDirect3dFamilyClearsOnlyManagedDllsAndUsesExplicitBuiltin() throws Exception {
        File root = temporary.newFolder();
        dxvk(root, "custom-dxvk-a", "dxvk");
        for (String arch : new String[] {"x86", "x64"}) {
            CustomComponentSelectionTest.write(new File(root, "glibc/components/custom-vkd3d-a/" + arch + "/d3d12.dll"), "vkd3d");
        }
        receipt(root, "glibc/components/custom-vkd3d-a", "glibc_termux_box", "vkd3d", "custom-vkd3d-a");
        CustomComponentSelectionTest.write(new File(root, "prefix/drive_c/windows/system32/user.dll"), "user");
        Result result = run(root, false, "TERMUX_BOX_DXWRAPPER=custom-dxvk-a; apply_native_dx_component; " +
            "TERMUX_BOX_DXWRAPPER=custom-vkd3d-a; apply_native_dx_component; " +
            "test ! -e \"$WINEPREFIX/drive_c/windows/system32/d3d11.dll\"; " +
            "test -f \"$WINEPREFIX/drive_c/windows/system32/d3d12.dll\"; " +
            "TERMUX_BOX_DXWRAPPER=wined3d; apply_native_dx_component; printf '%s' \"$NATIVE_D3D_OVERRIDES\"");
        assertEquals(result.output, 0, result.code);
        assertFalse(new File(root, "prefix/drive_c/windows/system32/d3d12.dll").exists());
        assertEquals("user", read(new File(root, "prefix/drive_c/windows/system32/user.dll")));
        assertTrue(result.output.endsWith("=b"));
        assertFalse(result.output.contains("n,b"));
    }

    @Test public void missingDirect3dVersionFailsBeforeChangingThePrefix() throws Exception {
        File root = temporary.newFolder();
        CustomComponentSelectionTest.write(new File(root, "prefix/drive_c/windows/system32/d3d11.dll"), "old");
        Result result = run(root, false, "TERMUX_BOX_DXWRAPPER=custom-dxvk-missing; apply_native_dx_component");
        assertNotEquals(0, result.code);
        assertTrue(result.output.contains("custom_component_receipt_invalid:custom-dxvk-missing"));
        assertEquals("old", read(new File(root, "prefix/drive_c/windows/system32/d3d11.dll")));
    }

    @Test public void turnipIcdPointsAtTheExactVersionAndRemainsPrivateToItsPrefix() throws Exception {
        File root = temporary.newFolder();
        for (String id : new String[] {"custom-turnip-a", "custom-turnip-b"}) {
            CustomComponentSelectionTest.write(new File(root, "glibc/components/" + id + "/lib/libvulkan_freedreno.so"), id);
            receipt(root, "glibc/components/" + id, "glibc_termux_box", "turnip", id);
        }
        Result result = run(root, false, "WINEPREFIX=\"$TEST_ROOT/prefix-a\"; TERMUX_BOX_GRAPHICS_DRIVER=custom-turnip-a; apply_native_graphics_component; " +
            "WINEPREFIX=\"$TEST_ROOT/prefix-b\"; TERMUX_BOX_GRAPHICS_DRIVER=custom-turnip-b; apply_native_graphics_component");
        assertEquals(result.output, 0, result.code);
        assertTrue(read(new File(root, "prefix-a/termux-boxmeta/turnip-icd.json")).contains("components/custom-turnip-a/lib/libvulkan_freedreno.so"));
        assertTrue(read(new File(root, "prefix-b/termux-boxmeta/turnip-icd.json")).contains("components/custom-turnip-b/lib/libvulkan_freedreno.so"));
        assertFalse(new File(root, "glibc/lib/libvulkan_freedreno.so").exists());
    }

    @Test public void nativeArchiveIdsAreWhitelistedWithoutSubstringAliases() throws Exception {
        File root = temporary.newFolder();
        Result allowed = run(root, false, "native_dx_archive_name native-dxvk-gplasync-2.3; native_turnip_archive_name native-turnip-v6.5");
        assertEquals(allowed.output, 0, allowed.code);
        assertEquals("dxvk-gplasync-2.3.7z\nturnip-v6.5.7z\n", allowed.output);
        assertNotEquals(0, run(root, false, "native_dx_archive_name custom-dxvk-gplasync-2.3").code);
        assertNotEquals(0, run(root, false, "native_turnip_archive_name native-turnip-v6.5/../other").code);
    }

    @Test public void rootfsResolvesExactWineAndBox64AndIgnoresArbitraryWinePathOverrides() throws Exception {
        File root = temporary.newFolder();
        for (String bin : new String[] {"wine", "wineboot", "wineserver"}) {
            executable(root, "rootfs/opt/custom-wine/custom-wine-a/bin/" + bin, "#!/bin/sh\nexit 0\n");
        }
        executable(root, "rootfs/opt/box64/rootfs_box64_0.4.4/bin/box64", "#!/bin/sh\nexit 0\n");
        executable(root, "rootfs/opt/box64/custom-box64-a/bin/box64", "#!/bin/sh\nexit 0\n");
        receipt(root, "rootfs/opt/custom-wine/custom-wine-a", "rootfs_proot", "wine", "custom-wine-a", "box64");
        receipt(root, "rootfs/opt/box64/custom-box64-a", "rootfs_proot", "box64", "custom-box64-a");
        Result result = run(root, true, "WINE_PACKAGE=custom-wine-a; GAMES_RUNTIME_TRANSLATOR=box64; " +
            "GAMES_CUSTOM_BOX64_ID=custom-box64-a; GAMES_CUSTOM_WINE_PATH=/wrong/bin/wine; " +
            "resolve_rootfs_translator; printf '%s\\n' \"$GUEST_COMMAND\" \"$GUEST_WINE\"");
        assertEquals(result.output, 0, result.code);
        assertEquals("/opt/box64/custom-box64-a/bin/box64\n/opt/custom-wine/custom-wine-a/bin/wine\n", result.output);
        Result missingTranslator = run(root, true, "WINE_PACKAGE=custom-wine-a; resolve_rootfs_translator");
        assertNotEquals(0, missingTranslator.code);
        assertTrue(missingTranslator.output.contains("custom_wine_translator_required"));
    }

    @Test public void templateBuildPreservesSelectedWineAndTranslator() throws Exception {
        File root = temporary.newFolder();
        for (String bin : new String[] {"usr/bin/wine", "usr/bin/wineboot",
                "opt/box64/rootfs_box64_0.4.4/bin/box64", "opt/box64-wine/bin/wine64",
                "opt/box64-proton/bin/wine"}) {
            executable(root, "rootfs/" + bin, "#!/bin/sh\nexit 0\n");
        }
        executable(root, "bin/termux-x11", "#!/bin/sh\nexec sleep 5\n");
        for (String selected : new String[] {"rootfs_hangover_11.16", "rootfs_wine_10.0", "rootfs_proton_11.0"}) {
            String translator = selected.startsWith("rootfs_hangover_") ? "hangover" : "box64";
            Result result = run(root, true,
                "PATH=\"$TEST_ROOT/bin:$PATH\"; SHARED_ROOTFS=\"$TEST_ROOT/rootfs\"; " +
                "TEMPLATE_CACHE_DIR=\"$TEST_ROOT/templates\"; TEMPLATE_COMPRESSOR=cat; TEMPLATE_PREFIX_EXT=gz; " +
                "TERMUX_FILES_ROOT=\"$TEST_ROOT\"; PREFIX=\"$TEST_ROOT/usr\"; LOG_PATH=\"$TEST_ROOT/template.log\"; " +
                "WINE_PACKAGE=" + selected + "; GAMES_RUNTIME_TRANSLATOR=" + translator + "; GAMES_CUSTOM_BOX64_ID=; " +
                "progress() { :; }; warmup_rootfs_prefix() { :; }; run_logged() { \"$@\"; }; " +
                "tar() { while [ \"$1\" != -cf ]; do shift; done; : > \"$2\"; }; " +
                "build_prefix_templates rootfs_hangover_11.16,rootfs_wine_10.0,rootfs_proton_11.0; " +
                "resolve_rootfs_translator; printf '%s|%s\\n' \"$WINE_PACKAGE\" \"$RUNTIME_TRANSLATOR\"");
            assertEquals(result.output, 0, result.code);
            assertEquals(selected + "|" + translator + "\n", result.output);
        }
    }

    private Result run(File root, boolean rootfs, String body) throws Exception {
        File script = new File("src/main/assets/local-games/" + (rootfs ? "rootfs_prefix_warmup.sh" : "native_runtime_components.sh"));
        if (!script.isFile()) script = new File("termux-ui", script.getPath());
        assertTrue("Missing asset " + script, script.isFile());
        File output = File.createTempFile("shell-output", ".txt", root);
        ProcessBuilder builder = new ProcessBuilder("sh", "-eu", "-c",
            ". \"$1\"; warmup_fail() { printf '%s\\n' \"$1\" >&2; exit 91; }; " + body,
            "component-contract", script.getCanonicalPath());
        builder.environment().put("TEST_ROOT", root.getCanonicalPath());
        builder.environment().put("TERMUX_GLIBC_DIR", new File(root, "glibc").getCanonicalPath());
        builder.environment().put("TERMUX_OPT_DIR", new File(root, "glibc/opt").getCanonicalPath());
        builder.environment().put("WINEPREFIX", new File(root, "prefix").getCanonicalPath());
        builder.environment().put("ROOTFS_CANONICAL", new File(root, "rootfs").getCanonicalPath());
        builder.redirectErrorStream(true).redirectOutput(output);
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Shell contract timed out"); }
        return new Result(process.exitValue(), read(output));
    }
    private static void dxvk(File root, String id, String value) throws Exception {
        for (String arch : new String[] {"x32", "x64"}) {
            CustomComponentSelectionTest.write(new File(root, "glibc/components/" + id + "/" + arch + "/d3d11.dll"), value);
            CustomComponentSelectionTest.write(new File(root, "glibc/components/" + id + "/" + arch + "/dxgi.dll"), value);
        }
        receipt(root, "glibc/components/" + id, "glibc_termux_box", "dxvk", id);
    }
    private static void receipt(File root, String directory, String backend, String kind, String id) throws Exception {
        receipt(root, directory, backend, kind, id, "none");
    }
    private static void receipt(File root, String directory, String backend, String kind, String id, String translator) throws Exception {
        CustomComponentSelectionTest.write(new File(root, directory + "/.custom-component.properties"),
            "schemaVersion=1\nruntimeBackend=" + backend + "\nruntimeTranslator=" + translator + "\nkind=" + kind + "\ncomponentId=" + id + "\n");
    }
    private static void executable(File root, String relative, String value) throws Exception {
        File file = new File(root, relative);
        CustomComponentSelectionTest.write(file, value);
        assertTrue(file.setExecutable(true, true));
    }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static final class Result {
        final int code;
        final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }
}
