#!/bin/sh
# Current specs require both renderer layers; previous versions are unsupported.
desktop_resolve_rendering() {
    [ "${DESKTOP_SPEC_VERSION:-}" = 3 ] || return 64
    [ -n "${DESKTOP_HOST_GL_MODE:-}" ] && [ -n "${DESKTOP_GUEST_GL_MODE:-}" ] || return 64
    [ -z "${DESKTOP_GL_MODE:-}" ] || return 64
    case "${DESKTOP_MODE:-}:${DESKTOP_DE:-}:$DESKTOP_HOST_GL_MODE:$DESKTOP_GUEST_GL_MODE" in
        native:xfce4:system:none|native:xfce4:zink:none|native:xfce4:llvmpipe:none|\
        native:kwin_plasma:system:none|native:kwin_plasma:zink:none|native:kwin_plasma:llvmpipe:none|\
        native:gnome:system:none|native:ubuntu_gnome:system:none|native:hyprland:system:none|\
        proot:xfce4:system:default|proot:xfce4:zink:default|proot:xfce4:llvmpipe:default|\
        proot:kwin_plasma:zink:zink|proot:kwin_plasma:llvmpipe:llvmpipe|\
        proot:gnome:system:llvmpipe|proot:gnome:zink:zink|proot:ubuntu_gnome:system:llvmpipe|\
        proot:hyprland:system:default|proot:hyprland:system:zink) ;;
        *) return 64 ;;
    esac
    if [ "${DESKTOP_MODE:-}:${DESKTOP_DE:-}" = native:ubuntu_gnome ]; then
        [ "${DESKTOP_GNOME_SESSION_PROFILE:-}:${DESKTOP_USB_STORAGE:-}:${DESKTOP_REFRESH_RATE:-}" = native-ubuntu:off:60 ] || return 64
        [ -z "${DESKTOP_CONTAINER_BASE:-}${DESKTOP_CONTAINER_CODENAME:-}${DESKTOP_GNOME_DISPLAY_GL_MODE:-}${DESKTOP_GNOME_DISPLAY_BUFFER_MODE:-}" ] || return 64
        [ "${DESKTOP_GNOME_BACKEND:-}:${DESKTOP_GNOME_RENDERER:-}:${DESKTOP_GNOME_SESSION_STARTUP:-}" = android:system:direct ] || return 64
    elif [ "${DESKTOP_DE:-}" = ubuntu_gnome ]; then
        [ "${DESKTOP_CONTAINER_BASE:-}:${DESKTOP_CONTAINER_CODENAME:-}:${DESKTOP_GNOME_SESSION_PROFILE:-}:${DESKTOP_USB_STORAGE:-}" = ubuntu:noble:noble:ubuntu:off ] || return 64
        [ "${DESKTOP_GNOME_BACKEND:-}:${DESKTOP_GNOME_RENDERER:-}:${DESKTOP_GNOME_SESSION_STARTUP:-}:${DESKTOP_GNOME_DISPLAY_GL_MODE:-}:${DESKTOP_GNOME_DISPLAY_BUFFER_MODE:-}" = x11:llvmpipe:direct:system:native ] || return 64
    fi
    # Component package/launcher routing; host and guest environments use their explicit fields.
    case "${DESKTOP_MODE:-}:${DESKTOP_DE:-}" in
        native:*|proot:xfce4) DESKTOP_COMPONENT_RENDERER="$DESKTOP_HOST_GL_MODE" ;;
        proot:hyprland) case "$DESKTOP_GUEST_GL_MODE" in default) DESKTOP_COMPONENT_RENDERER=system ;; *) DESKTOP_COMPONENT_RENDERER="$DESKTOP_GUEST_GL_MODE" ;; esac ;;
        *) DESKTOP_COMPONENT_RENDERER="$DESKTOP_GUEST_GL_MODE" ;;
    esac
}
