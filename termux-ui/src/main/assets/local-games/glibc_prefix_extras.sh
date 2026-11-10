# Native prebuilt font verification and read-only shared GLIBC locale checks. Sourced by bootstrap_termux_box.sh, which the prefix
# setup task runs when a game is imported or its runtime options are saved, and by
# start_termux_box_game.sh. Font verification is read-only; no runtime registry repair.
#
# Callers provide TERMUX_GLIBC_DIR, GLIBC_BIN, WINEPREFIX and TERMUX_BOX_RUN_DIR.

ensure_games_wine_fonts() {
    _glibc_font_dir="$TERMUX_GLIBC_DIR/share/fonts"
    _font_source="$_glibc_font_dir/NotoSansCJK-Regular.ttc"
    _serif_source="$_glibc_font_dir/NotoSerifCJK-Regular.ttc"
    # The GLIBC Wine runtime resolves font fallback through FONTCONFIG_PATH, whose
    # configured source is $TERMUX_GLIBC_DIR/share/fonts.  Keeping a TTC only in
    # C:\\Windows\\Fonts leaves that source empty and produces tofu glyphs.
    _font_marker="$WINEPREFIX/termux-boxmeta/wine-fonts-v2"
    [ -f "$_font_source" ] && [ -f "$_serif_source" ] &&
    [ -f "$WINEPREFIX/drive_c/windows/Fonts/NotoSansCJK-Regular.ttc" ] &&
    [ -f "$WINEPREFIX/drive_c/windows/Fonts/NotoSerifCJK-Regular.ttc" ] || {
        echo "ERROR: Required Wine fonts runtime component is not activated."
        return 1
    }
    _font_registry_is_persisted() {
        grep -F '"Tahoma"="Noto Sans CJK SC"' "$WINEPREFIX/system.reg" >/dev/null 2>&1 && \
            grep -F '"Noto Sans CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"' \
                "$WINEPREFIX/system.reg" >/dev/null 2>&1
    }
    [ -f "$_font_marker" ] && _font_registry_is_persisted && return 0
    echo "ERROR: Native prebuilt prefix font state is invalid."
    return 1
}

# Generated and verified by install_termux_glibc_runtime.sh. Never repair at game time.
ensure_games_cjk_locale() {
    [ -f "$TERMUX_BOX_RUN_DIR/wine-zh-cn-locale-v1" ] &&
    [ -f "$TERMUX_GLIBC_DIR/lib/locale/zh_CN.utf8/LC_CTYPE" ] && [ -s "$TERMUX_GLIBC_DIR/lib/locale/zh_CN.utf8/LC_CTYPE" ] &&
    [ -f "$TERMUX_GLIBC_DIR/lib/locale/zh_CN.gbk/LC_CTYPE" ] && [ -s "$TERMUX_GLIBC_DIR/lib/locale/zh_CN.gbk/LC_CTYPE" ] || {
        echo "ERROR: glibc_cjk_locale_missing"
        return 1
    }
    export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8
}
