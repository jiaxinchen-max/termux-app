TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/gnome-settings-daemon
TERMUX_PKG_DESCRIPTION="Upstream gnome-settings-daemon settings schemas only (no daemon)"
TERMUX_PKG_LICENSE="GPL-2.0-or-later"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=48.1
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/gnome-settings-daemon/48/gnome-settings-daemon-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=3860a2ea214dcbcb6600ae7a1e3358a5389215087bc3e4a47cee3f87baee062e
TERMUX_PKG_DEPENDS="glib"
TERMUX_PKG_BUILD_DEPENDS="glib-cross"
TERMUX_PKG_PLATFORM_INDEPENDENT=true

termux_step_post_get_source() {
    cp "$TERMUX_PKG_BUILDER_DIR/meson-schemas.build" "$TERMUX_PKG_SRCDIR/meson.build"
}
termux_step_pre_configure() {
    termux_setup_glib_cross_pkg_config_wrapper
}
