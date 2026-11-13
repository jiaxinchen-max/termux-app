package com.termux.api.util;

import android.app.IntentService;
import android.content.Intent;

public abstract class ApiIntentService extends IntentService {
    protected ApiIntentService(String name) { super(name); }
    @Override protected final void onHandleIntent(Intent intent) {
        if (intent == null) return;
        try { onApiIntent(intent); }
        catch (Throwable failure) { ResultReturner.returnError(this, intent, failure.toString()); }
    }
    protected abstract void onApiIntent(Intent intent);
}
