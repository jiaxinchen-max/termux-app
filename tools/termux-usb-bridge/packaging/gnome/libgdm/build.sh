TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/gdm
TERMUX_PKG_DESCRIPTION="GDM D-Bus client library for GNOME (no display manager or login sessions)"
TERMUX_PKG_LICENSE="GPL-2.0"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=48.0
TERMUX_PKG_REVISION=1
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/gdm/48/gdm-48.0.tar.xz
TERMUX_PKG_SHA256=1bc06daff093ec7b5e37ecb4f92e5da3474a1b1ba076edb9151ee967d1c30adf
TERMUX_PKG_DEPENDS="glib, gobject-introspection"
TERMUX_PKG_BUILD_DEPENDS="g-ir-scanner, glib-cross"
TERMUX_PKG_VERSIONED_GIR=false
TERMUX_PKG_EXTRA_CONFIGURE_ARGS=""

termux_step_post_get_source() {
    cp "$TERMUX_PKG_BUILDER_DIR/meson-client.build" "$TERMUX_PKG_SRCDIR/meson.build"
    cp "$TERMUX_PKG_BUILDER_DIR/android-no-logind.h" "$TERMUX_PKG_SRCDIR/android-no-logind.h"
    cp "$TERMUX_PKG_BUILDER_DIR/gdm-user-switching-android.c" "$TERMUX_PKG_SRCDIR/libgdm/gdm-user-switching.c"
    sed -i 's|#include <systemd/sd-login.h>|#include "../android-no-logind.h"|' "$TERMUX_PKG_SRCDIR/libgdm/gdm-sessions.c"
}

termux_step_pre_configure() {
    termux_setup_gir
    termux_setup_glib_cross_pkg_config_wrapper
    export TERMUX_MESON_ENABLE_SOVERSION=1
}
