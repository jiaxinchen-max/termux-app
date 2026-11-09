package com.termux.ui.wine.service;

import android.content.Context;
import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.components.index.ComponentDescriptor;
import com.termux.ui.components.index.ComponentIndex;
import com.termux.ui.components.index.ComponentIndexParser;
import com.termux.ui.components.install.ComponentInstallationReader;
import com.termux.ui.components.install.InstalledComponent;
import com.termux.ui.wine.runtime.NativePrebuiltPrefixes;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Keeps Native prefix resources in the verified component store, outside the GLIBC runtime. */
final class NativePrefixAssets {
    interface AssetReader { InputStream open(String path) throws IOException; }

    static String bundleSha256(Context context, String wine) throws IOException {
        return identity(context, wine);
    }

    static String identity(Context context, String wine) throws IOException {
        return identity(path -> context.getAssets().open(path), wine);
    }

    /** The bundle binds Wine, architecture, runtime files and restoration assets, not scripts. */
    static String identity(AssetReader assets, String wine) throws IOException {
        NativePrebuiltPrefixes.componentId(wine);
        return assetHash(assets, "local-games/native-prefix/" + wine + "/bundle.json");
    }

    static String preparationIdentity(Context context) throws IOException {
        return preparationIdentity(path -> context.getAssets().open(path));
    }

    static String preparationIdentity(AssetReader assets) throws IOException {
        MessageDigest digest = sha256();
        for (String asset : new String[]{"bootstrap_termux_box.sh", "local-games/restore_wine_prefix.py",
                "local-games/glibc_prefix_extras.sh", "local-games/native_runtime_components.sh"}) {
            digest.update(assetHash(assets, asset).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        return hex(digest.digest());
    }

    static boolean isPrepared(Context context, String wine, File prefix) throws IOException {
        return isPrepared(path -> context.getAssets().open(path), wine, prefix);
    }

    static boolean isPrepared(AssetReader assets, String wine, File prefix) throws IOException {
        return hasMarker(new File(prefix, ".games-runtime/prepared-identity"))
            && hasMarker(new File(prefix, ".games-runtime/preparation-script-identity"));
    }

    private static boolean hasMarker(File file) {
        if (!file.isFile() || file.length() != 65) return false;
        return true;
    }

    static File requireInstalled(Context context, String wine) throws IOException {
        String id = NativePrebuiltPrefixes.componentId(wine);
        ComponentIndex index;
        try (InputStream input = context.getAssets().open("termux-app-packages/index-v1.json")) {
            index = new ComponentIndexParser().parse(input);
        }
        try (InputStream input = context.getAssets().open("local-games/native-prefix/" + wine + "/bundle.json")) {
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (data.size() + count > 65536) throw new IOException("native_prefix_metadata_invalid");
                data.write(buffer, 0, count);
            }
            org.json.JSONObject metadata = new org.json.JSONObject(data.toString("UTF-8"));
            ComponentDescriptor selected = index.find(wine)
                .orElseThrow(() -> new IOException("native_prefix_wine_unknown"));
            if (!wine.equals(metadata.getString("winePackage")) ||
                !id.equals(metadata.getString("prefixComponentId")) ||
                !"glibc_termux_box".equals(metadata.getString("backend")) ||
                metadata.getInt("componentVersion") != selected.getVersion()) {
                throw new IOException("native_prefix_metadata_mismatch");
            }
        } catch (org.json.JSONException error) {
            throw new IOException("native_prefix_metadata_invalid", error);
        }
        ComponentInstallationReader reader = new ComponentInstallationReader(
            new ComponentStoragePaths(context.getFilesDir()).getInstallDirectory());
        for (String component : new String[]{wine, id}) {
            ComponentDescriptor descriptor = index.find(component)
                .orElseThrow(() -> new IOException("native_prefix_component_unknown"));
            InstalledComponent installed = reader.read(component).getActive()
                .orElseThrow(() -> new IOException("native_prefix_component_missing"));
            if (installed.getVersion() != descriptor.getVersion()) {
                throw new IOException("native_prefix_component_mismatch");
            }
        }
        return reader.read(id).getActive().get().getDirectory().getCanonicalFile();
    }

    private static String assetHash(AssetReader assets, String path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = assets.open(path)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException(error); }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(64);
        for (byte b : value) result.append(String.format(Locale.US, "%02x", b & 255));
        return result.toString();
    }
}
