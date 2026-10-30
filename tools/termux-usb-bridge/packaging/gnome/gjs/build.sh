TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/gjs
TERMUX_PKG_DESCRIPTION="GNOME JavaScript bindings for GObject (SpiderMonkey 128)"
TERMUX_PKG_LICENSE="MIT, LGPL-2.0"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=1.82.3
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/gjs/1.82/gjs-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=63e84b9c82a60d166c8704322f8907945e25d9bbd0b80485468d3126505c027d
TERMUX_PKG_DEPENDS="glib, gobject-introspection, libffi, libcairo, libc++, spidermonkey"
TERMUX_PKG_BUILD_DEPENDS="g-ir-scanner, glib-cross"
TERMUX_PKG_VERSIONED_GIR=false
TERMUX_PKG_EXTRA_CONFIGURE_ARGS="
-Dprofiler=disabled
-Dreadline=disabled
-Dinstalled_tests=false
-Dbuild_tests=false
-Dskip_dbus_tests=true
-Dskip_gtk_tests=true
"

termux_step_pre_configure() {
    termux_setup_gir
    termux_setup_glib_cross_pkg_config_wrapper
    export TERMUX_MESON_ENABLE_SOVERSION=1
}
