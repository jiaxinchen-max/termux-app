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
#   TEMPLATE_CACHE_DIR   only required by resolve_template_prefix_archive() (below), not by
#                        warmup_rootfs_prefix() itself -- callers that build TEMPLATE_PREFIX_ARCHIVE
#                        some other way (setup_rootfs_runtime.sh's BASE_ONLY template-build step
#                        always passes "" instead, to force a real wineboot there) do not need it.
#   RUNTIME_TRANSLATOR   only required by resolve_template_prefix_archive() (below); set by calling
#                        resolve_rootfs_translator() first -- templates are keyed by translator
#                        (hangover / box64 / fex), not by the literal WINE_PACKAGE string, since
#                        that is the actual prefix-compatibility boundary (see that function's own
#                        comment).
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
#                        before this existed -- see setup_rootfs_runtime.sh for how it is built).
#                        Callers warming a real container should call resolve_template_prefix_archive()
#                        (below) first rather than compute this themselves.
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
#
# Dispatch sites in this file and in start_rootfs_game.sh key the "is this the hangover direct-exec
# shape, or the box64/fex wrapped-binary shape" branch off `[ "$RUNTIME_TRANSLATOR" = hangover ]`,
# not a literal GUEST_COMMAND path match -- GUEST_COMMAND for hangover is itself overridable (see
# resolve_rootfs_translator()'s GAMES_CUSTOM_WINE_PATH read, used when WINE_PACKAGE names a custom-
# installed build), so matching its *value* against a hardcoded "/usr/bin/wine" would silently
# break for any custom hangover-family build.

# Resolves the pre-booted prefix template for the *current* RUNTIME_TRANSLATOR (hangover / box64 /
# fex -- see resolve_rootfs_translator() below, which must be called first). Templates are keyed by
# translator, not by the literal WINE_PACKAGE string: two wine packages that resolve to the same
# translator (e.g. two box64-wine-* versions) are the same host CPU architecture running the same
# flavor of wine, and share one compatible prefix -- only a *cross*-translator swap (Hangover's
# native ARM64 wine vs box64's translated x86_64 wine) produces an incompatible prefix layout
# (confirmed: cloning the wrong one produced `wine: could not load kernel32.dll, status c000007b`).
# setup_rootfs_runtime.sh's BASE_ONLY path builds one template per distinct translator found while
# looping over games-runtime.properties's runtimePackages (see that loop for how it dedupes), so
# this lookup can never name a translator the build side does not also know about. Requires
# TEMPLATE_CACHE_DIR and RUNTIME_TRANSLATOR already set; sets TEMPLATE_PREFIX_ARCHIVE to the
# archive path, or "" if no template exists yet for this translator (e.g. it was only just added to
# the recipe and the base has not been rebuilt since -- warmup_rootfs_prefix() below falls back to
# a real wineboot in that case, same as always happened for every package before per-translator
# templates existed).
resolve_template_prefix_archive() {
    # A custom-installed Wine build (see install_custom_rootfs_component.sh) gets its own isolated
    # template cache entry keyed by its literal componentId, rather than sharing the translator
    # bucket's template -- unlike the two built-in presets per translator (verified compatible
    # with each other, see this function's header comment above), a custom build's compatibility
    # with whatever already-booted prefix a preset produced is unknown. The first container to use
    # a given custom id pays a real wineboot once (warmup_rootfs_prefix()'s existing fallback,
    # unchanged, handles TEMPLATE_PREFIX_ARCHIVE="" exactly like a brand-new translator would);
    # every later container reusing the same custom id then clones that id's own template.
    case "$WINE_PACKAGE" in
        custom-wine-*) template_key=$WINE_PACKAGE ;;
        *) template_key=$RUNTIME_TRANSLATOR ;;
    esac
    if [ -f "$TEMPLATE_CACHE_DIR/games-rootfs-base-prefix-$template_key.tar.zst" ]; then
        TEMPLATE_PREFIX_ARCHIVE="$TEMPLATE_CACHE_DIR/games-rootfs-base-prefix-$template_key.tar.zst"
    elif [ -f "$TEMPLATE_CACHE_DIR/games-rootfs-base-prefix-$template_key.tar.gz" ]; then
        TEMPLATE_PREFIX_ARCHIVE="$TEMPLATE_CACHE_DIR/games-rootfs-base-prefix-$template_key.tar.gz"
    else
        TEMPLATE_PREFIX_ARCHIVE=
    fi
}

resolve_rootfs_translator() {
    RUNTIME_TRANSLATOR=${GAMES_RUNTIME_TRANSLATOR:-}
    [ -n "$RUNTIME_TRANSLATOR" ] || case "$WINE_PACKAGE" in
        hangover-*) RUNTIME_TRANSLATOR=hangover ;;
        *) RUNTIME_TRANSLATOR=box64 ;;
    esac
    case "$RUNTIME_TRANSLATOR" in
        hangover)
            case "$WINE_PACKAGE" in hangover-*|custom-wine-*) ;; *) warmup_fail runtime_translator_package_mismatch ;; esac
            # GUEST_COMMAND is the one binary actually exec'd for this translator (see
            # start_rootfs_game.sh's run_rootfs_wine()/game-launch dispatch, both keyed off
            # RUNTIME_TRANSLATOR, not a literal path match -- a custom hangover-family build's own
            # bin/wine replaces the apt-installed one here exactly the same way). GUEST_WINE is
            # unused for this translator (no wrapper binary involved), left blank as before.
            GUEST_COMMAND=${GAMES_CUSTOM_WINE_PATH:-/usr/bin/wine}
            GUEST_WINE=
            GUEST_WINEBOOT=${GAMES_CUSTOM_WINEBOOT_PATH:-/usr/bin/wineboot}
            [ -x "$ROOTFS_CANONICAL$GUEST_COMMAND" ] || warmup_fail rootfs_wine_missing
            [ -x "$ROOTFS_CANONICAL$GUEST_WINEBOOT" ] || warmup_fail rootfs_wineboot_missing
            ;;
        box64)
            case "$WINE_PACKAGE" in box64-wine*|custom-wine-*) ;; *) warmup_fail runtime_translator_package_mismatch ;; esac
            GUEST_COMMAND=/usr/local/bin/box64
            GUEST_WINE=${GAMES_CUSTOM_WINE_PATH:-/opt/box64-wine/bin/wine64}
            # The biarch build's bin/wineboot is a #!/bin/sh wrapper script, not an ELF -- box64
            # cannot translate/exec it directly ("Not an ELF file"). wineboot is instead invoked
            # as wine64's own built-in program dispatch (wine64 recognises "wineboot" as argv[1]
            # the same way it recognises "explorer"), so GUEST_WINEBOOT reuses the wine64 binary
            # itself and GUEST_WINEBOOT_ARG supplies the extra "wineboot" argument word. Verified
            # on-device: `box64 wine64 wineboot -u` completes cleanly with a fresh prefix. A
            # custom build's own bin/wineboot is trusted to be the same kind of wine64-dispatch
            # wrapper (install_custom_rootfs_component.sh only accepts wine-tree payloads shaped
            # like this one), so the same no-separate-binary convention applies to it too.
            GUEST_WINEBOOT=${GAMES_CUSTOM_WINE_PATH:-/opt/box64-wine/bin/wine64}
            GUEST_WINEBOOT_ARG=wineboot
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
    # Box64 build choice is orthogonal to the Wine package/translator choice above (see
    # GameRuntimeOptionsView's "Box64 build" row) -- a custom box64 binary only ever overrides
    # GUEST_COMMAND, never the wine/wineboot paths resolved above. A no-op when
    # GAMES_CUSTOM_BOX64_ID names a build that was installed as a .deb (apt already replaced
    # /usr/local/bin/box64 in place, so GUEST_COMMAND's existing value already points at it); only
    # a raw-binary custom build (installed to its own /opt/custom-box64/<id>/box64 path, never
    # touching /usr/local/bin/box64) actually changes GUEST_COMMAND here.
    if [ -n "${GAMES_CUSTOM_BOX64_ID:-}" ]; then
        case "$GAMES_CUSTOM_BOX64_ID" in
            custom-box64-*) ;;
            *) warmup_fail custom_box64_id_invalid ;;
        esac
        custom_box64_raw_bin="/opt/custom-box64/$GAMES_CUSTOM_BOX64_ID/box64"
        [ -x "$ROOTFS_CANONICAL$custom_box64_raw_bin" ] && GUEST_COMMAND=$custom_box64_raw_bin
    fi
}


# A deliberately minimal proot session for prefix maintenance only -- unlike
# start_rootfs_game.sh's own run_rootfs_command, it binds neither the game root nor a pulse
# server socket, and always runs as plain C.UTF-8 (wineboot does not need a game's selected
# locale; start_rootfs_game.sh applies GUEST_LOCALE separately, later, only to the actual game
# process). $HOME_PATH, not the shared rootfs's own /root, is what's actually writable here --
# see this file's header comment.
#
# WINEDLLOVERRIDES=mscoree,mshtml=d disables Wine's automatic Mono (.NET) and Gecko (HTML) runtime
# installers for the duration of this `wineboot -u`. Neither is bundled in the vanilla Kron4ek
# box64-wine build, so without this a box64-wine wineboot reaches `control.exe appwiz.cpl
# install_mono`, which in this headless, networkless proot either waits forever on a download that
# never comes or blocks on a GUI "install Mono?" dialog nobody can answer -- observed as wineboot
# hanging until the timeout killed it (`boot event wait timed out`, 0% progress), misread earlier
# as "box64 translation is just slow" and wrongly papered over with a longer timeout. With the
# override the identical wineboot completes cleanly in ~2.5 min. Prefix *initialisation* never
# needs either runtime (they matter only when a game actually runs .NET/embedded-HTML code, which
# is out of scope for building a base prefix); a game that needs .NET installs Mono separately.
# NOTE: this function `exec`s proot -- it REPLACES its (sub)shell rather than returning. That is
# deliberate and required for the silence watchdog to work: dash does NOT exec-optimise a
# backgrounded *function call* (verified on-device: `fn(){ sleep; }; fn &` leaves $! pointing at an
# intermediate dash subshell, and killing it orphans the sleep). With `exec` here, a backgrounded
# `run_rootfs_maintenance_command ... &` has $! == proot's real PID, so killing $! terminates proot,
# whose --kill-on-exit then tears down the whole wine tree. Consequence: every caller MUST invoke
# this in a subshell context -- either backgrounded (`... &`, already its own subshell) or wrapped
# in `( ... )` -- NEVER as a bare foreground call, which would exec-replace the whole setup script.
run_rootfs_maintenance_command() {
    exec "$PROOT_BIN" --kill-on-exit --link2symlink --sysvipc -0 \
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
        WINEPREFIX=/mnt/games/prefix WINEDLLOVERRIDES=mscoree,mshtml=d \
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
        # One template prefix is pre-booted per *translator* (see setup_rootfs_runtime.sh's
        # BASE_ONLY path, which loops over every package games-runtime.properties's
        # runtimePackages lists and dedupes by translator), not just Hangover's -- a Kron4ek
        # box64-wine prefix and a Hangover prefix have incompatible ntdll/kernel32 layouts, so
        # cloning the WRONG translator's template produced `wine: could not load kernel32.dll,
        # status c000007b`. Two wine packages of the SAME translator (e.g. two box64-wine-*
        # versions) do share one prefix, though -- that incompatibility is cross-architecture, not
        # cross-wine-version. resolve_template_prefix_archive() (below, and by both other callers
        # of this function) already resolves TEMPLATE_PREFIX_ARCHIVE to the template for *this*
        # container's own RUNTIME_TRANSLATOR specifically, so no further check is needed here: an
        # empty/missing value simply means no template exists yet for this translator (e.g. it was
        # just added to the recipe and the base hasn't been rebuilt since), and this container
        # falls back to a real wineboot, exactly as every package did before per-translator
        # templates existed.
        if [ -n "$TEMPLATE_PREFIX_ARCHIVE" ] && [ -f "$TEMPLATE_PREFIX_ARCHIVE" ]; then
            # Clone a prefix that was already wineboot'd + FontLink'd once, during the shared base
            # build, instead of paying that cost again for every container. Only the
            # marker's recorded RUNTIME_ROOT_PATH is container-specific, so it is rewritten below
            # to this container's real value rather than the template's own placeholder.
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
            # Setup/pre-warm time (no interactive cancel -- see this file's header on CANCEL_PATH).
            # Route wineboot through run_logged_watchdog so its ~2-3 min of output STREAMS LIVE into
            # the setup console dialog (same visibility every other setup step already has via
            # run_logged), instead of being swallowed into LOG_PATH with the terminal appearing
            # frozen. The watchdog also replaces the old arbitrary `timeout 600` ceiling with a
            # silence watchdog: wineboot may run as long as it keeps producing output, and is
            # aborted only on sustained silence (a genuine deadlock), never a healthy-but-slow run.
            # It returns 124 on watchdog fire, so the existing "-ne 124 -> timeout" check is reused.
            #
            # command -v guard: run_logged_watchdog is defined by setup_rootfs_runtime.sh, the only
            # caller that reaches this branch (CANCEL_PATH is always empty there; the launch caller,
            # which lacks the helper, always has a non-empty CANCEL_PATH and takes the else branch).
            # The fallback preserves the old foreground behaviour if ever sourced without the helper.
            #
            # Why both translators are equally slow (~2-3 min): Hangover is native ARM64 for 64-bit
            # code, but its 32-bit WoW64 half runs through WowBox64 (a box64-based dynarec, see the
            # `[BOX64] WowBox64 arm64 (Hangover 11.9)` lines wineboot emits), and the late wine.inf
            # phase spawns many 32-bit rundll32/setupapi helpers that all pay that JIT cost -- so a
            # cold Hangover wineboot is nearly as heavy as a box64-wine one. (box64-wine's earlier
            # `boot event wait timed out` hangs were NOT slowness: wineboot was blocking on the
            # Mono/Gecko auto-installer, now disabled via WINEDLLOVERRIDES in
            # run_rootfs_maintenance_command.)
            if command -v run_logged_watchdog >/dev/null 2>&1; then
                if [ "$RUNTIME_TRANSLATOR" = hangover ]; then
                    if run_logged_watchdog run_rootfs_maintenance_command "$GUEST_WINEBOOT" -u; then
                        PREFIX_EXIT_CODE=0; else PREFIX_EXIT_CODE=$?; fi
                else
                    if run_logged_watchdog run_rootfs_maintenance_command "$GUEST_COMMAND" "$GUEST_WINEBOOT" \
                        ${GUEST_WINEBOOT_ARG:+"$GUEST_WINEBOOT_ARG"} -u; then
                        PREFIX_EXIT_CODE=0; else PREFIX_EXIT_CODE=$?; fi
                fi
            else
                # Fallback if run_logged_watchdog is somehow absent (never in practice -- setup
                # always defines it). run_rootfs_maintenance_command execs proot, so a BARE
                # foreground call would exec-replace this script: wrap each in a ( ) subshell so
                # the exec only replaces the subshell and PREFIX_EXIT_CODE still captures proot's.
                if [ "$RUNTIME_TRANSLATOR" = hangover ]; then
                    ( run_rootfs_maintenance_command "$GUEST_WINEBOOT" -u ) >> "$LOG_PATH" 2>&1
                else
                    ( run_rootfs_maintenance_command "$GUEST_COMMAND" "$GUEST_WINEBOOT" \
                        ${GUEST_WINEBOOT_ARG:+"$GUEST_WINEBOOT_ARG"} -u ) >> "$LOG_PATH" 2>&1
                fi
                PREFIX_EXIT_CODE=$?
            fi
            [ "$PREFIX_EXIT_CODE" -ne 124 ] || warmup_fail rootfs_prefix_initialization_timeout
            [ "$PREFIX_EXIT_CODE" -eq 0 ] || warmup_fail rootfs_prefix_initialization_failed
            mkdir -p "$PREFIX_MARKER_DIR"
            printf '%s\n' "$EXPECTED_PREFIX_MARKER" > "$PREFIX_MARKER"
        else
            if [ "$RUNTIME_TRANSLATOR" = hangover ]; then
                run_rootfs_maintenance_command "$GUEST_WINEBOOT" -u >> "$LOG_PATH" 2>&1 &
            else
                run_rootfs_maintenance_command "$GUEST_COMMAND" "$GUEST_WINEBOOT" -u >> "$LOG_PATH" 2>&1 &
            fi
            PROOT_PID=$!
            PREFIX_LAST_SIZE=$(wc -c < "$LOG_PATH" 2>/dev/null || printf '0')
            PREFIX_LAST_CHANGE=$(date +%s)
            PREFIX_SILENCE=${PREFIX_SILENCE_TIMEOUT:-300}
            # PROOT_PID is proot's real PID (run_rootfs_maintenance_command execs proot). Both kills
            # below are SIGKILL, not the default SIGTERM: proot forwards SIGTERM to the guest init
            # (Windows wineboot ignores it) and does not exit, so --kill-on-exit never fires and the
            # wine tree keeps running (verified on-device). SIGKILL dies at the kernel, and ptrace
            # EXITKILL then reaps every tracee, including the daemonised PPID=1 wineserver.
            set +x
            while kill -0 "$PROOT_PID" 2>/dev/null; do
                [ ! -e "$CANCEL_PATH" ] || { CANCELLED=1; kill -9 "$PROOT_PID" 2>/dev/null || true; break; }
                now=$(date +%s)
                current_size=$(wc -c < "$LOG_PATH" 2>/dev/null || printf '0')
                if [ "$current_size" != "$PREFIX_LAST_SIZE" ]; then
                    PREFIX_LAST_SIZE=$current_size
                    PREFIX_LAST_CHANGE=$now
                elif [ $((now - PREFIX_LAST_CHANGE)) -ge "$PREFIX_SILENCE" ]; then
                    kill -9 "$PROOT_PID" 2>/dev/null || true
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
