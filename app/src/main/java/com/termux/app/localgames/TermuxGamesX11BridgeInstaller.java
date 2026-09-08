package com.termux.app.localgames;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Installs the app-bundled Termux:X11 command bridge without depending on terminal UI state. */
final class TermuxGamesX11BridgeInstaller {

    private static final Object INSTALL_LOCK = new Object();
    private static final long TIMEOUT_MS = 120_000;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private TermuxGamesX11BridgeInstaller() { }

    static void ensureInstalled(Context context) throws IOException {
        synchronized (INSTALL_LOCK) {
            File files = context.getFilesDir();
            if (isInstalled(files)) return;

            File temporary = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux/tmp");
            ensureDirectory(temporary);
            File install = copyAsset(context, "install", temporary);
            copyAsset(context, "xkeyboard-config_2.45_all.deb", temporary);
            copyAsset(context, "termux-x11-nightly-1.03.10-0-all.deb", temporary);
            if (!install.setExecutable(true, true) && !install.canExecute()) {
                throw new IOException("x11_bridge_installer_not_executable");
            }

            File shell = new File(files, "usr/bin/sh");
            if (!shell.isFile()) throw new IOException("termux_shell_missing");
            ProcessBuilder builder = new ProcessBuilder(shell.getPath(), install.getPath());
            builder.directory(temporary);
            builder.redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            environment.put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
            environment.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH +
                ":/system/bin:/system/xbin");

            Process process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> copyLimited(process.getInputStream(), output),
                "GamesX11BridgeOutput");
            reader.start();
            int exitCode = waitFor(process, TIMEOUT_MS);
            try {
                reader.join(2_000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("x11_bridge_install_interrupted", error);
            }
            if (exitCode != 0 || !isInstalled(files)) {
                throw new IOException("x11_bridge_install_failed:" + exitCode + ":" +
                    sanitize(output.toString()));
            }
        }
    }

    static boolean isInstalled(File filesDirectory) {
        File launcher = new File(filesDirectory, "usr/bin/termux-x11");
        File loader = new File(filesDirectory, "usr/libexec/termux-x11/loader.apk");
        return launcher.isFile() && launcher.canExecute() && launcher.length() > 0 &&
            loader.isFile() && loader.length() > 0;
    }

    private static File copyAsset(Context context, String name, File targetDirectory)
        throws IOException {
        File target = new File(targetDirectory, name);
        try (InputStream input = context.getAssets().open(name);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.getFD().sync();
        }
        return target;
    }

    private static int waitFor(Process process, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException running) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    process.destroy();
                    throw new IOException("x11_bridge_install_interrupted", error);
                }
            }
        }
        process.destroy();
        throw new IOException("x11_bridge_install_timeout");
    }

    private static void copyLimited(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[4 * 1024];
        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = MAX_OUTPUT_BYTES - output.size();
                if (remaining > 0) output.write(buffer, 0, Math.min(read, remaining));
            }
        } catch (IOException ignored) { }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("x11_bridge_tmp_create_failed");
        }
    }

    private static String sanitize(String value) {
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() <= 512 ? compact : compact.substring(compact.length() - 512);
    }
}
