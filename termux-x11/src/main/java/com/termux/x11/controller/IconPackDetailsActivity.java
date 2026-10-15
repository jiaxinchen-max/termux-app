package com.termux.x11.controller;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.x11.controller.contentdialog.ContentDialog;
import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.core.FileUtils;
import com.termux.x11.controller.core.LocaleHelper;
import com.termux.x11.controller.inputcontrols.IconPackManager;
import com.termux.x11.controller.inputcontrols.IconPackUtils;

import java.io.File;
import java.util.ArrayList;
import com.termux.x11.R;

public class IconPackDetailsActivity extends AppCompatActivity {
    private static final int ADD_SINGLE_ICON_REQUEST_CODE = 4001;
    private static final int ADD_MULTIPLE_ICONS_REQUEST_CODE = 4002;
    private static final int EXPORT_ICON_PACK_REQUEST_CODE = 4003;
    private static final int ICON_TARGET_SIZE = 256;

    private IconPackManager iconPackManager;
    private String packId;
    private IconPackManager.StoredIconPack pack;
    private TextView summaryTextView;
    private TextView emptyTextView;
    private RecyclerView recyclerView;
    private IconsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.icon_pack_details_activity);

        iconPackManager = new IconPackManager(this);
        packId = getIntent().getStringExtra("pack_id");
        summaryTextView = findViewById(R.id.TVSummary);
        emptyTextView = findViewById(R.id.TVEmptyText);
        recyclerView = findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter = new IconsAdapter());

        ((Button)findViewById(R.id.BTAddSingle)).setOnClickListener((v) -> openImagePicker(false));
        ((Button)findViewById(R.id.BTAddMultiple)).setOnClickListener((v) -> openImagePicker(true));
        ((Button)findViewById(R.id.BTExport)).setOnClickListener((v) -> exportPack());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPack();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != Activity.RESULT_OK || data == null) return;

        if ((requestCode == ADD_SINGLE_ICON_REQUEST_CODE || requestCode == ADD_MULTIPLE_ICONS_REQUEST_CODE)) {
            ArrayList<String> filePaths = data.getStringArrayListExtra(ImageFilePickerActivity.EXTRA_SELECTED_FILES);
            if (filePaths == null || filePaths.isEmpty()) return;
            try {
                ArrayList<Uri> uris = new ArrayList<>();
                for (String path : filePaths) uris.add(Uri.fromFile(new File(path)));
                ArrayList<IconPackManager.SourceIcon> icons = IconPackUtils.loadSourceIcons(this, uris, ICON_TARGET_SIZE);
                if (icons.isEmpty()) {
                    AppUtils.showToast(this, R.string.unable_to_load_image);
                    return;
                }

                IconPackManager.StoredIconPack updatedPack = iconPackManager.addIcons(packId, icons);
                if (updatedPack != null) {
                    pack = updatedPack;
                    refreshPack();
                }
            }
            catch (Exception e) {
                AppUtils.showToast(this, R.string.unable_to_create_icon_pack);
            }
        }
        else if (requestCode == EXPORT_ICON_PACK_REQUEST_CODE) {
            ArrayList<String> filePaths = data.getStringArrayListExtra(ImageFilePickerActivity.EXTRA_SELECTED_FILES);
            if (filePaths == null || filePaths.isEmpty()) return;
            try {
                pack = iconPackManager.getPack(packId);
                File exportFile = new File(filePaths.get(0), FileUtils.getName(pack.name)+".ipk");
                if (pack != null && iconPackManager.exportPack(pack, exportFile)) {
                    AppUtils.showToast(this, getString(R.string.icon_pack_exported, pack.name));
                }
                else AppUtils.showToast(this, R.string.unable_to_export_icon_pack);
            }
            catch (Exception e) {
                AppUtils.showToast(this, R.string.unable_to_export_icon_pack);
            }
        }
    }

    private void refreshPack() {
        pack = iconPackManager.getPack(packId);
        if (pack == null) {
            finish();
            return;
        }

        summaryTextView.setText(getString(R.string.icon_pack_contains_count, pack.name, pack.icons.size()));
        emptyTextView.setVisibility(pack.icons.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(pack.icons.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void openImagePicker(boolean allowMultiple) {
        Intent intent = new Intent(this, ImageFilePickerActivity.class);
        intent.putExtra(ImageFilePickerActivity.EXTRA_MODE, ImageFilePickerActivity.MODE_IMAGE);
        intent.putExtra(ImageFilePickerActivity.EXTRA_ALLOW_MULTIPLE, allowMultiple);
        startActivityForResult(intent, allowMultiple ? ADD_MULTIPLE_ICONS_REQUEST_CODE : ADD_SINGLE_ICON_REQUEST_CODE);
    }

    private void exportPack() {
        if (pack == null) return;

        Intent intent = new Intent(this, ImageFilePickerActivity.class);
        intent.putExtra(ImageFilePickerActivity.EXTRA_MODE, ImageFilePickerActivity.MODE_SAVE_DIR);
        startActivityForResult(intent, EXPORT_ICON_PACK_REQUEST_CODE);
    }

    private void removeIcon(int position) {
        ContentDialog.confirm(this, R.string.do_you_want_to_remove_this_icon, () -> {
            try {
                IconPackManager.StoredIconPack updatedPack = iconPackManager.removeIcon(packId, position);
                if (updatedPack != null) {
                    pack = updatedPack;
                    refreshPack();
                }
                else AppUtils.showToast(this, R.string.unable_to_remove_icon_pack);
            }
            catch (Exception e) {
                AppUtils.showToast(this, R.string.unable_to_remove_icon_pack);
            }
        });
    }

    private class IconsAdapter extends RecyclerView.Adapter<IconsAdapter.ViewHolder> {
        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView previewImageView;
            private final TextView titleTextView;
            private final TextView subtitleTextView;
            private final ImageView removeButton;

            private ViewHolder(View view) {
                super(view);
                previewImageView = view.findViewById(R.id.IVPreview);
                titleTextView = view.findViewById(R.id.TVTitle);
                subtitleTextView = view.findViewById(R.id.TVSubtitle);
                removeButton = view.findViewById(R.id.BTRemove);
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.icon_pack_icon_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            IconPackManager.PackIcon icon = pack.icons.get(position);
            byte[] bytes = icon.readBytes();
            if (bytes != null && bytes.length > 0) {
                holder.previewImageView.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
                int[] size = IconPackUtils.getBitmapSize(bytes);
                holder.subtitleTextView.setText(getString(R.string.icon_pack_dimensions_format, size[0], size[1]));
            }
            else {
                holder.previewImageView.setImageResource(R.drawable.icon_image_picker);
                holder.subtitleTextView.setText(R.string.unknown);
            }

            holder.titleTextView.setText(getIconTitle(icon, position));
            holder.removeButton.setOnClickListener((v) -> removeIcon(position));
        }

        @Override
        public int getItemCount() {
            return pack != null ? pack.icons.size() : 0;
        }
    }

    private String getIconTitle(IconPackManager.PackIcon icon, int position) {
        if (icon == null) return getString(R.string.icon_pack_icon_title_format, position + 1);
        if (!icon.name.trim().isEmpty()) return icon.name;
        if (!icon.path.trim().isEmpty()) return icon.path;
        return getString(R.string.icon_pack_icon_title_format, position + 1);
    }
}
