TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/nautilus
TERMUX_PKG_DESCRIPTION="GNOME Files native file manager"
TERMUX_PKG_LICENSE="GPL-3.0-or-later"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=48.3
TERMUX_PKG_REVISION=3
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/nautilus/48/nautilus-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=21a2aea005160db083c7a1d4d2c0989b845cc722c04ef9ebb60125a7ec5b393d
# tinysparql's dlopened parser needs libstemmer; its current recipe lists it
# only as a build dependency, so ELF checks on nautilus cannot discover it.
TERMUX_PKG_DEPENDS="gexiv2 (>= 0.14.7), gexiv2 (<< 0.15), gdk-pixbuf, glib, gnome-autoar, gnome-desktop4, gst-plugins-base, gstreamer, gtk4, libadwaita, libportal, libportal-gtk4, libstemmer, localsearch-schemas, tinysparql"
TERMUX_PKG_BUILD_DEPENDS="glib-cross"
TERMUX_PKG_EXTRA_CONFIGURE_ARGS="-Dpackagekit=false -Dcloudproviders=false -Dselinux=false -Dintrospection=false -Dtests=none -Ddocs=false"

termux_step_pre_configure() {
    termux_setup_glib_cross_pkg_config_wrapper
    cp "$TERMUX_PKG_BUILDER_DIR/android-gettext-annotations.h" "$TERMUX_PKG_SRCDIR/"
    CFLAGS+=" -include $TERMUX_PKG_SRCDIR/android-gettext-annotations.h"
    export TERMUX_MESON_ENABLE_SOVERSION=1
}
