# Native prebuilt font verification and shared GLIBC locale preparation. Sourced by bootstrap_termux_box.sh, which the prefix
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
    _font_marker="$WINEPREFIX/termux-boxmeta/games-wine-fonts-v2"
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

ensure_games_cjk_locale() {
    _locale_marker="$TERMUX_BOX_RUN_DIR/games-zh-cn-locale-v1"
    if [ ! -f "$_locale_marker" ]; then
        if [ -x "$GLIBC_BIN/locale-gen" ] && [ -f "$TERMUX_GLIBC_DIR/etc/locale.gen" ]; then
            echo "Preparing zh_CN UTF-8 and GBK locales"
            (
                export PATH="$GLIBC_BIN:$PATH"
                sed -i 's/^# *zh_CN.GBK GBK/zh_CN.GBK GBK/' "$TERMUX_GLIBC_DIR/etc/locale.gen"
                sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' "$TERMUX_GLIBC_DIR/etc/locale.gen"
                "$GLIBC_BIN/locale-gen"
            ) || return 0
            [ -d "$TERMUX_GLIBC_DIR/lib/locale/zh_CN.utf8" ] && : > "$_locale_marker"
        fi
    fi
    if [ -f "$_locale_marker" ]; then
        export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8
    fi
}
