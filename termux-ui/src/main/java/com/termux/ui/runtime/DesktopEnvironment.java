package com.termux.ui.runtime;

import androidx.annotation.Nullable;

import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopDe;
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

    /** Whether everything the distribution's run mode and environment need is present. */
    public boolean isInstalled(DesktopDistro distro) {
        boolean host;
        switch (distro.getDe()) {
            case KWIN_PLASMA:
                // Both modes run the KWin compositor natively; proot only adds the container.
                host = new File(prefix, "bin/start-plasma").isFile();
                break;
            case XFCE4:
                // Xlorie serves the view; the session itself runs natively or in the container.
                host = new File(prefix, "bin/Xlorie").isFile()
                    && (distro.getRunMode() == DesktopRunMode.PROOT
                        || new File(prefix, "bin/xfce4-session").isFile());
                break;
            default:
                return false;
        }
        if (!host) return false;
        if (distro.getRunMode() == DesktopRunMode.NATIVE) return true;
        if (!new File(prefix, "bin/proot-distro").isFile()) return false;
        File container = containerDir(distro.getProotDistro());
        // The marker is written by the install script once the desktop packages are in the
        // container; a half-provisioned instance (interrupted apt) stays "not installed".
        return container != null
            && new File(container, ".desktop-de-" + distro.getDe().getStorageValue()).isFile();
    }

    /** The X socket the Xlorie-based sessions listen on; its presence means the display is up. */
    public File x11Socket() {
        return new File(prefix, "tmp/.X11-unix/X0");
    }

    /** proot-distro 4+ keeps containers under {@code containers/}, older releases under
     *  {@code installed-rootfs/}. */
    @Nullable
    private File containerDir(String name) {
        File base = new File(prefix, "var/lib/proot-distro");
        File containers = new File(base, "containers/" + name);
        if (containers.isDirectory()) return containers;
        File legacy = new File(base, "installed-rootfs/" + name);
        return legacy.isDirectory() ? legacy : null;
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
