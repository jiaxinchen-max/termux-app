package com.termux.app.localgames;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import java.util.concurrent.ConcurrentHashMap;

/** Process-local bridge exposing only Games provisioning sessions to the Games module. */
public final class TermuxGamesProvisionTerminalRegistry {
    private static final String PREFIX = "games-runtime-provision-";
    private static final ConcurrentHashMap<String, TerminalSession> SESSIONS =
        new ConcurrentHashMap<>();

    private TermuxGamesProvisionTerminalRegistry() {}

    public static boolean isProvisionSession(String shellName) {
        return shellName != null && shellName.startsWith(PREFIX);
    }

    public static void register(String shellName, TerminalSession session) {
        if (!isProvisionSession(shellName) || session == null) return;
        SESSIONS.put(shellName.substring(PREFIX.length()), session);
    }

    @Nullable public static TerminalSession find(String taskId) {
        return taskId == null ? null : SESSIONS.get(taskId);
    }

    public static void remove(String shellName) {
        if (isProvisionSession(shellName)) SESSIONS.remove(shellName.substring(PREFIX.length()));
    }
}
