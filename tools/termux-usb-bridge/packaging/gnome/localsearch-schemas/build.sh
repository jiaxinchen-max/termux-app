TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/localsearch
TERMUX_PKG_DESCRIPTION="Upstream localsearch settings schemas only (no daemon)"
TERMUX_PKG_LICENSE="GPL-2.0-or-later"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=3.9.0
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/localsearch/3.9/localsearch-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=d42f408dc3fb28fe54f5a9abbf5f1decf5818db9c2e9ec51c09464bdfd0c14b9
TERMUX_PKG_DEPENDS="glib"
TERMUX_PKG_BUILD_DEPENDS="glib-cross"
TERMUX_PKG_PLATFORM_INDEPENDENT=true

termux_step_post_get_source() {
    cp "$TERMUX_PKG_BUILDER_DIR/meson-schemas.build" "$TERMUX_PKG_SRCDIR/meson.build"
}
termux_step_pre_configure() {
    termux_setup_glib_cross_pkg_config_wrapper
}
