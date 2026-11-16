TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/gnome-shell
TERMUX_PKG_DESCRIPTION="GNOME Shell for the native Android EGL/AHardwareBuffer backend"
TERMUX_PKG_LICENSE="GPL-2.0-or-later"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=50.1
TERMUX_PKG_REVISION=1
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/gnome-shell/50/gnome-shell-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=1b47760172c14f3f4edd1c9aff365f4de45583517bf0f80df4d3acbd4e4cb294
TERMUX_PKG_DEPENDS="accountsservice, at-spi2-core, gcr4, gdk-pixbuf, geoclue, gjs (>= 1.88.0), glib (>= 2.86.0), gnome-desktop4, gnome-settings-daemon-schemas, gsettings-desktop-schemas, gtk4, ibus, libcairo, libgdm (>= 48.0-1), libgweather, libxml2, libx11, libxext, libxfixes, mutter (>= 50.1), pango, polkit, pulseaudio-glib, librsvg, libsoup3, upower"
TERMUX_PKG_BUILD_DEPENDS="g-ir-scanner, glib-cross"
TERMUX_PKG_VERSIONED_GIR=false
TERMUX_PKG_EXTRA_CONFIGURE_ARGS="
-Dsystemd=false
-Dcamera_monitor=false
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
