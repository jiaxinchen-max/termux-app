package com.termux.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.termux.ui.R;
import com.termux.ui.api.ComponentTasks;
import com.termux.ui.api.CustomComponentInstallTasks;
import com.termux.ui.api.LocalGames;
import com.termux.ui.api.RuntimeComponentVariant;
import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.components.catalog.ComponentCatalogItem;
import com.termux.ui.components.catalog.ComponentCatalogRepository;
import com.termux.ui.components.index.ComponentDescriptor;
import com.termux.ui.components.index.ComponentIndex;
import com.termux.ui.components.index.ComponentIndexParser;
import com.termux.ui.components.install.ComponentInstallationReader;
import com.termux.ui.components.install.InstalledComponent;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.data.CustomComponentInstallation;
import com.termux.ui.data.FileCustomComponentInstallTaskRepository;
import com.termux.ui.data.FileCustomRuntimeComponentRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.ComponentTaskState;
import com.termux.ui.domain.CustomComponentInstallTask;
import com.termux.ui.domain.CustomComponentInstallTaskState;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeTranslator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The same version list and installation flow serves management and per-game selection. */
public final class RuntimeComponentVersionsActivity extends AppUiActivity {
    public static final String EXTRA_SELECTION_ID = "com.termux.ui.extra.COMPONENT_SELECTION_ID";
    public static final String EXTRA_KIND = "component.kind";
    public static final String EXTRA_BACKEND = "component.backend";
    private static final String EXTRA_MODE = "component.mode";
    private static final String EXTRA_LOCKED_TRANSLATOR = "component.locked_translator";
    private enum Mode { MANAGE, SELECT }

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = () -> reload();
    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) showCustomComponentMetadataDialog(this.kind,
                this.backend == GameRuntimeBackendType.ROOTFS_PROOT, uri);
        });
    private CustomRuntimeComponent.Kind kind;
    private GameRuntimeBackendType backend;
    private RuntimeTranslator locked;
    private Mode mode;
    private String selected;
    private GameStoragePaths paths;
    private FileCustomRuntimeComponentRepository customComponents;
    private LinearLayout content;
    private ScrollView scroll;
    private boolean loading;
    private boolean resumed;
    private String signature;

    public static Intent createManageIntent(Context context, CustomRuntimeComponent.Kind kind,
                                            GameRuntimeBackendType backend) {
        return createIntent(context, kind, backend, Mode.MANAGE);
    }

    public static Intent createSelectIntent(Context context, CustomRuntimeComponent.Kind kind,
                                            GameRuntimeBackendType backend, String current,
                                            @Nullable RuntimeTranslator locked) {
        Intent intent = createIntent(context, kind, backend, Mode.SELECT)
            .putExtra(EXTRA_SELECTION_ID, current);
        if (locked != null) intent.putExtra(EXTRA_LOCKED_TRANSLATOR, locked.getStorageValue());
        return intent;
    }

    private static Intent createIntent(Context context, CustomRuntimeComponent.Kind kind,
                                       GameRuntimeBackendType backend, Mode mode) {
        if (kind == null || backend == null || !RuntimeComponentVersions.supports(kind, backend)) {
            throw new IllegalArgumentException("component_scope_unsupported");
        }
        return new Intent(context, RuntimeComponentVersionsActivity.class)
            .putExtra(EXTRA_KIND, kind.name()).putExtra(EXTRA_BACKEND, backend.getStorageValue())
            .putExtra(EXTRA_MODE, mode.name());
    }

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        try {
            kind = CustomRuntimeComponent.Kind.valueOf(getIntent().getStringExtra(EXTRA_KIND));
            backend = GameRuntimeBackendType.fromStorageValue(getIntent().getStringExtra(EXTRA_BACKEND));
            mode = Mode.valueOf(getIntent().getStringExtra(EXTRA_MODE));
            String value = getIntent().getStringExtra(EXTRA_LOCKED_TRANSLATOR);
            locked = value == null ? null : RuntimeTranslator.fromStorageValue(value);
            if (!RuntimeComponentVersions.supports(kind, backend)) throw new IllegalArgumentException("component_scope_unsupported");
        } catch (RuntimeException error) {
            fail(error);
            finish();
            return;
        }
        selected = getIntent().getStringExtra(EXTRA_SELECTION_ID);
        if (kind == CustomRuntimeComponent.Kind.BOX64) {
            selected = RuntimeComponentVersions.selectedBox64Id(backend, selected);
        }
        paths = new GameStoragePaths(getFilesDir());
        customComponents = new FileCustomRuntimeComponentRepository(paths.getCustomRuntimeComponentsDirectory());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.local_games_background));
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(getString(R.string.component_versions_title, RuntimeComponentVersions.displayName(kind)));
        toolbar.setSubtitle(backend == GameRuntimeBackendType.ROOTFS_PROOT ? "PRoot" : "Native");
        toolbar.setTitleTextColor(color(R.color.local_games_on_surface));
        toolbar.setSubtitleTextColor(color(R.color.local_games_on_surface_secondary));
        toolbar.setNavigationIcon(R.drawable.local_games_ic_back);
        if (toolbar.getNavigationIcon() != null) toolbar.getNavigationIcon().setTint(color(R.color.local_games_on_surface));
        toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
        toolbar.setNavigationOnClickListener(view -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, getResources().getDimensionPixelSize(R.dimen.local_games_toolbar_height)));
        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (content != null) reload();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class Row {
        final String id, name;
        final ComponentDescriptor descriptor;
        final ComponentTask task;
        final boolean available;
        int description;
        Row(String id, String name, ComponentDescriptor descriptor, ComponentTask task, boolean available) {
            this.id = id; this.name = name; this.descriptor = descriptor; this.task = task; this.available = available;
        }
    }

    private void reload() {
        if (loading || isFinishing() || isDestroyed()) return;
        loading = true;
        ioExecutor.execute(() -> {
            try {
                List<Row> installed = new ArrayList<>();
                List<Row> online = new ArrayList<>();
                Map<String, Row> unique = new LinkedHashMap<>();
                for (RuntimeComponentVariant entry : LocalGames.requireHost(this)
                    .listInstalledRuntimeComponentVariants(kind, backend)) {
                    if (entry.getKind() != kind || entry.getBackendType() != backend) throw new IOException("component_variant_scope_mismatch");
                    unique.put(entry.getId(), new Row(entry.getId(), entry.getDisplayName(), null, null, true));
                }
                for (CustomRuntimeComponent entry : customComponents.list()) {
                    if (RuntimeComponentVersions.accepts(entry, kind, backend, locked) && CustomComponentInstallation.isAvailable(paths, entry)) {
                        unique.put(entry.getId(), new Row(entry.getId(), entry.getDisplayName(), null, null, true));
                    }
                }
                ComponentStoragePaths storage = new ComponentStoragePaths(getFilesDir());
                ComponentInstallationReader receipts = new ComponentInstallationReader(storage.getInstallDirectory());
                ComponentIndex index;
                try (InputStream stream = getAssets().open("termux-app-packages/index-v1.json")) {
                    index = new ComponentIndexParser().parse(stream);
                }
                // Hangover belongs to the base image, not to the downloadable component catalog.
                if (kind == CustomRuntimeComponent.Kind.WINE && backend == GameRuntimeBackendType.ROOTFS_PROOT
                    && (locked == null || locked == RuntimeTranslator.HANGOVER)) {
                    boolean ready = new com.termux.ui.wine.runtime.RuntimeEnvironmentStatus(paths).rootfsState()
                        == com.termux.ui.wine.domain.RuntimeReadinessState.READY;
                    Row bundled = new Row(RuntimeComponentVersions.PREINSTALLED_HANGOVER, "Hangover 11.16", null, null, ready);
                    bundled.description = ready ? R.string.component_versions_preinstalled_ready
                        : paths.getSharedRootfsContainerDirectory().exists()
                            ? R.string.component_versions_preinstalled_incomplete
                            : R.string.component_versions_preinstalled_pending;
                    unique.put(bundled.id, bundled);
                }
                for (ComponentCatalogItem item : new ComponentCatalogRepository(index,
                    new FileComponentTaskRepository(storage.getTasksDirectory()), receipts).load()) {
                    ComponentDescriptor descriptor = item.getDescriptor();
                    if (!RuntimeComponentVersions.accepts(descriptor, kind, backend, locked)) continue;
                    InstalledComponent receipt = receipts.read(descriptor.getId()).getActive().orElse(null);
                    boolean available = receipt != null && receipt.getVersion() == descriptor.getVersion() &&
                        receipt.getDirectory().isDirectory();
                    if (available && backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
                        available = LocalGames.requireHost(this).isRuntimeComponentAvailable(descriptor.getId(),
                            descriptor.getVersion(), descriptor.getSha256());
                    }
                    String selectionId = RuntimeComponentVersions.catalogSelectionId(descriptor, kind, backend);
                    ComponentTask task = item.getTask().orElse(null);
                    Row row = new Row(selectionId, descriptor.getDisplayName(), descriptor, task, available);
                    if (selectionId != null && available) unique.put(selectionId, row);
                    else if (!available) online.add(row);
                }
                installed.addAll(unique.values());
                runOnUiThread(() -> {
                    loading = false;
                    if (!isDestroyed() && !isFinishing()) render(installed, online);
                    if (resumed) handler.postDelayed(refresh, 1500);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    loading = false;
                    if (isDestroyed() || isFinishing()) return;
                    signature = null;
                    SettingsMenuBuilder menu = new SettingsMenuBuilder(this, content);
                    menu.row(getString(R.string.component_versions_failed, error.getMessage()),
                        getString(R.string.component_versions_retry), view -> reload());
                });
            }
        });
    }

    private void render(List<Row> installed, List<Row> online) {
        StringBuilder next = new StringBuilder();
        for (Row row : installed) next.append(row.id).append(row.name).append(row.description);
        for (Row row : online) next.append(row.descriptor.getId()).append(row.available)
            .append(row.task == null ? "" : row.task.getState().name());
        next.append(selected).append(mode);
        if (next.toString().equals(signature)) return;
        signature = next.toString();
        int scrollY = scroll.getScrollY();
        SettingsMenuBuilder menu = new SettingsMenuBuilder(this, content);
        List<Row> preinstalled = new ArrayList<>();
        List<Row> separate = new ArrayList<>();
        for (Row row : installed) (row.description != 0 ? preinstalled : separate).add(row);
        boolean currentFound = false;
        if (!preinstalled.isEmpty()) {
            menu.heading(getString(R.string.component_versions_preinstalled));
            for (Row row : preinstalled) {
                boolean current = row.id.equals(selected);
                currentFound |= current;
                String description = getString(row.description);
                if (current) description += " · " + getString(R.string.component_versions_current);
                if (mode == Mode.SELECT) FrostedMenuRow.addChoice(this, content, row.name, description,
                    current, view -> choose(row.id));
                else menu.row(row.name, description, null);
            }
        }
        installed = separate;
        if (!installed.isEmpty() || preinstalled.isEmpty()) {
            menu.heading(getString(R.string.component_versions_installed));
            if (installed.isEmpty()) menu.row(getString(R.string.component_versions_empty), null, null);
        }
        for (Row row : installed) {
            boolean current = row.id.equals(selected);
            currentFound |= current;
            if (mode == Mode.SELECT) {
                FrostedMenuRow.addChoice(this, content, row.name,
                    getString(current ? R.string.component_versions_current : R.string.component_versions_installed),
                    current, view -> choose(row.id));
            } else {
                menu.row(row.name, getString(R.string.component_versions_installed), null);
            }
        }
        if (mode == Mode.SELECT && selected != null && !selected.isEmpty() && !currentFound) {
            menu.row(getString(R.string.component_versions_missing, selected), null, null);
        }
        if (RuntimeComponentVersions.allowsManualInstall(kind, backend, locked)) {
            menu.row(getString(R.string.component_versions_manual), null, view ->
                filePicker.launch(new String[] {"application/x-xz", "application/zstd", "application/gzip",
                    "application/x-tar", "application/octet-stream"}));
        }
        if (!online.isEmpty()) {
            menu.heading(getString(R.string.component_versions_online));
            for (Row row : online) {
                menu.row(row.name, getString(row.task != null && row.task.getState().shouldRecoverAutomatically()
                    ? R.string.component_versions_in_progress : R.string.component_versions_download), view -> install(row));
            }
        }
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void choose(String id) {
        setResult(Activity.RESULT_OK, new Intent().putExtra(EXTRA_SELECTION_ID, id).putExtra(EXTRA_KIND, kind.name())
            .putExtra(EXTRA_BACKEND, backend.getStorageValue()));
        finish();
    }

    private void install(Row row) {
        try {
            String taskId;
            if (row.task != null && row.task.getState().shouldRecoverAutomatically()) taskId = row.task.getTaskId();
            else if (row.task != null && row.task.getState() == ComponentTaskState.PAUSED) {
                ComponentTasks.resume(this, row.task.getTaskId()); taskId = row.task.getTaskId();
            } else {
                taskId = ComponentTasks.enqueue(this, row.descriptor.getId());
            }
            if (taskId == null) { signature = null; reload(); }
            else ComponentTaskConsoleDialog.show(this, taskId);
        } catch (RuntimeException error) { fail(error); }
    }

    private void fail(Throwable error) {
        Toast.makeText(this, getString(R.string.component_versions_failed,
            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()), Toast.LENGTH_LONG).show();
    }

    private void reloadCustomComponentsAndRender() { signature = null; reload(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(int id) { return ContextCompat.getColor(this, id); }
    private View scrollable(View view) { ScrollView host = new ScrollView(this); host.addView(view); return host; }
    private void showCustomComponentMetadataDialog(CustomRuntimeComponent.Kind kind,
            boolean forRootfs, Uri uri) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int margin = dp(20);
        form.setPadding(margin, 0, margin, 0);
        EditText name = new EditText(this);
        name.setHint(R.string.local_games_custom_component_name_hint);
        name.setSingleLine(true);
        name.setTextColor(color(R.color.local_games_on_surface));
        name.setHintTextColor(color(R.color.local_games_on_surface_secondary));
        form.addView(name);
        new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(getString(R.string.component_versions_title, RuntimeComponentVersions.displayName(kind)))
            .setView(scrollable(form))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_custom_component_add_action, (dialog, which) -> {
                String displayName = name.getText().toString().trim();
                if (displayName.isEmpty()) {
                    Toast.makeText(this,
                        R.string.local_games_custom_component_name_required, Toast.LENGTH_LONG).show();
                    return;
                }
                RuntimeTranslator translator = kind == CustomRuntimeComponent.Kind.WINE && forRootfs
                    ? RuntimeTranslator.BOX64 : null;
                startCustomComponentInstall(kind, translator, displayName, uri);
            })
            .show();
    }

    private void startCustomComponentInstall(CustomRuntimeComponent.Kind kind,
            @Nullable RuntimeTranslator translator, String displayName, Uri uri) {
        String slug = displayName.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "build";
        if (slug.length() > 64) slug = slug.substring(0, 64);
        String componentId = "custom-" + kind.name().toLowerCase(Locale.ROOT) + "-"
            + (backend == GameRuntimeBackendType.ROOTFS_PROOT ? "proot-" : "native-") + slug;
        try {
            if (customComponents.find(componentId).isPresent()) {
                Toast.makeText(this,
                    R.string.local_games_custom_component_id_exists, Toast.LENGTH_LONG).show();
                return;
            }
        } catch (IOException error) {
            fail(error);
            return;
        }
        String finalComponentId = componentId;
        RuntimeTranslator finalTranslator = translator;
        Toast.makeText(this, R.string.local_games_custom_component_installing,
            Toast.LENGTH_SHORT).show();
        ioExecutor.execute(() -> {
            File staged;
            String sha256;
            try {
                staged = stageCustomComponentPayload(uri);
                sha256 = sha256Of(staged);
            } catch (IOException | NoSuchAlgorithmException | RuntimeException error) {
                runOnUiThread(() -> Toast.makeText(this,
                    R.string.local_games_custom_component_pick_failed, Toast.LENGTH_LONG).show());
                return;
            }
            try {
                String taskId = CustomComponentInstallTasks.enqueue(this, finalComponentId, kind,
                    finalTranslator, displayName, staged, sha256, backend);
                runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) showCustomComponentConsole(taskId); });
            } catch (RuntimeException error) {
                runOnUiThread(() -> fail(error));
            }
        });
    }

    private File stageCustomComponentPayload(Uri uri) throws IOException {
        File directory = paths.getCustomComponentStagingDirectory();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("custom_component_staging_directory_failed");
        }
        File staged = new File(directory, "pick-" + UUID.randomUUID() + ".payload");
        try (InputStream input = this.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("custom_component_source_unavailable");
            try (OutputStream output = new FileOutputStream(staged)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            }
        }
        return staged;
    }

    private static String sha256Of(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    private void showCustomComponentConsole(String taskId) {
        RuntimeSetupConsoleDialog.TaskStatusLookup lookup = id -> {
            try {
                CustomComponentInstallTask task = new FileCustomComponentInstallTaskRepository(
                    paths.getCustomComponentInstallTasksDirectory()).find(id).orElse(null);
                if (task == null) return null;
                return new RuntimeSetupConsoleDialog.TaskStatusLookup.Status(
                    task.getState() == CustomComponentInstallTaskState.FAILED, false,
                    task.getState() == CustomComponentInstallTaskState.SUCCEEDED,
                    task.getState().name());
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        };
        RuntimeSetupConsoleDialog.show(this, taskId,
                R.string.local_games_custom_component_console_title, lookup)
            .setOnDismissListener(dialog -> reloadCustomComponentsAndRender());
    }

}
