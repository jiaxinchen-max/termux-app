TERMUX_PKG_HOMEPAGE=https://gitlab.gnome.org/GNOME/gnome-control-center
TERMUX_PKG_DESCRIPTION="GNOME Settings for Android native desktop sessions"
TERMUX_PKG_LICENSE="GPL-2.0-or-later"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=48.5
TERMUX_PKG_SRCURL=https://download.gnome.org/sources/gnome-control-center/48/gnome-control-center-${TERMUX_PKG_VERSION}.tar.xz
TERMUX_PKG_SHA256=44aeb47b3fde8a1b8149a49dfd61aa8d01b712606c1877c488bfa3ab9fd6f9c1
TERMUX_PKG_DEPENDS="fontconfig, gdk-pixbuf, glib, gnome-desktop4, gsound, gsettings-desktop-schemas, gtk4, ibus, libadwaita, libcairo, libx11, libxml2, polkit, pulseaudio, pulseaudio-glib, tecla"
TERMUX_PKG_BUILD_DEPENDS="glib-cross"
TERMUX_PKG_EXTRA_CONFIGURE_ARGS="
-Dtests=false
-Ddocumentation=false
-Dsnap=false
-Dmalcontent=false
-Dlocation-services=disabled
-Dwayland=true
"
termux_step_pre_configure() {
    termux_setup_glib_cross_pkg_config_wrapper
}
