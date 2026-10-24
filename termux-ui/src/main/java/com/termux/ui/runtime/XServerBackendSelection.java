package com.termux.ui.runtime;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.XServerBackendType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The persisted X server choice: a one-line marker file under the games runtime directory.
 *
 * <p>A file rather than preferences so the launch shell scripts read the very same value the app
 * shows (and so it can be flipped by hand for A/B runs). Missing, empty or unknown content always
 * means {@link XServerBackendType#LEGACY_IN_PROCESS}.
 */
public final class XServerBackendSelection {

    private XServerBackendSelection() {}

    public static XServerBackendType read(GameStoragePaths paths) {
        if (paths == null) return XServerBackendType.LEGACY_IN_PROCESS;
        File marker = paths.getXServerBackendMarker();
        if (!marker.isFile() || marker.length() > 64) return XServerBackendType.LEGACY_IN_PROCESS;
        try (java.io.FileInputStream input = new java.io.FileInputStream(marker)) {
            byte[] bytes = new byte[(int) marker.length()];
            int read = input.read(bytes);
            String value = new String(bytes, 0, Math.max(0, read), StandardCharsets.UTF_8).trim();
            return XServerBackendType.fromStorageValue(value);
        } catch (IOException | IllegalArgumentException error) {
            return XServerBackendType.LEGACY_IN_PROCESS;
        }
    }

    public static void write(GameStoragePaths paths, XServerBackendType type) throws IOException {
        if (paths == null || type == null) throw new IllegalArgumentException("x_server_selection");
        File marker = paths.getXServerBackendMarker();
        File directory = marker.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("x_server_marker_directory_failed");
        }
        File temporary = new File(directory, marker.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write((type.getStorageValue() + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(marker)) {
            temporary.delete();
            throw new IOException("x_server_marker_publish_failed");
        }
    }

    /** The host capability id launches must find for the currently selected X server. */
    public static String hostCapabilityId(GameStoragePaths paths) {
        return read(paths).getHostCapabilityId();
    }
}
