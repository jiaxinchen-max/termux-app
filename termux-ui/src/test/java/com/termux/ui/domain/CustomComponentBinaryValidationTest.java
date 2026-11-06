package com.termux.ui.domain;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Runs the installer's actual validation functions against small malformed/valid headers. */
public final class CustomComponentBinaryValidationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private String lastOutput;

    @Test public void aarch64ElfAcceptedForBothInstallers() throws Exception {
        byte[] elf = new byte[64];
        elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F';
        elf[4] = 2; elf[5] = 1; elf[18] = (byte) 0xb7;
        for (String installer : installers()) assertEquals(0, validate(installer, "require_aarch64", elf, ""));
    }

    @Test public void x86ElfRejectedForBothInstallers() throws Exception {
        byte[] elf = new byte[64];
        elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F';
        elf[4] = 2; elf[5] = 1; elf[18] = 0x3e;
        for (String installer : installers()) assertEquals(64, validate(installer, "require_aarch64", elf, ""));
    }

    @Test public void elf32AndBigEndianRejected() throws Exception {
        byte[] elf = new byte[64];
        elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F';
        elf[4] = 1; elf[5] = 1; elf[18] = (byte) 0xb7;
        assertEquals(64, validate(installers()[0], "require_aarch64", elf, ""));
        elf[4] = 2; elf[5] = 2;
        assertEquals(64, validate(installers()[0], "require_aarch64", elf, ""));
    }

    @Test public void pe32AndPe64DllsAccepted() throws Exception {
        for (String installer : installers()) {
            assertEquals(0, validate(installer, "require_pe", pe(0x14c), "4c01"));
            assertEquals(0, validate(installer, "require_pe", pe(0x8664), "6486"));
        }
    }

    @Test public void peArchitectureMismatchAndNonPeRejected() throws Exception {
        for (String installer : installers()) {
            assertEquals(64, validate(installer, "require_pe", pe(0x14c), "6486"));
            assertEquals(64, validate(installer, "require_pe", new byte[64], "6486"));
        }
    }

    @Test public void peExecutableWithoutDllFlagRejected() throws Exception {
        byte[] file = pe(0x8664);
        file[87] = 0;
        for (String installer : installers()) assertEquals(64, validate(installer, "require_pe", file, "6486"));
    }

    @Test public void truncatedPeOffsetRejected() throws Exception {
        byte[] file = pe(0x8664);
        file[60] = (byte) 0xff; file[61] = (byte) 0xff;
        assertEquals(64, validate(installers()[0], "require_pe", file, "6486"));
        assertEquals(64, validate(installers()[0], "require_pe", Arrays.copyOf(file, 61), "6486"));
    }

    @Test public void wineAbiMatchesExplicitBackendAndTranslator() throws Exception {
        for (String installer : installers()) {
            assertEquals(0, validateWine(installer, elf(0x3e), "glibc_termux_box", "none"));
            assertEquals(0, validateWine(installer, elf(0x3e), "rootfs_proot", "box64"));
            assertEquals(0, validateWine(installer, elf(0xb7), "rootfs_proot", "hangover"));
        }
    }

    @Test public void wineAbiMismatchReportsStableErrorWithoutTryingAnotherMode() throws Exception {
        for (String installer : installers()) {
            assertEquals(64, validateWine(installer, elf(0xb7), "glibc_termux_box", "none"));
            assertTrue(lastOutput.contains("custom_wine_arch_mismatch"));
            assertEquals(64, validateWine(installer, elf(0xb7), "rootfs_proot", "box64"));
            assertTrue(lastOutput.contains("custom_wine_arch_mismatch"));
            assertEquals(64, validateWine(installer, elf(0x3e), "rootfs_proot", "hangover"));
            assertTrue(lastOutput.contains("custom_wine_arch_mismatch"));
        }
    }

    @Test public void wineRejectsTruncatedHeaderElf32AndUnknownTranslator() throws Exception {
        String installer = installers()[0];
        assertEquals(64, validateWine(installer, Arrays.copyOf(elf(0x3e), 20), "rootfs_proot", "box64"));
        byte[] elf32 = elf(0x3e); elf32[4] = 1;
        assertEquals(64, validateWine(installer, elf32, "rootfs_proot", "box64"));
        assertEquals(64, validateWine(installer, elf(0x3e), "rootfs_proot", "none"));
        assertTrue(lastOutput.contains("custom_wine_translator_mismatch"));
        assertEquals(64, validateWine(installer, elf(0x3e), "glibc_termux_box", "box64"));
        assertTrue(lastOutput.contains("custom_wine_translator_mismatch"));
    }

    private int validateWine(String installer, byte[] bytes, String backend, String translator) throws Exception {
        return validate(installer, "require_wine_abi", bytes, "", backend, translator);
    }

    private static byte[] elf(int machine) {
        byte[] result = new byte[64];
        result[0] = 0x7f; result[1] = 'E'; result[2] = 'L'; result[3] = 'F';
        result[4] = 2; result[5] = 1; result[18] = (byte) machine;
        result[19] = (byte) (machine >>> 8);
        return result;
    }

    private static byte[] pe(int machine) {
        byte[] result = new byte[128];
        result[0] = 'M'; result[1] = 'Z'; result[60] = 64;
        result[64] = 'P'; result[65] = 'E';
        result[68] = (byte) machine; result[69] = (byte) (machine >>> 8);
        result[87] = 0x20; // COFF IMAGE_FILE_DLL.
        return result;
    }

    private int validate(String installer, String function, byte[] bytes, String architecture) throws Exception {
        return validate(installer, function, bytes, architecture, "", "");
    }

    private int validate(String installer, String function, byte[] bytes, String architecture,
            String backend, String translator) throws Exception {
        File binary = temporary.newFile();
        Files.write(binary.toPath(), bytes);
        String source = new String(Files.readAllBytes(new File(
            "src/main/assets/local-games/" + installer).toPath()), StandardCharsets.UTF_8);
        int start = source.indexOf("require_aarch64() {");
        int end = source.indexOf("unique_file() {", start);
        String functions = "fail() { echo \"$1\" >&2; exit \"${2:-1}\"; }\n" + source.substring(start, end);
        Process process = new ProcessBuilder("/bin/sh", "-c", functions +
            "\nRUNTIME_BACKEND=$3; RUNTIME_TRANSLATOR=$4;\n" + function + " \"$1\" \"$2\"",
            "validation", binary.getAbsolutePath(), architecture, backend, translator)
            .redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024]; int count;
        while ((count = process.getInputStream().read(buffer)) >= 0) output.write(buffer, 0, count);
        int exit = process.waitFor();
        lastOutput = new String(output.toByteArray(), StandardCharsets.UTF_8);
        return exit;
    }

    private static String[] installers() {
        return new String[] {"install_custom_rootfs_component.sh", "install_custom_glibc_wine_component.sh"};
    }
}
