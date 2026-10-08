# Shared by setup_rootfs_runtime.sh and backup_restore_rootfs.sh. Not an executable script
# itself -- both callers `. ` (source) this file after validating and mkdir-ing their own
# $LOG_PATH, so every long-running command either script runs follows the same "visible in the
# live setup console, aborted on sustained silence rather than a fixed wall-clock ceiling"
# convention, instead of each growing its own copy of this logic.
#
# Required before sourcing: LOG_PATH (already created, appendable).

progress() {
    printf '%s\n' "$1" | tee -a "$LOG_PATH"
}

# Keep the durable log for task reconciliation, while forwarding command output to
# the PTY when the caller was started from the Games component screen.
run_logged() {
    if [ -t 1 ]; then
        status_path="$LOG_PATH.command-status.$$"
        rm -f "$status_path"
        (
            set +e
            "$@"
            command_status=$?
            printf '%s\n' "$command_status" > "$status_path"
        ) 2>&1 | tee -a "$LOG_PATH"
        command_status=$(cat "$status_path" 2>/dev/null || printf '1')
        rm -f "$status_path"
        return "$command_status"
    fi
    "$@" >> "$LOG_PATH" 2>&1
}

# Like run_logged, but for a long, possibly-stalling step that must stay visible in the live
# console without an arbitrary wall-clock ceiling. Two differences from run_logged:
#  1. Visibility without a tee pipe: the command writes to "$LOG_PATH" and a background `tail -f`
#     forwards new lines to the PTY. Because the command is backgrounded directly (no pipe), its
#     own "$!" is the real command PID -- for callers wrapping a proot invocation that execs proot
#     (see rootfs_prefix_warmup.sh's run_rootfs_maintenance_command), that PID is proot itself, and
#     proot runs with --kill-on-exit, so killing that one PID tears down the whole proot->guest
#     process tree. (A `... | tee &` pipeline's $! is tee, not the command, so killing it would
#     leave the command running -- hence the tail-forward instead.)
#  2. Silence watchdog instead of a fixed timeout: the command may run as long as "$LOG_PATH"
#     keeps growing, and is aborted only after PREFIX_SILENCE_TIMEOUT seconds of *zero* growth -- a
#     genuine deadlock (e.g. the old Mono-installer hang in wineboot), never a healthy-but-slow
#     run, which streams output continuously. Returns the command's real exit code, or 124
#     (mimicking `timeout`) on watchdog fire, so a caller's existing "-ne 124 -> timeout" check
#     keeps working unchanged.
run_logged_watchdog() {
    watchdog_silence=${PREFIX_SILENCE_TIMEOUT:-300}
    watchdog_tail_pid=
    if [ -t 1 ]; then
        tail -n 0 -f "$LOG_PATH" 2>/dev/null &
        watchdog_tail_pid=$!
    fi
    "$@" >> "$LOG_PATH" 2>&1 &
    watchdog_cmd_pid=$!
    watchdog_last_size=$(wc -c < "$LOG_PATH" 2>/dev/null || printf '0')
    watchdog_last_change=$(date +%s)
    watchdog_fired=0
    # Quiet set -x around the poll loop itself -- a per-5s trace of the watchdog machinery is noise
    # in the live console, not progress (same reasoning as rootfs_prefix_warmup.sh's poll loop).
    set +x
    while kill -0 "$watchdog_cmd_pid" 2>/dev/null; do
        sleep 5
        watchdog_now=$(date +%s)
        watchdog_size=$(wc -c < "$LOG_PATH" 2>/dev/null || printf '0')
        if [ "$watchdog_size" != "$watchdog_last_size" ]; then
            watchdog_last_size=$watchdog_size
            watchdog_last_change=$watchdog_now
        elif [ $((watchdog_now - watchdog_last_change)) -ge "$watchdog_silence" ]; then
            # SIGKILL, not SIGTERM: a ptrace tracer like proot forwards SIGTERM to the tracee
            # (which may itself ignore it, e.g. Windows wineboot) and does NOT itself exit, so
            # --kill-on-exit never fires -- verified on-device that plain `kill` leaves the whole
            # process tree running. SIGKILL can't be forwarded/caught, so the tracer dies at the
            # kernel and ptrace EXITKILL reaps every tracee (including any daemonised, PPID=1
            # children). watchdog_cmd_pid is the real command PID, never a wrapping shell -- see
            # point 1 above.
            kill -9 "$watchdog_cmd_pid" 2>/dev/null || true
            watchdog_fired=1
            break
        fi
    done
    set -x
    watchdog_status=0
    wait "$watchdog_cmd_pid" 2>/dev/null || watchdog_status=$?
    [ -z "$watchdog_tail_pid" ] || { kill "$watchdog_tail_pid" 2>/dev/null || true; wait "$watchdog_tail_pid" 2>/dev/null || true; }
    [ "$watchdog_fired" = 0 ] || return 124
    return "$watchdog_status"
}
