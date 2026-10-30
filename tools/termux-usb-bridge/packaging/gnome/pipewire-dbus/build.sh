TERMUX_PKG_HOMEPAGE=https://pipewire.org/
TERMUX_PKG_DESCRIPTION="Upstream PipeWire D-Bus and camera portal modules for rootless desktops"
TERMUX_PKG_LICENSE="MIT"
TERMUX_PKG_LICENSE_FILE="COPYING"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=1.4.9
TERMUX_PKG_SRCURL=https://github.com/PipeWire/pipewire/archive/refs/tags/${TERMUX_PKG_VERSION}.tar.gz
TERMUX_PKG_SHA256=8066a7b220069e4c6e3b02bd2b6ea303bba66df255023c07c99323449ba8fe3c
TERMUX_PKG_DEPENDS="dbus, pipewire (>= 1.4.9), pipewire (<< 1.4.10)"

termux_step_post_get_source() {
    cp "$TERMUX_PKG_BUILDER_DIR/meson-addon.build" "$TERMUX_PKG_SRCDIR/meson.build"
    rm "$TERMUX_PKG_SRCDIR/meson_options.txt"
}
