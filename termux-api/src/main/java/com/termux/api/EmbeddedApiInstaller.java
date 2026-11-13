package com.termux.api;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import com.termux.shared.termux.TermuxConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Installs the APK-matched CLI only during explicit runtime/bootstrap initialization. */
public final class EmbeddedApiInstaller {
    private EmbeddedApiInstaller() {}
    public static synchronized void install(Context context) throws IOException {
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (!new File(prefix, "bin").isDirectory()) throw new IOException("Termux prefix is not initialized");
        String abi = Build.VERSION.SDK_INT >= 23
            ? (android.os.Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS[0] : Build.SUPPORTED_32_BIT_ABIS[0])
            : Build.CPU_ABI;
        try {
            JSONObject hashes;
            try (InputStream in = context.getAssets().open("embedded-api/manifest.json")) {
                hashes = new JSONObject(new String(read(in), StandardCharsets.UTF_8));
            }
            if ("arm64-v8a".equals(abi)) {
                byte[] component;
                try (InputStream in = context.getAssets().open("embedded-components/upower_1.91.5-1_aarch64.deb")) { component = read(in); }
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                StringBuilder actual = new StringBuilder();
                for (byte b : digest.digest(component)) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                if (!actual.toString().equals("e0198daeabe6e8e5013c9d393340118f27c583c8a68cdbc2f774e2b4d3b478f3")) throw new IOException("UPower component checksum mismatch");
                File directory = new File(prefix, "share/embedded-api");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
                File temp = File.createTempFile(".upower-", ".deb", directory);
                try {
                    try (FileOutputStream out = new FileOutputStream(temp)) { out.write(component); out.getFD().sync(); }
                    Os.rename(temp.getPath(), new File(directory, "upower.deb").getPath());
                } finally { temp.delete(); }
            }
            java.util.Iterator<String> entries = hashes.keys();
            while (entries.hasNext()) {
                String entry = entries.next();
                String target;
                if (entry.equals(abi + "/termux-api")) target = "libexec/termux-api";
                else if (entry.equals(abi + "/termux-api.deb")) target = "share/embedded-api/termux-api.deb";
                else if (entry.startsWith("bin/")) target = entry;
                else if (entry.equals("termux-callback")) target = "libexec/termux-callback";
                else continue;
                byte[] bytes;
                try (InputStream in = context.getAssets().open("embedded-api/" + entry)) { bytes = read(in); }
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                StringBuilder actual = new StringBuilder();
                for (byte b : digest.digest(bytes)) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                if (!actual.toString().equals(hashes.getString(entry))) throw new IOException("API asset checksum mismatch: " + entry);
                File targetFile = new File(prefix, target);
                File parent = targetFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
                File temp = File.createTempFile(".embedded-api-", ".tmp", parent);
                try {
                    try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
                    Os.chmod(temp.getPath(), 0700);
                    Os.rename(temp.getPath(), targetFile.getPath());
                } finally { temp.delete(); }
            }
        } catch (Exception e) { throw new IOException("Embedded API CLI initialization failed", e); }
    }
    private static byte[] read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
