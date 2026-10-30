TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/gnome-autoar
TERMUX_PKG_DESCRIPTION="GNOME archive extraction and creation using GIO streams"
TERMUX_PKG_LICENSE="LGPL-2.1"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=0.4.5
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/gnome-autoar/0.4/gnome-autoar-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=838c5306fc38bfaa2f23abe24262f4bf15771e3303fb5dcb74f5b9c7a615dabe
TERMUX_PKG_DEPENDS="glib, libarchive"
TERMUX_PKG_BUILD_DEPENDS="glib-cross"
TERMUX_PKG_EXTRA_CONFIGURE_ARGS="-Dgtk=false -Dintrospection=disabled -Dtests=false -Dgtk_doc=false"

termux_step_pre_configure() {
    termux_setup_glib_cross_pkg_config_wrapper
    export TERMUX_MESON_ENABLE_SOVERSION=1
}
