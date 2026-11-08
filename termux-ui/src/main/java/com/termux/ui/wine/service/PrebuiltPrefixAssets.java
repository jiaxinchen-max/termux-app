package com.termux.ui.wine.service;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Copies only a selected, verified resource into the recipe consumed by the restore helper. */
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
            JSONObject hashes = new JSONObject(new String(trustedBundle,
                java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("assets");
            verify(source, "bundle.json", hash(trustedBundle));
            // Validate the whole resource before replacing any file. A stale recipe is never
            // accepted when the selected installed resource is missing or corrupt.
            for (String name : new String[]{"manifest.json", "delta.tgz"}) {
                verify(source, name, hashes.getString(name));
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
                    verify(target, temporary.getName(), hashes.getString(name));
                    if (!temporary.renameTo(destination)) throw new IOException("prefix_publish_failed");
                } finally {
                    if (temporary.exists()) temporary.delete();
                }
            }
        } catch (JSONException error) {
            throw new IOException("prefix_metadata_invalid", error);
        }
    }

    private static void verify(File root, String name, String expected) throws IOException {
        File file = new File(root, name);
        if (!expected.matches("[0-9a-f]{64}") || !file.isFile() ||
            !file.getCanonicalFile().equals(new File(root.getCanonicalFile(), name))) {
            throw new IOException("prefix_source_invalid:" + name);
        }
        MessageDigest digest = sha256();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (!expected.equals(hex(digest.digest()))) throw new IOException("prefix_digest_mismatch:" + name);
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException(error); }
    }

    private static String hash(byte[] value) throws IOException { return hex(sha256().digest(value)); }
    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(64);
        for (byte b : value) result.append(String.format(Locale.US, "%02x", b & 255));
        return result.toString();
    }
}
