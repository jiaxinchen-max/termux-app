package com.termux.ui.desktop.activity;

import com.termux.ui.R;
import com.termux.ui.desktop.domain.DesktopDe;

final class DesktopCardAppearance {
    private DesktopCardAppearance() { }

    static int art(DesktopDe de) {
        switch (de) {
            case XFCE4: return R.drawable.desktop_art_xfce;
            case KWIN_PLASMA: return R.drawable.desktop_art_plasma;
            case HYPRLAND: return R.drawable.desktop_art_hyprland;
            default: return R.drawable.desktop_art_ubuntu;
        }
    }

    static int cover(DesktopDe de) {
        switch (de) {
            case XFCE4: return R.drawable.desktop_cover_xfce;
            case KWIN_PLASMA: return R.drawable.desktop_cover_plasma;
            case HYPRLAND: return R.drawable.desktop_cover_hyprland;
            default: return R.drawable.desktop_cover_ubuntu;
        }
    }
}
