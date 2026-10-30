TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/gnome-shell
TERMUX_PKG_DESCRIPTION="GNOME Shell for rootless Android/Bionic X11 and nested Wayland"
TERMUX_PKG_LICENSE="GPL-2.0-or-later"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=48.7
TERMUX_PKG_REVISION=1
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/gnome-shell/48/gnome-shell-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=2dff328d5f7e29f8c897bc21caac7d2d443be3c63ae84d7623dec800f23493c3
TERMUX_PKG_DEPENDS="accountsservice, at-spi2-core, gcr4, gdk-pixbuf, geoclue, gjs, glib, gnome-desktop4, gnome-settings-daemon-schemas, gsettings-desktop-schemas, gtk4, ibus, libcairo, libgdm (>= 48.0-1), libgweather, libxml2, libx11, libxext, libxfixes, mutter, pango, pipewire, pipewire-dbus, polkit, pulseaudio-glib, librsvg, libsoup3, upower"
TERMUX_PKG_BUILD_DEPENDS="g-ir-scanner, glib-cross"
TERMUX_PKG_VERSIONED_GIR=false
TERMUX_PKG_EXTRA_CONFIGURE_ARGS="
-Dsystemd=false
-Dnetworkmanager=false
-Dportal_helper=false
-Dcalendar_server=false
-Dextensions_app=false
-Dtests=false
-Dman=false
-Dgtk_doc=false
"

termux_step_pre_configure() {
    termux_setup_gir
    termux_setup_glib_cross_pkg_config_wrapper
    export TERMUX_MESON_ENABLE_SOVERSION=1
}
