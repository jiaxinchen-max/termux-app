package com.termux.api;

import android.app.Application;
import com.termux.api.util.ResultReturner;

/** Module lifecycle; the host retains its Application, crash handler and logging configuration. */
public final class TermuxAPIApplication {
    private TermuxAPIApplication() {}
    public static void initialize(Application app) {
        android.app.ActivityManager manager = (android.app.ActivityManager) app.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        boolean main = false;
        if (processes != null) for (android.app.ActivityManager.RunningAppProcessInfo process : processes)
            if (process.pid == android.os.Process.myPid()) main = app.getPackageName().equals(process.processName);
        if (!main) return;
        ResultReturner.setContext(app);
        SocketListener.createSocketListener(app);
    }
}
