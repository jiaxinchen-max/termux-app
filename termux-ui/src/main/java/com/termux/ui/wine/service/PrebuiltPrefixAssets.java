package com.termux.ui.wine.service;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Copies the selected resource into the recipe consumed by the restore helper. */
final class PrebuiltPrefixAssets {
    static String folder(String wine) {
        if ("rootfs_hangover_11.16".equals(wine)) return "prefix-hangover-pdb2";
        if ("rootfs_wine_10.0".equals(wine)) return "prefix-wine10-wow64";
        if ("rootfs_proton_11.0".equals(wine)) return "prefix-proton11-wow64";
        return null;
    }

    static String componentId(String wine) {
        String folder = folder(wine);
        return folder == null ? null : "rootfs_" + folder.replace('-', '_');
    }

    static void copy(File source, File target, byte[] trustedBundle) throws IOException {
        try {
            JSONObject assets = new JSONObject(new String(trustedBundle,
                java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("assets");
            requireFile(source, "bundle.json");
            // Require every payload file and its path before replacing the recipe.
            for (String name : new String[]{"manifest.json", "delta.tgz"}) {
                assets.getString(name);
                requireFile(source, name);
            }
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("prefix_directory_failed");
            if (!target.getAbsoluteFile().equals(target.getCanonicalFile())) {
                throw new IOException("prefix_destination_invalid");
            }
            for (String name : new String[]{"manifest.json", "delta.tgz"}) {
                File temporary = new File(target, name + ".pending");
                File destination = new File(target, name);
                if (!temporary.getAbsoluteFile().equals(temporary.getCanonicalFile()) ||
                    !destination.getAbsoluteFile().equals(destination.getCanonicalFile())) {
                    throw new IOException("prefix_destination_invalid");
                }
                try {
                    try (FileInputStream input = new FileInputStream(new File(source, name));
                         FileOutputStream output = new FileOutputStream(temporary, false)) {
                        byte[] buffer = new byte[32768]; int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        output.getFD().sync();
                    }
                    requireFile(target, temporary.getName());
                    if (!temporary.renameTo(destination)) throw new IOException("prefix_publish_failed");
                } finally {
                    if (temporary.exists()) temporary.delete();
                }
            }
        } catch (JSONException error) {
            throw new IOException("prefix_metadata_invalid", error);
        }
    }

    private static void requireFile(File root, String name) throws IOException {
        File file = new File(root, name);
        if (!file.isFile() ||
            !file.getCanonicalFile().equals(new File(root.getCanonicalFile(), name))) {
            throw new IOException("prefix_source_invalid:" + name);
        }
    }

}
