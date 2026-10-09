package com.termux.localgames.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.localgames.R;
import com.termux.localgames.importer.SafGameRootUri;
import com.termux.localgames.importer.SafPermissionManager;

import java.io.IOException;

/** Re-grants a lost SAF read permission for an already-imported game whose rootUri is a
 *  content:// tree URI -- the game's own record (executable/workingDirectory) is untouched, only
 *  the permission is refreshed. New imports (see GameFileManagerActivity) are always file://
 *  paths under an allow-listed root and never lose permission this way, so this only matters for
 *  games imported before that redesign. Immediately launches the system folder picker on create;
 *  there is no UI of its own. */
public final class GameReauthorizeActivity extends AppCompatActivity {

    public static final String EXTRA_TREE_URI = "com.termux.localgames.extra.REAUTHORIZE_TREE_URI";

    private final ActivityResultLauncher<Intent> treePicker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result ->
            handleTreeResult(result.getResultCode(), result.getData()));

    @Nullable private Uri expectedRoot;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String uri = getIntent().getStringExtra(EXTRA_TREE_URI);
        if (uri != null) {
            try {
                expectedRoot = Uri.parse(uri);
            } catch (RuntimeException ignored) {
                expectedRoot = null;
            }
        }
        treePicker.launch(SafPermissionManager.createOpenTreeIntent());
    }

    private void handleTreeResult(int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        Uri selected = data.getData();
        try {
            // The picked folder must be the SAME tree the game was originally imported from --
            // otherwise this would silently re-point an existing game at unrelated files.
            if (expectedRoot != null && !SafGameRootUri.rootDocumentId(expectedRoot)
                    .equals(SafGameRootUri.rootDocumentId(selected))) {
                Toast.makeText(this, R.string.local_game_reauthorize_wrong_folder,
                    Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            SafPermissionManager.persistReadPermission(getContentResolver(), selected,
                data.getFlags());
            Toast.makeText(this, R.string.local_game_reauthorize_succeeded, Toast.LENGTH_SHORT)
                .show();
        } catch (SecurityException | IOException error) {
            Toast.makeText(this, R.string.local_game_import_permission_lost, Toast.LENGTH_LONG)
                .show();
        }
        finish();
    }
}
