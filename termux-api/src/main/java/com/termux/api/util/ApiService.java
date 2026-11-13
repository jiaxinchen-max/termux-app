package com.termux.api.util;

import android.app.Service;
import android.content.Intent;

/** API argument/platform failures must complete the CLI request without crashing the host app. */
public abstract class ApiService extends Service {
    private Throwable initializationFailure;
    @Override public final void onCreate() {
        super.onCreate();
        try { onApiCreate(); } catch (Throwable failure) { initializationFailure = failure; }
    }
    protected void onApiCreate() {}
    @Override public final int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        try {
            if (initializationFailure != null) throw initializationFailure;
            return onApiCommand(intent, flags, startId);
        } catch (Throwable failure) {
            ResultReturner.returnError(this, intent, failure.toString());
            stopSelf(startId);
            return START_NOT_STICKY;
        }
    }
    protected abstract int onApiCommand(Intent intent, int flags, int startId);
}
