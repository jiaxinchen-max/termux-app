package com.termux.app.localgames;

import android.content.Context;
import android.content.Intent;

import com.termux.x11.utils.KeyInterceptor;

/** The confirmation belongs to the UI; cleanup survives termination of the main process. */
final class TermuxAppExit {
    private TermuxAppExit() { }

    static void request(Context context) {
        KeyInterceptor.shutdown(false);
        context.startActivity(new Intent(context, TermuxExitActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
}
