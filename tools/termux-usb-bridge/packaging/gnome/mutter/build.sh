TERMUX_PKG_HOMEPAGE=https://mutter.gnome.org/
TERMUX_PKG_DESCRIPTION="A Wayland display server and X11 window manager and compositor library"
TERMUX_PKG_LICENSE="GPL-2.0-or-later, LGPL-3.0-only"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION="50.1"
TERMUX_PKG_REVISION=1
TERMUX_PKG_SRCURL="https://download.gnome.org/sources/mutter/${TERMUX_PKG_VERSION%%.*}/mutter-$TERMUX_PKG_VERSION.tar.xz"
TERMUX_PKG_SHA256=9344502ce473f788795f26b45f8b8ff53c0a36b867470d705a11a3ee0911021e
TERMUX_PKG_DEPENDS="atk, fribidi, gdk-pixbuf, glib, gnome-desktop4, gobject-introspection, graphene, gsettings-desktop-schemas, gtk4, harfbuzz, libandroid-shmem, libcairo, libcanberra, libcolord, libdisplay-info, libdrm, libei, libice, libpixman, libsm, libwayland, libx11, libxau, libxcb, libxcomposite, libxcursor, libxdamage, libxext, libxfixes, libxi, libxinerama, libxkbcommon, libxkbfile, libxrandr, libxtst, littlecms, opengl, pango, pipewire, startup-notification, xkeyboard-config, xwayland"
TERMUX_PKG_BUILD_DEPENDS="glib-cross, libwayland-protocols"
TERMUX_PKG_DEPENDS+=", libepoxy, termux-render, libandroid-stub"
TERMUX_PKG_VERSIONED_GIR=false

# TODO: Add libwacom
TERMUX_PKG_EXTRA_CONFIGURE_ARGS="
-Dlogind=false
-Dudev=false
-Dnative_backend=false
-Dandroid_backend=true
-Dremote_desktop=false
-Ddevkit=disabled
-Dlibwacom=false
-Dintrospection=true
-Dtests=disabled
-Dinstalled_tests=false
-Dbash_completion=false
-Dprofiler=false
-Dxwayland_path=$TERMUX_PREFIX/bin/Xwayland
"

termux_step_post_get_source() {
    mkdir -p "$TERMUX_PKG_SRCDIR/src/backends/android"
    cp "$TERMUX_PKG_BUILDER_DIR"/android/* "$TERMUX_PKG_SRCDIR/src/backends/android/"
    cp "$TERMUX_PKG_BUILDER_DIR/android/termux-ahardware-buffer-v1.xml" \
        "$TERMUX_PKG_SRCDIR/src/wayland/protocol/"
}

termux_step_pre_configure() {
	termux_setup_gir
	termux_setup_glib_cross_pkg_config_wrapper
	termux_setup_wayland_cross_pkg_config_wrapper

	export TERMUX_MESON_ENABLE_SOVERSION=1
	LDFLAGS+=" -landroid-shmem"
}
