# Shared by setup_rootfs_runtime.sh (import/config-time warmup, so the slow one-time cost below
# happens while the user is configuring the game, not when they hit Launch) and
# start_rootfs_game.sh (kept as a launch-time fallback/safety-net -- if warmup never ran, or the
# container predates this file, launch still works, it is just slow the first time, exactly as
# before). Not an executable script itself -- both callers `. ` (source) this file and then call
# its functions, so the marker-gated logic below can never drift out of sync between the two
# places that need to agree on it.
#
# Every function here reads already-set globals instead of taking them as parameters, matching
# this codebase's existing shell convention (see e.g. start_rootfs_game.sh's own
# run_rootfs_command). Required before calling anything below:
#   ROOTFS_CANONICAL    realpath of the one shared RootFS every container mounts (see
#                        GameStoragePaths.getSharedRootfsDirectory()) -- never a per-container copy
#   PREFIX_PATH          host path of this container's Wine prefix (bind-mounted inside the
#                        container at /mnt/games/prefix)
#   HOME_PATH            host path of this container's writable $HOME (bind-mounted at /root) --
#                        the shared rootfs itself is conceptually read-only, so anything a tool
#                        writes under $HOME needs somewhere real, per-container, to land
#   RUNTIME_ROOT_PATH    the exact string embedded into the "already warmed" marker files --
#                        both callers must compute the identical value for a given container, or
#                        warming up in one place does not skip anything in the other
#   WINE_PACKAGE         e.g. "hangover-11.9" or "box64-wine-9.3"
#   PROOT_BIN            path to the proot binary
#   TERMUX_FILES_DIR     Termux's `files` root
#   LOG_PATH             appended to for diagnostics
#   CANCEL_PATH          a file appearing here aborts an in-progress wineboot; pass "" when there
#                        is no interactive cancel concept (always false for -e "" , so the
#                        cancel check is simply never true)
#   TEMPLATE_PREFIX_ARCHIVE  a pre-booted Wine prefix archive (wineboot -u + CJK FontLink already
#                        applied, built once alongside the RootFS base) to extract into
#                        PREFIX_PATH instead of actually running wineboot here; pass "" when none
#                        is available yet (falls back to the real wineboot run below, same as
#                        before this existed -- see setup_rootfs_runtime.sh for how it is built)
#   MAINTENANCE_DISPLAY  optional X11 display passed to the proot session (default ":0").
#                        start_rootfs_game.sh leaves this unset -- an X session is already live
#                        on :0 by the time it reaches this code. setup_rootfs_runtime.sh has no
#                        live game session and must not collide with one that might be running
#                        concurrently on :0 for a different game, so it starts its own display on
#                        a dedicated number and is responsible for starting/stopping it itself
#                        around the call (see setup_rootfs_runtime.sh).
# Also required: a `warmup_fail "<code>"` function defined by the caller before sourcing this
# file (each caller keeps its own existing failure path -- terminal_failure vs fail).
# Uses, and leaves set on completion for the caller's own later use: PROOT_PID, CANCELLED,
# GUEST_COMMAND, GUEST_WINE, GUEST_WINEBOOT, GUEST_LOCALE, RUNTIME_TRANSLATOR.

resolve_rootfs_translator() {
    RUNTIME_TRANSLATOR=${GAMES_RUNTIME_TRANSLATOR:-}
    [ -n "$RUNTIME_TRANSLATOR" ] || case "$WINE_PACKAGE" in
        hangover-*) RUNTIME_TRANSLATOR=hangover ;;
        *) RUNTIME_TRANSLATOR=box64 ;;
    esac
    case "$RUNTIME_TRANSLATOR" in
        hangover)
            case "$WINE_PACKAGE" in hangover-*) ;; *) warmup_fail runtime_translator_package_mismatch ;; esac
            GUEST_COMMAND=/usr/bin/wine
            GUEST_WINE=
            GUEST_WINEBOOT=/usr/bin/wineboot
            [ -x "$ROOTFS_CANONICAL$GUEST_COMMAND" ] || warmup_fail rootfs_wine_missing
            [ -x "$ROOTFS_CANONICAL$GUEST_WINEBOOT" ] || warmup_fail rootfs_wineboot_missing
            ;;
        box64)
            case "$WINE_PACKAGE" in box64-wine*) ;; *) warmup_fail runtime_translator_package_mismatch ;; esac
            GUEST_COMMAND=/usr/local/bin/box64
            GUEST_WINE=/opt/box64-wine/bin/wine
            GUEST_WINEBOOT=/opt/box64-wine/bin/wineboot
            [ -x "$ROOTFS_CANONICAL$GUEST_COMMAND" ] || warmup_fail rootfs_box64_missing
            [ -x "$ROOTFS_CANONICAL$GUEST_WINE" ] || warmup_fail rootfs_wine_missing
            [ -x "$ROOTFS_CANONICAL$GUEST_WINEBOOT" ] || warmup_fail rootfs_wineboot_missing
            ;;
        fex)
            case "$WINE_PACKAGE" in box64-wine*) ;; *) warmup_fail runtime_translator_package_mismatch ;; esac
            GUEST_COMMAND=/usr/bin/FEXInterpreter
            GUEST_WINE=/opt/box64-wine/bin/wine
            GUEST_WINEBOOT=/opt/box64-wine/bin/wineboot
            [ -x "$ROOTFS_CANONICAL$GUEST_COMMAND" ] || warmup_fail rootfs_fex_missing
            [ -x "$ROOTFS_CANONICAL$GUEST_WINE" ] || warmup_fail rootfs_wine_missing
            [ -x "$ROOTFS_CANONICAL$GUEST_WINEBOOT" ] || warmup_fail rootfs_wineboot_missing
            ;;
        *) warmup_fail runtime_translator_unsupported ;;
    esac
}

# A deliberately minimal proot session for prefix maintenance only -- unlike
# start_rootfs_game.sh's own run_rootfs_command, it binds neither the game root nor a pulse
# server socket, and always runs as plain C.UTF-8 (wineboot does not need a game's selected
# locale; start_rootfs_game.sh applies GUEST_LOCALE separately, later, only to the actual game
# process). $HOME_PATH, not the shared rootfs's own /root, is what's actually writable here --
# see this file's header comment.
run_rootfs_maintenance_command() {
    "$PROOT_BIN" --kill-on-exit --link2symlink --sysvipc -0 \
        -r "$ROOTFS_CANONICAL" \
        -b /dev -b /proc -b /sys \
        -b "$TERMUX_FILES_DIR/usr/tmp:/tmp" \
        -b "$HOME_PATH:/root" \
        -b "$PREFIX_PATH:/mnt/games/prefix" \
        -w /root \
        /usr/bin/env -u PULSE_SERVER -u FONTCONFIG_PATH -u FONTCONFIG_FILE -u FONTCONFIG_SYSROOT \
        HOME=/root USER=root LOGNAME=root LANG=C.UTF-8 LC_ALL=C.UTF-8 \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        FONTCONFIG_PATH=/etc/fonts FONTCONFIG_FILE=/etc/fonts/fonts.conf \
        XDG_DATA_DIRS=/usr/local/share:/usr/share DISPLAY="${MAINTENANCE_DISPLAY:-:0}" \
        WINEPREFIX=/mnt/games/prefix \
        "$@"
}

# Warms this container's Wine prefix: `wineboot -u` (or a clone of a pre-booted template prefix,
# see TEMPLATE_PREFIX_ARCHIVE above) plus CJK FontLink registry injection. Idempotent -- a
# container whose markers already match is a handful of stat() calls, nothing more. Locale
# generation and the CJK font package itself are no longer done here at all: both are baked into
# the shared RootFS once, at base-image build time, by runtime-rootfs/setup-container.sh -- doing
# either per-container used to be merely redundant (the base already had them); now that the
# RootFS is one shared, conceptually read-only image instead of a per-container copy, it would
# also be a correctness bug (concurrent per-container setups writing into the same shared tree).
warmup_rootfs_prefix() {
    GUEST_LOCALE=zh_CN.UTF-8
    PREFIX_MARKER_DIR="$PREFIX_PATH/.games-runtime"
    GUEST_CJK_FONT=/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc

    PREFIX_MARKER="$PREFIX_MARKER_DIR/runtime"
    EXPECTED_PREFIX_MARKER="$RUNTIME_ROOT_PATH|$WINE_PACKAGE"
    CURRENT_PREFIX_MARKER=
    [ ! -f "$PREFIX_MARKER" ] || CURRENT_PREFIX_MARKER=$(sed -n '1p' "$PREFIX_MARKER" 2>/dev/null)
    if [ "$CURRENT_PREFIX_MARKER" != "$EXPECTED_PREFIX_MARKER" ]; then
        if [ -n "$TEMPLATE_PREFIX_ARCHIVE" ] && [ -f "$TEMPLATE_PREFIX_ARCHIVE" ]; then
            # Clone a prefix that was already wineboot'd + FontLink'd once, during the shared
            # base build, instead of paying that cost again for every container. The clone is
            # universal (no container-specific state is baked into a fresh prefix -- see
            # setup_rootfs_runtime.sh's template-prefix build step for why this is safe); only
            # the marker's recorded RUNTIME_ROOT_PATH is container-specific, so it is rewritten
            # below to this container's real value rather than the template's own placeholder.
            printf '%s\n' 'Cloning the pre-booted Wine prefix template.' >> "$LOG_PATH"
            case "$TEMPLATE_PREFIX_ARCHIVE" in
                *.tar.zst) TEMPLATE_PREFIX_COMPRESSOR=zstd ;;
                *) TEMPLATE_PREFIX_COMPRESSOR=gzip ;;
            esac
            rm -rf "$PREFIX_PATH"
            mkdir -p "$PREFIX_PATH"
            if ! tar -C "$PREFIX_PATH" --use-compress-program "$TEMPLATE_PREFIX_COMPRESSOR" \
                --numeric-owner -xpf "$TEMPLATE_PREFIX_ARCHIVE" >> "$LOG_PATH" 2>&1; then
                warmup_fail rootfs_prefix_template_extract_failed
            fi
            mkdir -p "$PREFIX_MARKER_DIR"
            printf '%s\n' "$EXPECTED_PREFIX_MARKER" > "$PREFIX_MARKER"
            printf '%s\n' "$RUNTIME_ROOT_PATH|$WINE_PACKAGE|Noto Sans CJK SC|v4" \
                > "$PREFIX_MARKER_DIR/cjk-fonts"
        elif [ -z "$CANCEL_PATH" ]; then
            # No interactive cancel concept here (setup/pre-warm time -- see this file's header
            # comment on CANCEL_PATH). Block directly on one foreground command instead of
            # backgrounding it and polling its PID once a second: `timeout`, run inside the
            # guest, enforces the same 180s ceiling without a manual loop. The old polling loop's
            # own `sleep 1`/`kill -0`/`date` statements were themselves traced by this script's
            # `set -x` into the live setup console, reading as "nothing is happening" even while
            # wineboot was actively running (its own output goes to LOG_PATH, not this trace).
            if [ "$GUEST_COMMAND" = /usr/bin/wine ]; then
                run_rootfs_maintenance_command timeout 180 "$GUEST_WINEBOOT" -u >> "$LOG_PATH" 2>&1
            else
                run_rootfs_maintenance_command timeout 180 "$GUEST_COMMAND" "$GUEST_WINEBOOT" -u \
                    >> "$LOG_PATH" 2>&1
            fi
            PREFIX_EXIT_CODE=$?
            [ "$PREFIX_EXIT_CODE" -ne 124 ] || warmup_fail rootfs_prefix_initialization_timeout
            [ "$PREFIX_EXIT_CODE" -eq 0 ] || warmup_fail rootfs_prefix_initialization_failed
            mkdir -p "$PREFIX_MARKER_DIR"
            printf '%s\n' "$EXPECTED_PREFIX_MARKER" > "$PREFIX_MARKER"
        else
            if [ "$GUEST_COMMAND" = /usr/bin/wine ]; then
                run_rootfs_maintenance_command "$GUEST_WINEBOOT" -u >> "$LOG_PATH" 2>&1 &
            else
                run_rootfs_maintenance_command "$GUEST_COMMAND" "$GUEST_WINEBOOT" -u >> "$LOG_PATH" 2>&1 &
            fi
            PROOT_PID=$!
            PREFIX_STARTED_AT=$(date +%s)
            # The interactive-cancel fallback still needs to poll (watching an arbitrary file
            # `timeout` cannot watch for us) -- but quiet this script's own `set -x` tracing
            # around the loop itself, same reasoning as above: a per-second trace of the polling
            # machinery is noise, not progress.
            set +x
            while kill -0 "$PROOT_PID" 2>/dev/null; do
                [ ! -e "$CANCEL_PATH" ] || { CANCELLED=1; kill "$PROOT_PID" 2>/dev/null || true; break; }
                now=$(date +%s)
                if [ $((now - PREFIX_STARTED_AT)) -ge 180 ]; then
                    kill "$PROOT_PID" 2>/dev/null || true
                    wait "$PROOT_PID" 2>/dev/null || true
                    PROOT_PID=
                    set -x
                    warmup_fail rootfs_prefix_initialization_timeout
                fi
                sleep 1
            done
            set -x
            wait "$PROOT_PID"
            PREFIX_EXIT_CODE=$?
            PROOT_PID=
            [ "${CANCELLED:-0}" = 1 ] || [ "$PREFIX_EXIT_CODE" -eq 0 ] || \
                warmup_fail rootfs_prefix_initialization_failed
            if [ "${CANCELLED:-0}" != 1 ]; then
                mkdir -p "$PREFIX_MARKER_DIR"
                printf '%s\n' "$EXPECTED_PREFIX_MARKER" > "$PREFIX_MARKER"
            fi
        fi
    fi

    # Wine registers Linux fonts, but Hangover's `wine reg add` can report success without
    # persisting the value. Use the same .reg import path as Termux-box and also keep the CJK
    # TTC files in the Windows font directory for applications that enumerate only
    # C:\\Windows\\Fonts. All host-side file operations below -- no proot session needed.
    FONT_MARKER="$PREFIX_MARKER_DIR/cjk-fonts"
    EXPECTED_FONT_MARKER="$RUNTIME_ROOT_PATH|$WINE_PACKAGE|Noto Sans CJK SC|v4"
    FONT_LINK_REGISTRY_VALUE='"Tahoma"=hex(7):4e,00,6f,00,74,00,6f,00,53,00,61,00,6e,00,73,00,43,00,4a,00,4b,00,2d,00,52,00,65,00,67,00,75,00,6c,00,61,00,72,00,2e,00,74,00,74,00,63,00,2c,00,4e,00,6f,00,74,00,6f,00,20,00,53,00,61,00,6e,00,73,00,20,00,43,00,4a,00,4b,00,20,00,53,00,43,00,00,00,00,00'
    CURRENT_FONT_MARKER=
    [ ! -f "$FONT_MARKER" ] || CURRENT_FONT_MARKER=$(sed -n '1p' "$FONT_MARKER" 2>/dev/null)
    if [ -f "$ROOTFS_CANONICAL$GUEST_CJK_FONT" ] && \
        [ "$CURRENT_FONT_MARKER" != "$EXPECTED_FONT_MARKER" ]; then
        FONT_DIRECTORY="$PREFIX_PATH/drive_c/windows/Fonts"
        FONT_REGISTRY_FILE="$PREFIX_MARKER_DIR/cjk-fonts.reg"
        mkdir -p "$FONT_DIRECTORY" "$PREFIX_MARKER_DIR"
        cp "$ROOTFS_CANONICAL/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc" \
            "$FONT_DIRECTORY/NotoSansCJK-Regular.ttc" && \
        cp "$ROOTFS_CANONICAL/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc" \
            "$FONT_DIRECTORY/NotoSansCJK-Bold.ttc" || \
            printf '%s\n' 'Failed to copy Noto CJK fonts into the Wine prefix.' >> "$LOG_PATH"
        {
            printf '%s\n' 'REGEDIT4'
            printf '\n'
            printf '%s\n' '[HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\FontSubstitutes]'
            printf '%s\n' '"MS Shell Dlg"="Tahoma"'
            printf '%s\n' '"MS Shell Dlg 2"="Tahoma"'
            printf '%s\n' '"Microsoft Sans Serif"="Noto Sans CJK SC"'
            printf '%s\n' '"SimSun"="Noto Sans CJK SC"'
            printf '%s\n' '"NSimSun"="Noto Sans CJK SC"'
            printf '%s\n' '"Microsoft YaHei"="Noto Sans CJK SC"'
            printf '\n'
            printf '%s\n' '[HKEY_LOCAL_MACHINE\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\FontSubstitutes]'
            printf '%s\n' '"MS Shell Dlg"="Tahoma"'
            printf '%s\n' '"MS Shell Dlg 2"="Tahoma"'
            printf '%s\n' '"Microsoft Sans Serif"="Noto Sans CJK SC"'
            printf '%s\n' '"SimSun"="Noto Sans CJK SC"'
            printf '%s\n' '"NSimSun"="Noto Sans CJK SC"'
            printf '%s\n' '"Microsoft YaHei"="Noto Sans CJK SC"'
            printf '\n'
            printf '%s\n' '[HKEY_LOCAL_MACHINE\Software\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink]'
            printf '%s\n' "$FONT_LINK_REGISTRY_VALUE"
            printf '%s\n' "\"Tahoma Bold\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Shell Dlg\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Shell Dlg 2\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Microsoft Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Lucida Sans Unicode\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Arial\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Arial Black\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '\n'
            printf '%s\n' '[HKEY_LOCAL_MACHINE\Software\Wow6432Node\Microsoft\Windows NT\CurrentVersion\FontLink\SystemLink]'
            printf '%s\n' "$FONT_LINK_REGISTRY_VALUE"
            printf '%s\n' "\"Tahoma Bold\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Shell Dlg\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Shell Dlg 2\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Microsoft Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Lucida Sans Unicode\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Arial\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Arial Black\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        } > "$FONT_REGISTRY_FILE"
        # Hangover's regedit can exit successfully without flushing HKEY_LOCAL_MACHINE when the
        # prefix is bind-mounted through PRoot. system.reg is Wine's durable registry hive and is
        # not in use until the game Wine server starts below.
        {
            printf '\n'
            printf '%s\n' '[Software\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes]'
            printf '%s\n' '"MS Shell Dlg"="Tahoma"'
            printf '%s\n' '"MS Shell Dlg 2"="Tahoma"'
            printf '%s\n' '"Microsoft Sans Serif"="Noto Sans CJK SC"'
            printf '%s\n' '"SimSun"="Noto Sans CJK SC"'
            printf '%s\n' '"NSimSun"="Noto Sans CJK SC"'
            printf '%s\n' '"Microsoft YaHei"="Noto Sans CJK SC"'
            printf '\n'
            printf '%s\n' '[Software\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink]'
            printf '%s\n' "$FONT_LINK_REGISTRY_VALUE"
            printf '%s\n' "\"Tahoma Bold\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Shell Dlg\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Shell Dlg 2\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Microsoft Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Lucida Sans Unicode\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Arial\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Arial Black\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '\n'
            printf '%s\n' '[Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion\\FontSubstitutes]'
            printf '%s\n' '"MS Shell Dlg"="Tahoma"'
            printf '%s\n' '"MS Shell Dlg 2"="Tahoma"'
            printf '%s\n' '"Microsoft Sans Serif"="Noto Sans CJK SC"'
            printf '%s\n' '"SimSun"="Noto Sans CJK SC"'
            printf '%s\n' '"NSimSun"="Noto Sans CJK SC"'
            printf '%s\n' '"Microsoft YaHei"="Noto Sans CJK SC"'
            printf '\n'
            printf '%s\n' '[Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion\\FontLink\\SystemLink]'
            printf '%s\n' "$FONT_LINK_REGISTRY_VALUE"
            printf '%s\n' "\"Tahoma Bold\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Shell Dlg\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Shell Dlg 2\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Microsoft Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"MS Sans Serif\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Lucida Sans Unicode\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Arial\"=${FONT_LINK_REGISTRY_VALUE#*=}"
            printf '%s\n' "\"Arial Black\"=${FONT_LINK_REGISTRY_VALUE#*=}"
        } >> "$PREFIX_PATH/system.reg"
        if grep -F "$FONT_LINK_REGISTRY_VALUE" "$PREFIX_PATH/system.reg" \
            >/dev/null 2>&1; then
            mkdir -p "$PREFIX_MARKER_DIR"
            printf '%s\n' "$EXPECTED_FONT_MARKER" > "$FONT_MARKER"
        else
            printf '%s\n' 'Wine CJK FontLink registry setup failed.' >> "$LOG_PATH"
        fi
    fi
}
