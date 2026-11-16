TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/gjs
TERMUX_PKG_DESCRIPTION="GNOME JavaScript bindings for GObject (SpiderMonkey 140)"
TERMUX_PKG_LICENSE="MIT, LGPL-2.0"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=1.88.0
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/gjs/1.88/gjs-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=30a0b9f3317e8e60b1896db2903c70e8b0cd33df953c328755803a75191dc453
TERMUX_PKG_DEPENDS="glib, gobject-introspection, libffi, libcairo, libc++, spidermonkey (>= 140.17.0)"
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
    # SpiderMonkey 140 profiler headers use XP_UNIX before including js-config.h.
    CXXFLAGS+=" -DXP_UNIX"
    termux_setup_gir
    termux_setup_glib_cross_pkg_config_wrapper
    export TERMUX_MESON_ENABLE_SOVERSION=1
}
