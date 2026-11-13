package com.termux.app.localgames;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import java.util.concurrent.ConcurrentHashMap;

/** Process-local bridge exposing only Games setup sessions to the Games module. */
public final class TermuxGamesSetupTerminalRegistry {
    private static final String PREFIX = "games-runtime-setup-";
    private static final ConcurrentHashMap<String, TerminalSession> SESSIONS =
        new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Long> PENDING = new ConcurrentHashMap<>();
    private static final long SUBMISSION_TIMEOUT_MS = 60000;

    private TermuxGamesSetupTerminalRegistry() {}

    public static void submitting(String taskId) {
        PENDING.put(taskId, android.os.SystemClock.elapsedRealtime());
    }

    public static void submissionFailed(String taskId) { PENDING.remove(taskId); }

    public static boolean isPending(String taskId) {
        Long submitted = PENDING.get(taskId);
        if (submitted == null) return false;
        if (android.os.SystemClock.elapsedRealtime() - submitted < SUBMISSION_TIMEOUT_MS) return true;
        PENDING.remove(taskId, submitted);
        return false;
    }

    public static boolean isSetupSession(String shellName) {
        return shellName != null && shellName.startsWith(PREFIX);
    }

    public static void register(String shellName, TerminalSession session) {
        if (!isSetupSession(shellName) || session == null) return;
        String taskId = shellName.substring(PREFIX.length());
        SESSIONS.put(taskId, session);
        PENDING.remove(taskId);
    }

    @Nullable public static TerminalSession find(String taskId) {
        return taskId == null ? null : SESSIONS.get(taskId);
    }

    public static void remove(String shellName) {
        if (isSetupSession(shellName)) SESSIONS.remove(shellName.substring(PREFIX.length()));
    }
}
