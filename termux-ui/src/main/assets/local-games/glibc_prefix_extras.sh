# One-time Wine prefix / GLIBC initialisation that every Native game needs: the Noto CJK fonts and their
# FontLink registry entries, and the zh_CN locales. Sourced by bootstrap_termux_box.sh, which the prefix
# setup task runs when a game is imported or its runtime options are saved, and by
# start_termux_box_game.sh, where it is only a safety net: each function returns at once when its marker
# exists, so a launch never pays for it.
#
# Callers provide: TERMUX_GLIBC_DIR, GLIBC_BIN, WINEPREFIX, TERMUX_BOX_RUN_DIR and a run_container_wine
# function that runs the prefix's wine with the given arguments. GAMES_WINESERVER_BIN may name the wineserver
# to use for flushing the registry (default: $GLIBC_BIN/wineserver).

ensure_games_wine_fonts() {
    _glibc_font_dir="$TERMUX_GLIBC_DIR/share/fonts"
    _font_source="$_glibc_font_dir/NotoSansCJK-Regular.ttc"
    _serif_source="$_glibc_font_dir/NotoSerifCJK-Regular.ttc"
    _font_target="$WINEPREFIX/drive_c/windows/Fonts/NotoSansCJK-Regular.ttc"
    # The GLIBC Wine runtime resolves font fallback through FONTCONFIG_PATH, whose
    # configured source is $TERMUX_GLIBC_DIR/share/fonts.  Keeping a TTC only in
    # C:\\Windows\\Fonts leaves that source empty and produces tofu glyphs.
    _font_marker="$WINEPREFIX/termux-boxmeta/games-wine-fonts-v2"
    _font_registry="$WINEPREFIX/termux-boxmeta/games-wine-fonts.reg"
    _font_link='"Tahoma"=hex(7):4e,00,6f,00,74,00,6f,00,53,00,61,00,6e,00,73,00,43,00,4a,00,4b,00,2d,00,52,00,65,00,67,00,75,00,6c,00,61,00,72,00,2e,00,74,00,74,00,63,00,2c,00,4e,00,6f,00,74,00,6f,00,20,00,53,00,61,00,6e,00,73,00,20,00,43,00,4a,00,4b,00,20,00,53,00,43,00,00,00,00,00'
    [ -f "$_font_source" ] && [ -f "$_serif_source" ] || {
        echo "ERROR: Required Wine fonts runtime component is not activated."
        return 1
    }
    _font_registry_is_persisted() {
        grep -F '"Tahoma"="Noto Sans CJK SC"' "$WINEPREFIX/system.reg" >/dev/null 2>&1 && \
            grep -F '"Noto Sans CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"' \
                "$WINEPREFIX/system.reg" >/dev/null 2>&1
    }
    [ -f "$_font_marker" ] && _font_registry_is_persisted && return 0
    mkdir -p "$(dirname "$_font_target")" "$WINEPREFIX/termux-boxmeta" || return 1
    cp "$_font_source" "$_font_target" || return 1
    cp "$_serif_source" "$WINEPREFIX/drive_c/windows/Fonts/NotoSerifCJK-Regular.ttc" || return 1
    # Wine rewrites system.reg from its in-memory registry on startup.  Appending
    # keys directly therefore produces a marker without durable font fallbacks.
    # Import after the prefix exists and wait for wineserver to flush the hives.
    {
        printf '%s\n' 'REGEDIT4'
        printf '\n'
        for _branch in \
            'HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\Fonts' \
            'HKEY_LOCAL_MACHINE\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\Fonts'; do
            printf '[%s]\n' "$_branch"
            printf '%s\n' '"Noto Sans CJK SC (TrueType)"="NotoSansCJK-Regular.ttc"'
            printf '%s\n' '"Noto Sans CJK SC Bold (TrueType)"="NotoSansCJK-Regular.ttc"'
            printf '\n'
        done
        for _branch in \
            'HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\FontSubstitutes' \
            'HKEY_LOCAL_MACHINE\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\FontSubstitutes'; do
            printf '[%s]\n' "$_branch"
            for _name in Tahoma 'Tahoma Bold' 'MS Shell Dlg' 'MS Shell Dlg 2' \
                'Microsoft Sans Serif' 'MS Sans Serif' 'Lucida Sans Unicode' Arial \
                'Arial Black' 'Segoe UI' 'Segoe UI Semibold' 'Segoe UI Symbol' \
                SimSun NSimSun 'Microsoft YaHei' 'Microsoft YaHei UI' Meiryo; do
                printf '"%s"="Noto Sans CJK SC"\n' "$_name"
            done
            printf '\n'
        done
        for _branch in \
            'HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink' \
            'HKEY_LOCAL_MACHINE\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink'; do
            printf '[%s]\n' "$_branch"
            printf '%s\n' "$_font_link"
            for _name in 'Tahoma Bold' 'MS Shell Dlg' 'MS Shell Dlg 2' \
                'Microsoft Sans Serif' 'MS Sans Serif' 'Lucida Sans Unicode' Arial 'Arial Black'; do
                printf '"%s"=%s\n' "$_name" "${_font_link#*=}"
            done
            printf '\n'
        done
    } > "$_font_registry" || return 1
    if ! run_container_wine regedit "$_font_registry"; then
        echo "ERROR: Wine CJK registry import failed."
        return 1
    fi
    # regedit may return before its wineserver flushes the prefix hives.
    "$GLIBC_BIN/box64" "${GAMES_WINESERVER_BIN:-$GLIBC_BIN/wineserver}" -w >/dev/null 2>&1 || true
    "$GLIBC_BIN/box64" "${GAMES_WINESERVER_BIN:-$GLIBC_BIN/wineserver}" -k >/dev/null 2>&1 || true
    if _font_registry_is_persisted; then
        : > "$_font_marker"
        echo "Configured CJK FontLink for the game prefix."
    else
        echo "ERROR: CJK FontLink was not persisted."
        return 1
    fi
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
