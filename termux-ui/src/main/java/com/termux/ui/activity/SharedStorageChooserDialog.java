package com.termux.ui.activity;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.ui.R;
import com.termux.ui.runtime.RuntimeBackupSharedStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app file chooser for backups. Its root is fixed to shared storage ({@code ~/storage/shared})
 * and it never offers anything above that root; the system document picker is deliberately not
 * involved.
 */
final class SharedStorageChooserDialog {
    private static final String ROOT_LABEL = "~/storage/shared";
    /**
     * Horizontal inset AppCompat's default EditText background adds on each side; the file-name
     * field is pulled outwards by this much so its edge lines up with the other content.
     */
    private static final int EDIT_TEXT_BACKGROUND_INSET_DP = 4;
    private static final int ROW_MIN_HEIGHT_DP = 48;

    interface OnChosen {
        void onChosen(File file);
    }

    private final Activity activity;
    private final File root;
    private final boolean saveMode;
    private final String extension;
    private final OnChosen callback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesBackupChooserIo"));
    private final List<File> entries = new ArrayList<>();
    private final ArrayAdapter<String> adapter;
    private TextView pathView;
    private TextView emptyView;
    private EditText nameInput;
    private AlertDialog dialog;
    private ListView listView;
    @Nullable private AlertDialog overwriteDialog;
    private File current;
    private int generation;

    private SharedStorageChooserDialog(Activity activity, File root, boolean saveMode,
                                       String extension, OnChosen callback) {
        this.activity = activity;
        this.root = root;
        this.saveMode = saveMode;
        this.extension = extension;
        this.callback = callback;
        this.current = root;
        adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int position, @Nullable View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                if (row instanceof TextView) ((TextView) row).setMinHeight(dp(ROW_MIN_HEIGHT_DP));
                row.setMinimumHeight(dp(ROW_MIN_HEIGHT_DP));
                // The stock row inset (list item padding) pushes entries right of the title, the
                // path line and the file-name field; drop only the start side so they align.
                row.setPaddingRelative(0, row.getPaddingTop(), row.getPaddingEnd(), row.getPaddingBottom());
                return row;
            }
        };
    }

    /**
     * Pick a folder inside {@code root} and a file name ending in {@code extension}; the chosen
     * file may not exist yet.
     */
    static SharedStorageChooserDialog chooseSaveFile(Activity activity, File root, String defaultName,
                                                     String extension, OnChosen callback) {
        SharedStorageChooserDialog chooser =
            new SharedStorageChooserDialog(activity, root, true, extension, callback);
        chooser.show(defaultName);
        return chooser;
    }

    /** Pick one existing file ending in {@code extension} inside {@code root}. */
    static SharedStorageChooserDialog chooseArchive(Activity activity, File root, String extension,
                                                    OnChosen callback) {
        SharedStorageChooserDialog chooser =
            new SharedStorageChooserDialog(activity, root, false, extension, callback);
        chooser.show(null);
        return chooser;
    }

    /** Closes the chooser, its overwrite confirmation and its worker thread; safe to call twice. */
    void dismiss() {
        executor.shutdownNow();
        if (overwriteDialog != null && overwriteDialog.isShowing()) overwriteDialog.dismiss();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void show(@Nullable String defaultName) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        // Title, path line, list rows and file-name field share the title panel's left/right inset
        // (one dimens token), so they all start on the same vertical line.
        int pad = activity.getResources().getDimensionPixelSize(R.dimen.local_games_dialog_content_inset);
        content.setPadding(pad, dp(8), pad, 0);

        pathView = new TextView(activity);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pathView.setTextColor(pathColor());
        pathView.setPadding(0, 0, 0, dp(8));
        content.addView(pathView);

        ListView list = new ListView(activity);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onEntryClicked(position));
        listView = list;
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight()));

        emptyView = new TextView(activity);
        emptyView.setText(activity.getString(R.string.local_games_chooser_empty, extension));
        emptyView.setPadding(0, dp(8), 0, dp(8));
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView);

        if (saveMode) {
            nameInput = new EditText(activity);
            nameInput.setSingleLine(true);
            nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            nameInput.setHint(R.string.local_games_chooser_file_name);
            nameInput.setText(defaultName);
            nameInput.setMinimumHeight(dp(ROW_MIN_HEIGHT_DP));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.setMarginStart(-dp(EDIT_TEXT_BACKGROUND_INSET_DP));
            nameParams.setMarginEnd(-dp(EDIT_TEXT_BACKGROUND_INSET_DP));
            content.addView(nameInput, nameParams);
        }

        // The scroll view keeps every control reachable when the window is short or the IME is
        // open; the wrapper re-fits the list height when the window size changes.
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(content, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ConfigurationAwareFrameLayout contentRoot = new ConfigurationAwareFrameLayout(activity);
        contentRoot.addView(scroll, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentRoot.setListener(configuration -> fitListHeight());

        MaterialAlertDialogBuilder builder = new FrostedDialogBuilder(activity)
            .setTitle(saveMode ? R.string.local_games_chooser_title_save
                : R.string.local_games_chooser_title_restore)
            .setView(contentRoot)
            .setNegativeButton(android.R.string.cancel, null);
        if (saveMode) builder.setPositiveButton(R.string.local_games_chooser_save_here, null);
        dialog = builder.create();
        dialog.setOnDismissListener(d -> executor.shutdownNow());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        if (saveMode) {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> onSaveClicked());
        }
        navigate(root);
    }

    private int listHeight() {
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        return DialogSizing.listHeightPx(metrics.heightPixels, metrics.density);
    }

    private void fitListHeight() {
        if (listView == null) return;
        ViewGroup.LayoutParams params = listView.getLayoutParams();
        int height = listHeight();
        if (params != null && params.height != height) {
            params.height = height;
            listView.setLayoutParams(params);
        }
    }

    private void navigate(File directory) {
        current = directory;
        int token = ++generation;
        pathView.setText(displayPath(directory));
        executor.execute(() -> {
            List<File> listing = RuntimeBackupSharedStorage.listChoices(root, directory, saveMode, extension);
            activity.runOnUiThread(() -> {
                if (token == generation && dialog != null && dialog.isShowing()) showListing(directory, listing);
            });
        });
    }

    private void showListing(File directory, List<File> listing) {
        entries.clear();
        adapter.clear();
        File parent = RuntimeBackupSharedStorage.parentWithin(root, directory);
        if (parent != null) {
            entries.add(parent);
            adapter.add(activity.getString(R.string.local_games_chooser_up));
        }
        for (File file : listing) {
            entries.add(file);
            adapter.add(file.isDirectory() ? file.getName() + "/" : file.getName());
        }
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(listing.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void onEntryClicked(int position) {
        File file = entries.get(position);
        if (file.isDirectory()) {
            navigate(file);
        } else {
            dialog.dismiss();
            callback.onChosen(file);
        }
    }

    private void onSaveClicked() {
        String name = RuntimeBackupSharedStorage.normalizeArchiveName(nameInput.getText().toString(), extension);
        if (name == null) {
            nameInput.setError(activity.getString(R.string.local_games_chooser_invalid_name));
            return;
        }
        File target = new File(current, name);
        if (!target.exists()) {
            dialog.dismiss();
            callback.onChosen(target);
            return;
        }
        overwriteDialog = new FrostedDialogBuilder(activity)
            .setTitle(R.string.local_games_chooser_overwrite_title)
            .setMessage(activity.getString(R.string.local_games_chooser_overwrite_message, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_chooser_save_here, (d, which) -> {
                dialog.dismiss();
                callback.onChosen(target);
            })
            .show();
    }

    private String displayPath(File directory) {
        String rootPath = root.getPath();
        String path = directory.getPath();
        return path.startsWith(rootPath) ? ROOT_LABEL + path.substring(rootPath.length()) : ROOT_LABEL;
    }

    /**
     * The path line uses the theme's secondary text colour. A plain programmatic TextView would
     * otherwise fall back to a dark default that disappears on the dark dialog.
     */
    private int pathColor() {
        TypedArray attributes = activity.obtainStyledAttributes(new int[] {android.R.attr.textColorSecondary});
        try {
            ColorStateList colors = attributes.getColorStateList(0);
            return colors != null ? colors.getDefaultColor() : androidx.core.content.ContextCompat.getColor(activity, R.color.local_games_on_surface_secondary);
        } finally {
            attributes.recycle();
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            activity.getResources().getDisplayMetrics()));
    }
}
