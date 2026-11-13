package com.termux.api;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.termux.shared.termux.TermuxConstants;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Initializes the APK-matched, externally published CLI on an explicit worker task. */
public final class EmbeddedApiInstaller {
    private EmbeddedApiInstaller() {}
    public static synchronized void install(Context context) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("API initialization must run in a background task");
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        String abi = Build.VERSION.SDK_INT >= 23
            ? (android.os.Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS[0] : Build.SUPPORTED_32_BIT_ABIS[0])
            : Build.CPU_ABI;
        try (InputStream input = context.getAssets().open("runtime-api-components.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 256 * 1024) throw new IOException("Oversized API catalog");
                bytes.write(buffer, 0, count);
            }
            JSONObject catalog = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            new RuntimeApiComponents(new RuntimeApiComponents.Platform() {
                @Override public boolean isSymlink(File path) throws IOException {
                    try { return OsConstants.S_ISLNK(Os.lstat(path.getPath()).st_mode); }
                    catch (ErrnoException error) {
                        if (error.errno == OsConstants.ENOENT) return false;
                        throw new IOException("Cannot inspect API target", error);
                    }
                }
                @Override public void chmod(File path, int mode) throws IOException {
                    try { Os.chmod(path.getPath(), mode); }
                    catch (ErrnoException error) { throw new IOException("Cannot set API file permissions", error); }
                }
            }, RuntimeApiComponents::downloadHttps).install(prefix, RuntimeApiComponents.resolve(catalog, abi));
            File pending = new File(prefix, ".runtime-api-pending");
            if (pending.exists() && !pending.delete()) throw new IOException("Cannot clear API pending state");
        } catch (Exception error) { throw new IOException("Android API component initialization failed; retry initialization", error); }
    }
}
