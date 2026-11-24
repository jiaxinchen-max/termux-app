package com.termux.ui.ide;

import java.io.File;

/** Checks /proc identity before signalling a negative (process-group) PID. */
public final class IdeProcessIdentity {
    public static final String RUNNER_SCRIPT = "set -eu\nprintf '%s' \"$$\" > \"$IDE_JOB_DIR/pid\"\nbash \"$IDE_JOB_DIR/toolchain.sh\" \"$@\"\n";
    public static boolean isTaskLeader(int pid, String command, String stat, File runner) {
        if (pid <= 1) return false;
        try {
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" +");
            boolean scriptArgument = java.util.Arrays.asList(command.split("\u0000")).contains(runner.getPath());
            return scriptArgument && Integer.parseInt(stat.substring(0, stat.indexOf(' '))) == pid
                && Integer.parseInt(fields[2]) == pid && Integer.parseInt(fields[3]) == pid;
        } catch (RuntimeException ignored) { return false; }
    }
    private IdeProcessIdentity() { }
}
