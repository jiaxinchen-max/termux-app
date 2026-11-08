package com.termux.ui.wine.service;

import android.content.Context;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.runtime.RootfsSetupRecipe;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Installs immutable bundled recipe inputs and computes their source-bound identity. Public
 *  because ResetRuntimeForegroundService/LocalGamesActivity also need to resolve the current
 *  recipeSha256 (to know which shared rootfs template a "reset RootFS" action should target). */
public final class RootfsSetupAssetInstaller {
    private static final String[] RECIPE_FILES = {
        "setup-container.sh", "games-runtime.properties"
    };
    private final Context context;
    private final GameStoragePaths paths;

    public RootfsSetupAssetInstaller(Context context, GameStoragePaths paths) {
        this.context = context.getApplicationContext();
        this.paths = paths;
    }

    public Installed install(RootfsSetupRecipe recipe, String[] componentSha256s) throws IOException {
        if (componentSha256s == null || componentSha256s.length == 0) {
            throw new IOException("rootfs_component_digest_invalid");
        }
        if (recipe.getVersion() == RootfsSetupRecipe.EXTERNAL_COMPONENTS_VERSION) {
            try (InputStream input = context.getAssets().open(recipe.getAssetDirectory() + "/image.json")) {
                ByteArrayOutputStream metadata = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (metadata.size() + count > 65536) throw new IOException("image_metadata_too_large");
                    metadata.write(buffer, 0, count);
                }
                requireImageSource(metadata.toString("UTF-8"), componentSha256s);
            }
        }
        MessageDigest digest = sha256Digest();
        for (String componentSha256 : componentSha256s) {
            if (componentSha256 == null || !componentSha256.matches("[0-9a-f]{64}")) {
                throw new IOException("rootfs_component_digest_invalid");
            }
            digest.update(componentSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
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
        if (recipe.getVersion() == RootfsSetupRecipe.EXTERNAL_COMPONENTS_VERSION) {
            for (String helper : new String[]{"rootfs_prefix_warmup.sh", "proot_component_bindings.sh"}) {
                try (InputStream input = context.getAssets().open("local-games/" + helper)) {
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
            }
        }
        if (recipe.getVersion() == RootfsSetupRecipe.EXTERNAL_COMPONENTS_VERSION) {
            copyAsset("local-games/relocate_rootfs_links.py",
                new File(directory, "relocate_rootfs_links.py"), digest);
            copyAsset("local-games/restore_wine_prefix.py",
                new File(directory, "restore_wine_prefix.py"), digest);
            for (String helper : new String[]{"prebuilt_rootfs.py"}) {
                copyAsset("local-games/" + helper, new File(directory, helper), digest);
            }
            copyAsset(recipe.getAssetDirectory() + "/image.json", new File(directory, "image.json"), digest);
            for (String name : new String[]{"bundle.json", "manifest.json", "delta.tgz"}) {
                String relative = "prefix-hangover-pdb2/" + name;
                copyAsset(recipe.getAssetDirectory() + "/" + relative,
                    new File(directory, relative), digest);
            }
        }
        File script = new File(paths.getRuntimeSetupDirectory(), "setup_rootfs_runtime_v" + recipe.getVersion() + ".sh");
        copyAsset(recipe.getSetupScriptAssetPath(), script, digest);
        if (!script.setExecutable(true, true)) throw new IOException("rootfs_setup_script_mode_failed");
        return new Installed(directory.getCanonicalFile(), script.getCanonicalFile(), hex(digest.digest()));
    }

    static void requireImageSource(String metadata, String[] componentSha256s) throws IOException {
        try {
            org.json.JSONObject image = new org.json.JSONObject(metadata);
            String expected = image.getString("sha256");
            if (!RootfsSetupRecipe.IMAGE_COMPONENT.equals(image.getString("imageId")) ||
                !"ed206c70ca889b1bbbcedaa93d4018d315b3512a861dec551da8093f9a1807c1".equals(
                    image.getString("sourceComponentSha256")) ||
                !expected.matches("[0-9a-f]{64}") || componentSha256s == null ||
                componentSha256s.length != 1 || !expected.equals(componentSha256s[0])) {
                throw new IOException("rootfs_image_component_mismatch");
            }
        } catch (org.json.JSONException error) {
            throw new IOException("rootfs_image_metadata_invalid", error);
        }
    }

    static void requirePrefixSource(String metadata, String[] componentSha256s) throws IOException {
        try {
            String expected = new org.json.JSONObject(metadata).getString("sourceComponentSha256");
            if (!expected.matches("[0-9a-f]{64}") || componentSha256s == null
                    || componentSha256s.length != 1 || !expected.equals(componentSha256s[0])) {
                throw new IOException("rootfs_prebuilt_prefix_component_mismatch");
            }
        } catch (org.json.JSONException error) {
            throw new IOException("rootfs_prebuilt_prefix_metadata_invalid", error);
        }
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

    public static final class Installed {
        final File recipeDirectory;
        final File script;
        final String recipeSha256;

        Installed(File recipeDirectory, File script, String recipeSha256) {
            this.recipeDirectory = recipeDirectory;
            this.script = script;
            this.recipeSha256 = recipeSha256;
        }

        public String getRecipeSha256() { return recipeSha256; }
    }
}
