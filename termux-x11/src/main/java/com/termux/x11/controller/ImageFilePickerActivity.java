package com.termux.x11.controller;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.core.FileUtils;
import com.termux.x11.controller.core.LocaleHelper;
import com.termux.x11.controller.core.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import com.termux.x11.R;

public class ImageFilePickerActivity extends AppCompatActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ALLOW_MULTIPLE = "allow_multiple";
    public static final String EXTRA_FILE_FILTER = "file_filter";
    public static final String EXTRA_SELECTED_FILES = "selected_files";

    public static final int MODE_IMAGE = 0;
    public static final int MODE_FILE = 1;
    public static final int MODE_SAVE_DIR = 2;

    private static final String[] IMAGE_EXTENSIONS = {"png", "jpg", "jpeg", "gif", "bmp", "webp", "svg"};

    private RecyclerView recyclerView;
    private TextView currentPathTextView;
    private TextView selectionInfoTextView;
    private TextView emptyTextView;
    private FileAdapter adapter;

    private File currentDir;
    private boolean allowMultiple;
    private String[] fileExtensions;
    private int mode;
    private final ArrayList<File> selectedFiles = new ArrayList<>();
    private final HashSet<String> selectedPaths = new HashSet<>();
    private final ArrayList<FileItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.image_file_picker_activity);

        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_IMAGE);
        allowMultiple = getIntent().getBooleanExtra(EXTRA_ALLOW_MULTIPLE, false);

        String filter = getIntent().getStringExtra(EXTRA_FILE_FILTER);
        if (filter != null) {
            fileExtensions = filter.split(",");
        } else if (mode == MODE_IMAGE) {
            fileExtensions = IMAGE_EXTENSIONS;
        } else {
            fileExtensions = new String[]{"ipk"};
        }

        currentDir = getBestStartDir();

        currentPathTextView = findViewById(R.id.TVCurrentPath);
        selectionInfoTextView = findViewById(R.id.TVSelectionInfo);
        emptyTextView = findViewById(R.id.TVEmptyText);
        recyclerView = findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);

        findViewById(R.id.BTUp).setOnClickListener((v) -> {
            File parent = currentDir.getParentFile();
            if (parent != null && parent.canRead()) {
                currentDir = parent;
                refreshContent();
            }
        });

        findViewById(R.id.BTConfirm).setOnClickListener((v) -> confirmSelection());
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshContent();
    }

    private File getBestStartDir() {
        // 恢复入口：优先打开 Downloads（备份文件保存位置）
        File downloads = new File(AppUtils.DIRECTORY_DOWNLOADS);
        if (downloads.isDirectory() && downloads.canRead()) return downloads;

        String extStorage = System.getenv("EXTERNAL_STORAGE");
        if (extStorage != null) {
            File dir = new File(extStorage);
            if (dir.isDirectory() && dir.canRead()) return dir;
        }
        File dir = new File("/sdcard");
        if (dir.isDirectory() && dir.canRead()) return dir;
        dir = new File("/storage/emulated/0");
        if (dir.isDirectory() && dir.canRead()) return dir;
        return new File("/");
    }

    private void refreshContent() {
        items.clear();
        currentPathTextView.setText(currentDir.getAbsolutePath());

        File[] files = currentDir.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });

            for (File file : files) {
                if (file.isHidden()) continue;
                if (file.isDirectory()) {
                    items.add(new FileItem(file, true));
                } else if (mode != MODE_SAVE_DIR && matchesFilter(file)) {
                    items.add(new FileItem(file, false));
                }
            }
        }

        emptyTextView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
        updateSelectionInfo();
    }

    private boolean matchesFilter(File file) {
        String ext = FileUtils.getExtension(file.getName().toLowerCase());
        if (ext.isEmpty()) return false;
        for (String allowed : fileExtensions) {
            if (ext.equals(allowed.toLowerCase())) return true;
        }
        return false;
    }

    private void updateSelectionInfo() {
        if (mode == MODE_SAVE_DIR) {
            selectionInfoTextView.setVisibility(View.VISIBLE);
            selectionInfoTextView.setText(getString(R.string.select_current_directory));
        } else if (allowMultiple && !selectedFiles.isEmpty()) {
            selectionInfoTextView.setVisibility(View.VISIBLE);
            selectionInfoTextView.setText(getString(R.string.selected_file_count, selectedFiles.size()));
        } else if (!allowMultiple && !selectedFiles.isEmpty()) {
            selectionInfoTextView.setVisibility(View.VISIBLE);
            selectionInfoTextView.setText(selectedFiles.get(0).getName());
        } else {
            selectionInfoTextView.setVisibility(View.GONE);
        }
    }

    private void confirmSelection() {
        if (mode == MODE_SAVE_DIR) {
            Intent result = new Intent();
            ArrayList<String> paths = new ArrayList<>();
            paths.add(currentDir.getAbsolutePath());
            result.putStringArrayListExtra(EXTRA_SELECTED_FILES, paths);
            setResult(Activity.RESULT_OK, result);
            finish();
            return;
        }

        if (selectedFiles.isEmpty()) {
            AppUtils.showToast(this, R.string.no_files_selected);
            return;
        }

        Intent result = new Intent();
        ArrayList<String> paths = new ArrayList<>();
        for (File f : selectedFiles) paths.add(f.getAbsolutePath());
        result.putStringArrayListExtra(EXTRA_SELECTED_FILES, paths);
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private class FileItem {
        final File file;
        final boolean isDirectory;

        FileItem(File file, boolean isDirectory) {
            this.file = file;
            this.isDirectory = isDirectory;
        }
    }

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
        private class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView iconView;
            final TextView nameView;
            final TextView metaView;
            final CheckBox checkBox;

            ViewHolder(View view) {
                super(view);
                iconView = view.findViewById(R.id.IVIcon);
                nameView = view.findViewById(R.id.TVFileName);
                metaView = view.findViewById(R.id.TVFileMeta);
                checkBox = view.findViewById(R.id.CBSelect);
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.image_file_picker_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            FileItem item = items.get(position);
            holder.nameView.setText(item.file.getName());

            if (item.isDirectory) {
                holder.iconView.setImageResource(R.drawable.icon_popup_menu_folder_open);
                holder.metaView.setText(getString(R.string.folder));
                holder.checkBox.setVisibility(View.GONE);
                holder.itemView.setOnClickListener((v) -> {
                    currentDir = item.file;
                    refreshContent();
                });
            } else {
                if (mode == MODE_IMAGE) {
                    loadThumbnail(item.file, holder.iconView);
                } else {
                    holder.iconView.setImageResource(R.drawable.icon_popup_menu_file_manager);
                }
                holder.metaView.setText(StringUtils.formatBytes(item.file.length()));

                boolean isSelected = selectedPaths.contains(item.file.getAbsolutePath());
                if (allowMultiple) {
                    holder.checkBox.setVisibility(View.VISIBLE);
                    holder.checkBox.setOnCheckedChangeListener(null);
                    holder.checkBox.setChecked(isSelected);
                    holder.checkBox.setOnCheckedChangeListener((cb, checked) -> {
                        if (checked) {
                            selectedFiles.add(item.file);
                            selectedPaths.add(item.file.getAbsolutePath());
                        } else {
                            selectedFiles.remove(item.file);
                            selectedPaths.remove(item.file.getAbsolutePath());
                        }
                        updateSelectionInfo();
                    });
                    holder.itemView.setOnClickListener((v) -> holder.checkBox.toggle());
                } else {
                    holder.checkBox.setVisibility(View.GONE);
                    holder.itemView.setOnClickListener((v) -> {
                        selectedFiles.clear();
                        selectedPaths.clear();
                        selectedFiles.add(item.file);
                        selectedPaths.add(item.file.getAbsolutePath());
                        updateSelectionInfo();
                        v.post(() -> confirmSelection());
                    });
                }
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private void loadThumbnail(File file, ImageView imageView) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);

            int targetSize = 80;
            int scale = 1;
            if (opts.outWidth > targetSize || opts.outHeight > targetSize) {
                scale = Math.max(1, Math.max(opts.outWidth, opts.outHeight) / targetSize);
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = scale;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap != null) imageView.setImageBitmap(bitmap);
            else imageView.setImageResource(R.drawable.icon_image_picker);
        } catch (Exception e) {
            imageView.setImageResource(R.drawable.icon_image_picker);
        }
    }
}
