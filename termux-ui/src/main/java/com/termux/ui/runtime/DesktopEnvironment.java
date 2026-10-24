package com.termux.ui.runtime;

import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopRunMode;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** File-system facts about the KWin installation under one Termux files directory. */
public final class DesktopEnvironment {

    private static final int LOG_SCAN_BYTES = 256 * 1024;

    private final File prefix;
    private final File home;

    /** @param filesDirectory the Termux files directory ({@code .../files}) */
    public DesktopEnvironment(File filesDirectory) {
        if (filesDirectory == null) throw new IllegalArgumentException("filesDirectoryRequired");
        this.prefix = new File(filesDirectory, "usr");
        this.home = new File(filesDirectory, "home");
    }

    /** Whether everything the distribution's run mode needs from the host is present. */
    public boolean isInstalled(DesktopDistro distro) {
        // Both modes run KWin (start-plasma) natively; proot only adds the container.
        if (!new File(prefix, "bin/start-plasma").isFile()) return false;
        if (distro.getRunMode() == DesktopRunMode.NATIVE) return true;
        return new File(prefix, "bin/proot-distro").isFile() && prootContainerExists(distro.getProotDistro());
    }

    /** proot-distro 4+ keeps containers under {@code containers/}, older releases under
     *  {@code installed-rootfs/}. */
    private boolean prootContainerExists(String name) {
        File base = new File(prefix, "var/lib/proot-distro");
        return new File(base, "containers/" + name).isDirectory()
            || new File(base, "installed-rootfs/" + name).isDirectory();
    }

    /** KWin's Wayland socket for the given Android uid, whether or not it exists. */
    public File waylandSocket(int uid) {
        return new File(home, ".termux/kwin-runtime/runtime-" + uid + "/wayland-0");
    }

    /**
     * Whether the log already contains KWin's ready marker. The marker is written during
     * startup, so only the head is scanned: a long, chatty session must not drop back to
     * "starting" once the marker has scrolled out of a tail window.
     */
    public static boolean logShowsReady(File logFile) {
        return head(logFile, LOG_SCAN_BYTES).contains(DesktopLaunchSpec.READY_LOG_MARKER);
    }

    /** The first {@code maxBytes} of a log, or an empty string when it is missing or unreadable. */
    public static String head(File file, int maxBytes) {
        if (file == null || !file.isFile() || maxBytes <= 0) return "";
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            int count = (int) Math.min(input.length(), maxBytes);
            byte[] bytes = new byte[count];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }

    /** The last {@code maxBytes} of a log, or an empty string when it is missing or unreadable. */
    public static String tail(File file, int maxBytes) {
        if (file == null || !file.isFile() || maxBytes <= 0) return "";
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            int count = (int) Math.min(length, maxBytes);
            byte[] bytes = new byte[count];
            input.seek(length - count);
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }
}
