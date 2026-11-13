package com.termux.api.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.JsonWriter;

import com.termux.api.util.ResultReturner;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.util.ArrayList;

public class TermuxApiPermissionActivity extends Activity {

    private static final String LOG_TAG = "TermuxApiPermissionActivity";

    /**
     * Intent extra containing the permissions to request.
     */
    public static final String PERMISSIONS_EXTRA = TermuxConstants.TERMUX_API_PACKAGE_NAME + ".permission_extra";

    /**
     * Check for and request permissions if necessary.
     *
     * @return if all permissions were already granted
     */
    public static boolean checkAndRequestPermissions(Context context, Intent intent, String... permissions) {
        final ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (!PermissionUtils.checkPermission(context, permission)) {
                permissionsToRequest.add(permission);
            }
        }

        if (permissionsToRequest.isEmpty()) {
            return true;
        } else {
            ResultReturner.returnData(context, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    String errorMessage = "Please grant the following permission"
                            + (permissionsToRequest.size() > 1 ? "s" : "")
                            + " to use this command: "
                            + TextUtils.join(" ,", permissionsToRequest);
                    out.beginObject().name("error").value(errorMessage).endObject();
                }
            });

            Intent startIntent = new Intent(context, TermuxApiPermissionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putStringArrayListExtra(TermuxApiPermissionActivity.PERMISSIONS_EXTRA, permissionsToRequest);
            ResultReturner.copyIntentExtras(intent, startIntent);
            context.startActivity(startIntent);
            return false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Logger.logDebug(LOG_TAG, "onNewIntent");

        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if (state != null) return;
        ArrayList<String> values = getIntent().getStringArrayListExtra(PERMISSIONS_EXTRA);
        if (values == null || values.isEmpty() || android.os.Build.VERSION.SDK_INT < 23) {
            finish();
            return;
        }
        requestPermissions(values.toArray(new String[0]), 0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        finish(); // The original CLI already received a permission error; retry after granting.
    }
}
