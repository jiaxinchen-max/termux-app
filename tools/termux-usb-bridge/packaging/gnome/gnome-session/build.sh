TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/gnome-session
TERMUX_PKG_DESCRIPTION="GNOME native session manager using upstream's non-systemd backend"
TERMUX_PKG_LICENSE="GPL-2.0-or-later"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=48.0
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/gnome-session/48/gnome-session-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=dd909fbc5b22cdbdb2fc4df1a47d78d1b5943ccc5e61e6a20a1846246347c417
TERMUX_PKG_DEPENDS="glib, gnome-desktop3, gtk3, json-glib, libice, libsm, libx11, libxcomposite, libepoxy, opengl"
TERMUX_PKG_BUILD_DEPENDS="glib-cross, xtrans"
TERMUX_PKG_EXTRA_CONFIGURE_ARGS="-Dsystemd=false -Ddocbook=false -Dman=false"

termux_step_pre_configure() {
    termux_setup_glib_cross_pkg_config_wrapper
    cp "$TERMUX_PKG_BUILDER_DIR/android-systemd-optional.h" "$TERMUX_PKG_SRCDIR/gnome-session/"
}
