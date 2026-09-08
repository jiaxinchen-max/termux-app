package com.termux.localgames.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.documentfile.provider.DocumentFile;

import com.termux.localgames.R;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.databinding.ActivityLocalGameFileManagerBinding;
import com.termux.localgames.importer.SafPermissionManager;
import com.termux.localgames.importer.SafGameRootUri;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/** Browser for private game files and persisted SAF roots. It never mutates file content. */
public final class GameFileManagerActivity extends AppCompatActivity {

    private final Deque<DocumentFile> safHistory = new ArrayDeque<>();
    private final ActivityResultLauncher<Intent> directoryPicker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null ||
                result.getData().getData() == null) return;
            try {
                SafPermissionManager.persistReadPermission(getContentResolver(),
                    result.getData().getData(), result.getData().getFlags());
                showHome();
            } catch (SecurityException error) {
                Toast.makeText(this, R.string.local_game_import_permission_lost,
                    Toast.LENGTH_LONG).show();
            }
        });

    private ActivityLocalGameFileManagerBinding binding;
    private File filesRoot;
    private File gameFilesRoot;
    private File localCurrent;
    private DocumentFile safCurrent;
    private Uri safTreeUri;
    private boolean browsingAuthorizedFolders;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLocalGameFileManagerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        filesRoot = getFilesDir();
        gameFilesRoot = new GameStoragePaths(filesRoot).getImportedGamesDirectory();
        if (!gameFilesRoot.exists() && !gameFilesRoot.mkdirs()) {
            Toast.makeText(this, R.string.local_game_file_manager_empty, Toast.LENGTH_SHORT).show();
        }
        binding.localGameFileManagerToolbar.setNavigationOnClickListener(view -> navigateUp());
        GamesHelpDialog.attach(binding.localGameFileManagerToolbar,
            R.string.local_game_file_manager_title, R.string.local_game_file_manager_help);
        showHome();
    }

    @Override
    public void onBackPressed() {
        if (isHome()) super.onBackPressed();
        else navigateUp();
    }

    private void navigateUp() {
        if (localCurrent != null) {
            if (localCurrent.equals(filesRoot)) {
                showHome();
            } else {
                File parent = localCurrent.getParentFile();
                if (parent == null || !isWithin(filesRoot, parent)) showHome();
                else showLocal(parent);
            }
            return;
        }
        if (safCurrent != null) {
            if (safHistory.isEmpty()) showHome();
            else showSaf(safHistory.removeLast(), safTreeUri, false);
            return;
        }
        if (browsingAuthorizedFolders) {
            showHome();
            return;
        }
        finish();
    }

    private boolean isHome() {
        return localCurrent == null && safCurrent == null && !browsingAuthorizedFolders;
    }

    private void showHome() {
        localCurrent = null;
        safCurrent = null;
        safTreeUri = null;
        safHistory.clear();
        browsingAuthorizedFolders = false;
        binding.localGameFileManagerLocation.setText(
            R.string.local_game_file_manager_internal_storage);
        clearRows();
        addTile(getString(R.string.local_game_file_manager_game_files), true,
            view -> showLocal(gameFilesRoot), null);
        addTile(getString(R.string.local_game_file_manager_private_files), true,
            view -> showLocal(filesRoot), null);
        addTile(getString(R.string.local_game_file_manager_authorized), true,
            view -> showAuthorizedFolders(), null);
    }

    private void showAuthorizedFolders() {
        localCurrent = null;
        safCurrent = null;
        safTreeUri = null;
        safHistory.clear();
        browsingAuthorizedFolders = true;
        binding.localGameFileManagerLocation.setText(R.string.local_game_file_manager_authorized);
        clearRows();
        addTile(getString(R.string.local_game_file_manager_grant_directory), true,
            view -> directoryPicker.launch(SafPermissionManager.createOpenTreeIntent()), null);
        List<Uri> roots = new ArrayList<>();
        getContentResolver().getPersistedUriPermissions().forEach(permission -> {
            if (permission.isReadPermission()) roots.add(permission.getUri());
        });
        if (roots.isEmpty()) {
            addEmpty(getString(R.string.local_game_file_manager_empty));
            return;
        }
        for (Uri root : roots) {
            DocumentFile file = DocumentFile.fromTreeUri(this, root);
            if (file == null) continue;
            String name = file.getName();
            addTile(name == null || name.trim().isEmpty() ? root.toString() : name, true,
                view -> showSaf(file, root, true), view -> importGame(root));
        }
    }

    private void showLocal(File directory) {
        try {
            File canonical = directory.getCanonicalFile();
            if (!isWithin(filesRoot.getCanonicalFile(), canonical) || !canonical.isDirectory()) {
                showHome();
                return;
            }
            localCurrent = canonical;
            safCurrent = null;
            safTreeUri = null;
            safHistory.clear();
            browsingAuthorizedFolders = false;
            binding.localGameFileManagerLocation.setText(canonical.getPath());
            clearRows();
            File[] entries = canonical.listFiles();
            if (entries == null || entries.length == 0) {
                addEmpty(getString(R.string.local_game_file_manager_empty));
                return;
            }
            Arrays.sort(entries, Comparator.comparing(File::isFile).thenComparing(File::getName,
                String.CASE_INSENSITIVE_ORDER));
            for (File entry : entries) {
                if (!isWithin(filesRoot, entry)) continue;
                if (entry.isDirectory()) {
                    addTile(entry.getName(), true, view -> showLocal(entry),
                        view -> importGame(Uri.fromFile(entry)));
                } else {
                    addTile(entry.getName(), false, null, null);
                }
            }
        } catch (IOException error) {
            showHome();
        }
    }

    private void showSaf(DocumentFile directory, Uri treeUri, boolean resetHistory) {
        if (directory == null || !directory.isDirectory()) {
            showHome();
            return;
        }
        if (resetHistory) safHistory.clear();
        localCurrent = null;
        safCurrent = directory;
        safTreeUri = treeUri;
        browsingAuthorizedFolders = false;
        binding.localGameFileManagerLocation.setText(directory.getUri().toString());
        clearRows();
        DocumentFile[] entries;
        try {
            entries = directory.listFiles();
        } catch (SecurityException error) {
            addEmpty(getString(R.string.local_game_import_permission_lost));
            return;
        }
        if (entries.length == 0) {
            addEmpty(getString(R.string.local_game_file_manager_empty));
            return;
        }
        List<DocumentFile> sorted = new ArrayList<>(Arrays.asList(entries));
        Collections.sort(sorted, Comparator.comparing(DocumentFile::isFile).thenComparing(
            file -> file.getName() == null ? "" : file.getName(), String.CASE_INSENSITIVE_ORDER));
        for (DocumentFile entry : sorted) {
            String name = entry.getName() == null ? entry.getUri().toString() : entry.getName();
            if (entry.isDirectory()) {
                addTile(name, true,
                    view -> {
                        safHistory.addLast(directory);
                        showSaf(entry, treeUri, false);
                    }, view -> importSafFolder(treeUri, entry));
            } else {
                addTile(name, false, null, null);
            }
        }
    }

    private void importSafFolder(Uri treeUri, DocumentFile directory) {
        try {
            importGame(SafGameRootUri.forDocument(treeUri, directory.getUri()));
        } catch (IOException error) {
            Toast.makeText(this, getString(R.string.local_game_import_failed,
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    private void importGame(Uri root) {
        startActivity(LocalGames.createImportIntent(this)
            .putExtra(GameImportActivity.EXTRA_TREE_URI, root.toString()));
    }

    private void clearRows() {
        binding.localGameFileManagerItems.removeAllViews();
        binding.localGameFileManagerItems.setColumnCount(gridColumns());
    }

    private void addTile(String title, boolean directory, @Nullable View.OnClickListener listener,
                         @Nullable View.OnClickListener menuListener) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundResource(R.drawable.local_games_bg_file_tile);
        tile.setOnClickListener(listener);
        tile.setEnabled(listener != null);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(8), dp(10), dp(8), dp(4));
        tile.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(directory ? R.drawable.local_games_ic_folder :
            R.drawable.local_games_ic_file);
        icon.setContentDescription(null);
        content.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(getColor(directory ? R.color.local_games_on_surface :
            R.color.local_games_on_surface_secondary));
        label.setTextSize(11);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        labelParams.topMargin = dp(5);
        content.addView(label, labelParams);

        if (menuListener != null) {
            ImageButton more = new ImageButton(this);
            more.setImageResource(R.drawable.local_games_ic_overflow);
            more.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            more.setContentDescription(getString(R.string.local_game_card_more));
            more.setPadding(dp(4), dp(4), dp(4), dp(4));
            more.setOnClickListener(view -> showFolderMenu(view, menuListener));
            FrameLayout.LayoutParams moreParams = new FrameLayout.LayoutParams(dp(28), dp(28),
                Gravity.TOP | Gravity.END);
            moreParams.setMargins(0, dp(4), dp(4), 0);
            tile.addView(more, moreParams);
        }

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = gridTileWidth();
        params.height = dp(112);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        binding.localGameFileManagerItems.addView(tile, params);
    }

    private void showFolderMenu(View anchor, View.OnClickListener importListener) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.local_game_file_manager_import_as_game);
        menu.setOnMenuItemClickListener(item -> {
            importListener.onClick(anchor);
            return true;
        });
        menu.show();
    }

    private void addEmpty(String message) {
        TextView empty = new TextView(this);
        empty.setText(message);
        empty.setTextColor(getColor(R.color.local_games_on_surface_secondary));
        empty.setPadding(dp(8), dp(24), dp(8), dp(24));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(0, gridColumns());
        binding.localGameFileManagerItems.addView(empty, params);
    }

    private int gridColumns() {
        return getResources().getConfiguration().orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 6 : 4;
    }

    private int gridTileWidth() {
        int horizontalPadding = dp(40);
        int cellMargins = dp(10);
        int available = Math.max(dp(96), getResources().getDisplayMetrics().widthPixels -
            horizontalPadding - cellMargins * gridColumns());
        return Math.max(dp(96), available / gridColumns());
    }

    private boolean isWithin(File root, File candidate) {
        try {
            File canonicalRoot = root.getCanonicalFile();
            File canonicalCandidate = candidate.getCanonicalFile();
            String prefix = canonicalRoot.getPath() + File.separator;
            return canonicalCandidate.equals(canonicalRoot) ||
                canonicalCandidate.getPath().startsWith(prefix);
        } catch (IOException error) {
            return false;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
