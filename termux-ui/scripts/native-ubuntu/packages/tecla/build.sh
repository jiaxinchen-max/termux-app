TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/tecla
TERMUX_PKG_DESCRIPTION="GNOME keyboard layout previewer"
TERMUX_PKG_LICENSE="GPL-2.0-or-later"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=48.0.2
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/tecla/48/tecla-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=783d3464d2a2cf7eb1507649dbd9ff09ce24852c2a6c9a0d365db84063d3d401
TERMUX_PKG_DEPENDS="glib, gtk4, libadwaita, libxkbcommon, libwayland"
TERMUX_PKG_BUILD_DEPENDS="glib-cross"
termux_step_pre_configure() {
    termux_setup_glib_cross_pkg_config_wrapper
}
