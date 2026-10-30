TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/gnome-shell
TERMUX_PKG_DESCRIPTION="AccountsService D-Bus client library (no account daemon or logind)"
TERMUX_PKG_LICENSE="GPL-3.0"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=23.13.9
TERMUX_PKG_SRCURL=https://www.freedesktop.org/software/accountsservice/accountsservice-23.13.9.tar.xz
TERMUX_PKG_SHA256=adda4cdeae24fa0992e7df3ffff9effa7090be3ac233a3edfdf69d5a9c9b924f
TERMUX_PKG_DEPENDS="glib, gobject-introspection, libcrypt, polkit"
TERMUX_PKG_BUILD_DEPENDS="g-ir-scanner, glib-cross"
TERMUX_PKG_VERSIONED_GIR=false
TERMUX_PKG_EXTRA_CONFIGURE_ARGS="-Dintrospection=true -Dvapi=false"

termux_step_post_get_source() {
    cp "$TERMUX_PKG_BUILDER_DIR/meson-client.build" "$TERMUX_PKG_SRCDIR/meson.build"
    cp "$TERMUX_PKG_BUILDER_DIR/android-no-logind.h" "$TERMUX_PKG_SRCDIR/android-no-logind.h"
    sed -i 's|#include <systemd/sd-login.h>|#include "android-no-logind.h"|' "$TERMUX_PKG_SRCDIR/src/libaccountsservice/act-user-manager.c"
}

termux_step_pre_configure() {
    termux_setup_gir
    termux_setup_glib_cross_pkg_config_wrapper
    export TERMUX_MESON_ENABLE_SOVERSION=1
}
