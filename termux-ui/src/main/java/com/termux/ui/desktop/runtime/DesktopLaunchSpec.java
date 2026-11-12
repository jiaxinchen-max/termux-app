package com.termux.ui.desktop.runtime;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;

/**
 * The {@code KEY=value} spec handed to the desktop shell scripts.
 *
 * <p>The scripts {@code source} this file, so every value is restricted to a conservative
 * character set and written unquoted-safe; nothing user-typed (such as the display name) is ever
 * included.
 */
public final class DesktopLaunchSpec {

    public static final int SCHEMA_VERSION = 2;
    /** The lorie render server socket that KWin's libtermux-render connects to. */
    public static final String RENDER_SOCKET_PATH = "/data/data/com.termux/files/home/tmp/termux-render";
    /** Logged by {@code start-plasma} once KWin's Android EGL backend is up. */
    public static final String READY_LOG_MARKER = "Android EGL backend initialized";
    /** Pinned release of the KWin Termux hardware-acceleration packages. */
    public static final String RELEASE_TAG = "termux-hwaccel-6.6-r28";

    private DesktopLaunchSpec() {}

    public static String encode(DesktopDistro distro, String pidFile, String statusFile,
                                String logFile) {
        DesktopRendererPolicy.requireSupported(distro);
        StringBuilder spec = new StringBuilder();
        put(spec, "DESKTOP_SPEC_VERSION", Integer.toString(SCHEMA_VERSION));
        put(spec, "DESKTOP_ID", distro.getId());
        put(spec, "DESKTOP_MODE", distro.getRunMode().getStorageValue());
        put(spec, "DESKTOP_REMOVE_METHOD", "proot-distro");
        put(spec, "DESKTOP_DE", distro.getDe().getStorageValue());
        put(spec, "DESKTOP_GL_MODE", distro.getGlMode().getStorageValue());
        put(spec, "DESKTOP_AUDIO_MODE", "host-pulseaudio");
        put(spec, "DESKTOP_USB_STORAGE", distro.getUsbStorage().getStorageValue());
        put(spec, "DESKTOP_PROOT_DISTRO", distro.getProotDistro());
        put(spec, "DESKTOP_RESOLUTION", distro.getResolution());
        put(spec, "DESKTOP_WIDTH", Integer.toString(distro.getWidth()));
        put(spec, "DESKTOP_HEIGHT", Integer.toString(distro.getHeight()));
        put(spec, "DESKTOP_REFRESH_RATE", Integer.toString(distro.getRefreshRate()));
        put(spec, "DESKTOP_PID_FILE", requirePath(pidFile));
        put(spec, "DESKTOP_STATUS_FILE", requirePath(statusFile));
        put(spec, "DESKTOP_LOG_FILE", requirePath(logFile));
        put(spec, "DESKTOP_RENDER_SOCKET", RENDER_SOCKET_PATH);
        put(spec, "DESKTOP_RELEASE_TAG", RELEASE_TAG);
        if (distro.getDe() == DesktopDe.HYPRLAND) {
            put(spec, "DESKTOP_HYPRLAND_RELEASE", "hyprland-android-0.57.0-70ec30e7-r7");
            put(spec, "DESKTOP_HYPRLAND_BACKEND", "android");
            put(spec, "DESKTOP_HYPRLAND_HOST_GL_MODE", "system");
            put(spec, "DESKTOP_HYPRLAND_CLIENT_GL_MODE",
                distro.getGlMode() == DesktopGlMode.ZINK ? "zink" : "default");
        }
        if (distro.getDe() == DesktopDe.GNOME) {
            put(spec, "DESKTOP_GNOME_SESSION_STARTUP", "direct");
            put(spec, "DESKTOP_GNOME_BACKEND", distro.getRunMode() == DesktopRunMode.NATIVE ? "android" : "x11");
            put(spec, "DESKTOP_GNOME_RENDERER", distro.getGlMode().getStorageValue());
            if (distro.getRunMode() == DesktopRunMode.PROOT) {
                put(spec, "DESKTOP_CONTAINER_CODENAME", "trixie");
                // Each guest renderer requires its exact host display/buffer topology.
                switch (distro.getGlMode()) {
                    case ZINK:
                        put(spec, "DESKTOP_GNOME_DISPLAY_GL_MODE", "zink");
                        put(spec, "DESKTOP_GNOME_DISPLAY_BUFFER_MODE", "dmabuf");
                        break;
                    case LLVMPIPE:
                        put(spec, "DESKTOP_GNOME_DISPLAY_GL_MODE", "system");
                        put(spec, "DESKTOP_GNOME_DISPLAY_BUFFER_MODE", "native");
                        break;
                    default:
                        throw new IllegalArgumentException("desktop_renderer_explicit_required");
                }
            }
        }
        return spec.toString();
    }

    private static void put(StringBuilder spec, String key, String value) {
        if (!value.matches("[A-Za-z0-9._/:=@x_-]*")) {
            throw new IllegalArgumentException("unsafe_desktop_spec_value:" + key);
        }
        spec.append(key).append('=').append(value).append('\n');
    }

    private static String requirePath(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("desktop_spec_path_must_be_absolute");
        }
        return path;
    }
}
