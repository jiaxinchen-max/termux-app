package com.termux.localgames.service;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.runtime.GameRuntimeBackend;
import com.termux.localgames.runtime.GlibcTermuxBoxBackend;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Atomically deploys versioned module scripts into private executable storage. */
public final class LaunchScriptInstaller {

    private static final String BOOTSTRAP_ASSET = "bootstrap_termux_box.sh";
    private static final String PREFIX_PROVISION_ASSET = "local-games/provision_glibc_prefix.sh";
    private static final String TERMUX_GLIBC_INSTALL_ASSET =
        "local-games/install_termux_glibc_runtime.sh";
    private static final String TERMUX_GLIBC_RESET_ASSET =
        "local-games/reset_termux_glibc_runtime.sh";
    private static final String ROOTFS_RESET_ASSET =
        "local-games/reset_rootfs_runtime.sh";
    private static final String TERMUX_BOX_GAME_ASSET =
        "local-games/start_termux_box_game.sh";

    private final Context context;
    private final File runtimeDirectory;

    public LaunchScriptInstaller(Context context, GameStoragePaths paths) {
        this.context = context.getApplicationContext();
        this.runtimeDirectory = paths.getRuntimeDirectory();
    }

    public File install() throws IOException {
        return install(new GlibcTermuxBoxBackend());
    }

    public File install(GameRuntimeBackend backend) throws IOException {
        if (backend == null) throw new IllegalArgumentException("runtime_backend_required");
        File launcher = new File(runtimeDirectory, backend.getLauncherFileName());
        installAsset(backend.getLauncherAssetPath(), launcher);
        if (backend instanceof GlibcTermuxBoxBackend) {
            installAsset(TERMUX_BOX_GAME_ASSET,
                new File(runtimeDirectory, "start_termux_box_game.sh"));
            installAsset(BOOTSTRAP_ASSET, new File(runtimeDirectory, "bootstrap_termux_box.sh"));
            installAsset(PREFIX_PROVISION_ASSET,
                new File(runtimeDirectory, "provision_glibc_prefix.sh"));
        }
        return launcher;
    }

    /** Deploys the trusted terminal script that installs the official Termux GLIBC packages. */
    public File installTermuxGlibcRuntime() throws IOException {
        File script = new File(runtimeDirectory, "install_termux_glibc_runtime.sh");
        installAsset(TERMUX_GLIBC_INSTALL_ASSET, script);
        return script;
    }

    /** Deploys the trusted terminal script that purges the GLIBC runtime back to not-installed. */
    public File installResetTermuxGlibcRuntime() throws IOException {
        File script = new File(runtimeDirectory, "reset_termux_glibc_runtime.sh");
        installAsset(TERMUX_GLIBC_RESET_ASSET, script);
        return script;
    }

    /** Deploys the trusted terminal script that removes one RootFS container's build + metadata. */
    public File installResetRootfsRuntime() throws IOException {
        File script = new File(runtimeDirectory, "reset_rootfs_runtime.sh");
        installAsset(ROOTFS_RESET_ASSET, script);
        return script;
    }

    private void installAsset(String asset, File target) throws IOException {
        if (!runtimeDirectory.isDirectory() && !runtimeDirectory.mkdirs()) {
            throw new IOException("launch_runtime_directory_failed");
        }
        byte[] expected;
        try (InputStream input = context.getAssets().open(asset)) {
            expected = digest(input);
        }
        if (target.isFile()) {
            try (InputStream input = new FileInputStream(target)) {
                if (MessageDigest.isEqual(expected, digest(input))) {
                    chmod(target);
                    return;
                }
            }
        }
        File temporary = new File(runtimeDirectory, target.getName() + ".tmp");
        try (InputStream input = context.getAssets().open(asset);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        chmod(temporary);
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("launch_script_publish_failed");
        }
    }

    private static byte[] digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void chmod(File file) throws IOException {
        try { Os.chmod(file.getPath(), 0700); }
        catch (ErrnoException error) { throw new IOException("launch_script_chmod_failed", error); }
    }
}
