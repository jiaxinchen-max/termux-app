package com.termux.localgames.service;

import android.content.Context;

import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.runtime.RootfsProvisionRecipe;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Installs immutable bundled recipe inputs and computes their source-bound identity. */
final class RootfsProvisionAssetInstaller {
    private static final String[] RECIPE_FILES = {
        "provision-container.sh", "games-runtime.properties"
    };
    private final Context context;
    private final GameStoragePaths paths;

    RootfsProvisionAssetInstaller(Context context, GameStoragePaths paths) {
        this.context = context.getApplicationContext();
        this.paths = paths;
    }

    Installed install(RootfsProvisionRecipe recipe, String sourceSha256) throws IOException {
        if (sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("rootfs_source_digest_invalid");
        }
        MessageDigest digest = sha256Digest();
        digest.update(sourceSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        File directory = new File(paths.getRuntimeRecipeDirectory(),
            recipe.getPackageName() + "-v" + recipe.getVersion());
        ensureDirectory(directory);
        for (String name : RECIPE_FILES) {
            File target = new File(directory, name);
            try (InputStream input = new BufferedInputStream(context.getAssets().open(
                     recipe.getAssetDirectory() + "/" + name));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(target, false))) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
        }
        File script = new File(paths.getRuntimeProvisionDirectory(), "provision_rootfs_runtime.sh");
        copyAsset("local-games/provision_rootfs_runtime.sh", script, digest);
        if (!script.setExecutable(true, true)) throw new IOException("rootfs_provision_script_mode_failed");
        return new Installed(directory.getCanonicalFile(), script.getCanonicalFile(), hex(digest.digest()));
    }

    private void copyAsset(String asset, File target, MessageDigest digest) throws IOException {
        ensureDirectory(target.getParentFile());
        try (InputStream input = new BufferedInputStream(context.getAssets().open(asset));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(target, false))) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("sha256_unavailable", error); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static void ensureDirectory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("rootfs_recipe_directory_failed");
    }

    static final class Installed {
        final File recipeDirectory;
        final File script;
        final String recipeSha256;

        Installed(File recipeDirectory, File script, String recipeSha256) {
            this.recipeDirectory = recipeDirectory;
            this.script = script;
            this.recipeSha256 = recipeSha256;
        }
    }
}
