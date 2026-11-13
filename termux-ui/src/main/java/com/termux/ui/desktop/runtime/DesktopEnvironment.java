package com.termux.ui.desktop.runtime;

import androidx.annotation.Nullable;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.HyprlandComponents;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;

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
        if (distro.getRunMode() == DesktopRunMode.NATIVE && !distro.getDe().supportsNative()) return false;
        boolean host;
        switch (distro.getDe()) {
            case KWIN_PLASMA:
                // Both modes run the KWin compositor natively; proot only adds the container.
                host = new File(prefix, "bin/start-plasma").isFile();
                break;
            case HYPRLAND:
                host = hyprlandRuntimeInstalled(distro) && hyprlandThemeInstalled(distro)
                    && (distro.getRunMode() == DesktopRunMode.PROOT
                        || new File(prefix, "bin/xfce4-terminal").isFile() && new File(prefix, "bin/thunar").isFile());
                break;
            case UBUNTU_GNOME:
            case GNOME:
                host = new File(prefix, "bin/python3").isFile()
                    && new File(prefix, "libexec/termux-gnome/launch.py").isFile()
                    && (distro.getDe() != DesktopDe.UBUNTU_GNOME
                        || new File(prefix, "libexec/termux-gnome/" + (distro.getRunMode() == DesktopRunMode.NATIVE
                            ? "desktop_native_ubuntu.py" : "desktop_ubuntu_gnome.py")).isFile())
                    && (distro.getRunMode() == DesktopRunMode.NATIVE
                        ? new File(prefix, "bin/gnome-shell").isFile()
                            && new File(prefix, "bin/gnome-session").isFile()
                            && nativeGnomeServicesInstalled()
                            && (distro.getDe() != DesktopDe.UBUNTU_GNOME || nativeUbuntuInstalled())
                        : new File(prefix, "bin/Xlorie").isFile());
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
        if (distro.getDe() == DesktopDe.KWIN_PLASMA) {
            File rendering = new File(prefix, "var/lib/termux-desktop/rendering/"
                + distro.getProotDistro() + "." + distro.getHostGlMode().getStorageValue()
                + "." + distro.getGuestGlMode().getStorageValue() + ".env");
            // A newly selected pair needs explicit initialization before the UI offers Start.
            if (!rendering.isFile() || java.nio.file.Files.isSymbolicLink(rendering.toPath())) return false;
        }
        if (distro.getDe() == DesktopDe.UBUNTU_GNOME && !ubuntuGnomeInstalled(container)) return false;
        if (container != null && distro.getDe() == DesktopDe.HYPRLAND
                && distro.getGuestGlMode() == com.termux.ui.desktop.domain.DesktopGuestGlMode.ZINK) {
            return "b711d1d9ab5537ee9085da8896dfe2115e791b33aa317fe37538f276c967d687".equals(
                head(new File(container, ".desktop-de-hyprland-zink"), 80).trim())
                && new File(container, ".desktop-de-hyprland").isFile()
                && new File(container, "rootfs/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json").isFile()
                && new File(container, "rootfs/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so").isFile()
                && new File(container, "rootfs/usr/bin/python3").isFile();
        }
        // The marker is written by the install script once the desktop packages are in the
        // container; a half-provisioned instance (interrupted apt) stays "not installed".
        return container != null
            && new File(container, ".desktop-de-" + distro.getDe().getStorageValue()).isFile();
    }

    public boolean hyprlandRuntimeInstalled(DesktopDistro distro) {
        if (distro.getDe() != DesktopDe.HYPRLAND) return false;
        File root = new File(prefix, "opt/termux-hyprland/" + distro.getHyprlandRuntimeId());
        for (String name : new String[]{"bin/Hyprland", "bin/hyprctl", "bin/Xwayland",
                "lib/hyprland/android/libhyprland-system-gl.so", "lib/hyprland/android/libvndksupport.so"})
            if (!new File(root, name).isFile()) return false;
        return HyprlandComponents.RUNTIME_MANIFEST.equals(head(new File(root, ".installed"), 80).trim());
    }

    public boolean hyprlandThemeInstalled(DesktopDistro distro) {
        String theme = distro.getHyprlandThemeId();
        for (String file : HyprlandComponents.requiredFiles(theme))
            if (!new File(prefix, file).isFile()) return false;
        File root = new File(prefix, "share/termux-hyprland/themes/" + theme);
        if (!HyprlandComponents.themeManifest(theme).equals(head(new File(root, ".installed"), 80).trim())) return false;
        for (String file : HyprlandComponents.resourceFiles(theme))
            if (!new File(root, file).isFile() || java.nio.file.Files.isSymbolicLink(new File(root, file).toPath())) return false;
        return true;
    }

    private boolean ubuntuGnomeInstalled(File container) {
        if (container == null) return false;
        File root = new File(container, "rootfs");
        String release = head(new File(root, "etc/os-release"), 8192);
        if (!java.util.regex.Pattern.compile("(?m)^ID=[\"']?ubuntu[\"']?\\s*$").matcher(release).find()
                || !java.util.regex.Pattern.compile("(?m)^VERSION_CODENAME=[\"']?noble[\"']?\\s*$").matcher(release).find()) return false;
        for (String file : new String[]{"usr/bin/gnome-shell", "usr/bin/gnome-extensions", "usr/bin/gdbus",
                "usr/bin/nautilus", "usr/bin/gnome-terminal", "usr/bin/gnome-text-editor", "usr/libexec/gsd-xsettings",
                "usr/share/gnome-session/sessions/ubuntu.session", "usr/share/xsessions/ubuntu-xorg.desktop",
                "usr/share/gnome-shell/modes/ubuntu.json", "usr/share/gnome-shell/theme/Yaru/gnome-shell.css",
                "usr/share/gnome-shell/theme/Yaru/gnome-shell-theme.gresource", "usr/share/gnome-shell/theme/Yaru/gnome-shell-icons.gresource", "usr/share/themes/Yaru/gtk-3.0/gtk.css",
                "usr/share/icons/Yaru/index.theme", "usr/share/sounds/Yaru/index.theme",
                "usr/share/backgrounds/warty-final-ubuntu.png", "usr/share/glib-2.0/schemas/gschemas.compiled",
                "usr/share/glib-2.0/schemas/10_ubuntu-settings.gschema.override",
                "usr/share/dbus-1/services/ca.desrt.dconf.service", "usr/share/dbus-1/services/org.gtk.vfs.Daemon.service",
                "usr/share/dbus-1/services/org.gnome.Nautilus.service", "usr/share/dbus-1/services/org.gnome.Terminal.service",
                "usr/share/dbus-1/services/org.gnome.TextEditor.service"}) {
            if (!new File(root, file).isFile()) return false;
        }
        for (String uuid : new String[]{"ubuntu-dock@ubuntu.com", "ubuntu-appindicators@ubuntu.com",
                "ding@rastersoft.com", "tiling-assistant@ubuntu.com"}) {
            File extension = new File(root, "usr/share/gnome-shell/extensions/" + uuid);
            if (!new File(extension, "metadata.json").isFile() || !new File(extension, "extension.js").isFile()) return false;
        }
        return true;
    }

    private boolean nativeUbuntuInstalled() {
        if (!"5a767f2ef030ea401015e9c4afad6cfc582c203c6f4eb30e079ae6935474ff03".equals(
                head(new File(prefix, "share/termux-native-ubuntu/.installed"), 80).trim())) return false;
        for (String file : new String[]{"bin/gnome-terminal", "bin/gnome-text-editor", "bin/gjs",
                "share/termux-native-ubuntu/manifest.json", "share/gnome-shell/modes/termux-ubuntu.json",
                "share/gnome-shell/theme/Yaru/gnome-shell-theme.gresource",
                "share/themes/Yaru/gtk-3.0/gtk.css", "share/icons/Yaru/index.theme",
                "share/backgrounds/termux-native-ubuntu/default.png",
                "lib/girepository-1.0/Gtk-3.0.typelib", "lib/girepository-1.0/GnomeDesktop-3.0.typelib",
                "share/dbus-1/services/org.gnome.Terminal.service", "share/dbus-1/services/org.gnome.TextEditor.service"}) {
            if (!new File(prefix, file).isFile()) return false;
        }
        for (String uuid : new String[]{"ubuntu-dock@ubuntu.com", "appindicatorsupport@rgcjonas.gmail.com", "ding@rastersoft.com"}) {
            File extension = new File(prefix, "share/gnome-shell/extensions/" + uuid);
            if (!new File(extension, "metadata.json").isFile() || !new File(extension, "extension.js").isFile()) return false;
        }
        return true;
    }

    private boolean nativeGnomeServicesInstalled() {
        // Required by desktop_gnome_launch.py's core D-Bus activation policy.
        for (String service : new String[]{"ca.desrt.dconf", "org.gtk.vfs.Daemon", "org.gnome.Nautilus"}) {
            if (!new File(prefix, "share/dbus-1/services/" + service + ".service").isFile()) return false;
        }
        return true;
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

    /** Native GNOME uses its own work/runtime and never reuses KWin's socket. */
    public File gnomeWaylandSocket(DesktopDistro distro) {
        if (!distro.getDe().isGnomeSession() || distro.getRunMode()!=DesktopRunMode.NATIVE)
            throw new IllegalArgumentException("desktop_gnome_native_required");
        return new File(home,".termux/gnome-"+distro.getId()+"/runtime/wayland-gnome");
    }

    public File hyprlandWaylandSocket(DesktopDistro distro) {
        return new File(home, ".termux/hyprland-" + distro.getId() + "/runtime/wayland-hyprland");
    }

    public static boolean hyprlandReadyMarkerMatches(File pidFile, long currentPid) {
        File marker = new File(pidFile.getPath() + ".hyprland-ready");
        return currentPid > 0 && marker.isFile() && marker.length() <= 32
            && Long.toString(currentPid).equals(head(marker, 32).trim());
    }

    /** A stale marker from an earlier launcher must not mark a new process ready. */
    public static boolean gnomeReadyMarkerMatches(File pidFile,long currentPid) {
        if (currentPid<=0) return false;
        File marker=new File(pidFile.getPath()+".gnome-ready");
        if (!marker.isFile() || marker.length()>32) return false;
        return Long.toString(currentPid).equals(head(marker,32).trim());
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
