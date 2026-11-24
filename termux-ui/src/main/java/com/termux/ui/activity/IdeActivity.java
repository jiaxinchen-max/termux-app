package com.termux.ui.activity;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.AtomicFile;
import android.view.*;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.documentfile.provider.DocumentFile;
import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import com.termux.ui.platform.IdePreference;
import com.termux.ui.ide.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/** Native Android project workspace, editor, supervised Gradle tasks and explicit APK export. */
public final class IdeActivity extends AppUiActivity {
    private static final int MAX_EDITOR_SIZE = 128 * 1024, IMPORT = 301, EXPORT = 302;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private IdeWorkspace workspace;
    private File directory, editing, exportFile;
    private LinearLayout content, toolbar;
    private TextView title, status, logView;
    private EditText editor;
    private IdeSyntax syntax;
    private String savedText;
    private boolean busy, refreshingTask;
    private final Runnable poll = new Runnable() {
        @Override public void run() { refreshTask(); handler.postDelayed(this, 1000); }
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        try {
            workspace = new IdeWorkspace(new File(getFilesDir(), "home/projects"));
            directory = workspace.root;
            io.execute(() -> {
                try { IdeBuildService.recover(getApplicationContext()); }
                catch (IOException e) { handler.post(() -> { if (!isFinishing() && !isDestroyed()) fail(e); }); }
            });
            if (state != null) {
                String saved = state.getString("directory");
                if (saved != null && workspace.checked(new File(saved)).isDirectory()) directory = new File(saved);
                String export = state.getString("export");
                if (export != null) exportFile = checkedArtifact(export);
            }
        } catch (IOException e) { fail(e); finish(); return; }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.color.local_games_background);
        title = text(20); root.addView(title);
        toolbar = new LinearLayout(this);
        HorizontalScrollView actions = new HorizontalScrollView(this); actions.addView(toolbar); root.addView(actions);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(8)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text(12); status.setOnClickListener(v -> afterDiscard(this::showTask)); root.addView(status);
        setContentView(root);
        UiNavigation.install(this, R.id.local_games_navigation_ide, destination -> {
            if (destination != R.id.local_games_navigation_ide) afterDiscard(() -> UiNavigation.openDestination(this, destination));
            return false;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (busy) return;
                afterDiscard(() -> {
                    if (editing != null || logView != null) showDirectory();
                    else if (!directory.equals(workspace.root)) { directory = directory.getParentFile(); showDirectory(); }
                    else { UiNavigation.openDestination(IdeActivity.this, R.id.local_games_navigation_library); finish(); }
                });
            }
        });
        if (state != null && state.getString("editing") != null) {
            title.setText(R.string.ide_title);
            action(R.string.ide_files, this::showDirectory);
            openFile(new File(state.getString("editing")), state.getString("text"), state.getString("saved"));
        } else showDirectory();
    }
    @Override protected void onResume() {
        super.onResume();
        if (!IdePreference.isEnabled(this)) { UiNavigation.openDestination(this, R.id.local_games_navigation_library); finish(); return; }
        handler.post(poll);
    }
    @Override protected void onPause() { handler.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); if (syntax != null) syntax.detach(); io.shutdownNow(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (directory != null) state.putString("directory", directory.getPath());
        if (editing != null && editor != null) {
            state.putString("editing", editing.getPath()); state.putString("text", editor.getText().toString()); state.putString("saved", savedText);
        }
        if (exportFile != null) state.putString("export", exportFile.getPath());
        super.onSaveInstanceState(state);
    }
    private void reset(String heading) {
        if (syntax != null) { syntax.detach(); syntax = null; }
        editor = null; editing = null; logView = null;
        content.removeAllViews(); toolbar.removeAllViews(); title.setText(heading);
    }
    private File currentProject() throws IOException { return workspace.owner(directory); }
    private void action(int label, Runnable run) {
        Button button = new Button(this); button.setText(label); button.setTextSize(12);
        button.setOnClickListener(v -> { if (!busy) run.run(); }); toolbar.addView(button);
    }
    private void showDirectory() {
        reset(directory.equals(workspace.root) ? getString(R.string.ide_projects) : directory.getName());
        action(R.string.ide_tools, this::showTools);
        action(R.string.ide_open_terminal, () -> LocalGames.requireHost(this).openTerminal());
        if (directory.equals(workspace.root)) {
            action(R.string.ide_new_project, this::createProject);
            action(R.string.ide_import_project, () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), IMPORT));
        } else {
            action(R.string.ide_build, this::build);
            action(R.string.ide_new_file, () -> createEntry(false));
            action(R.string.ide_new_folder, () -> createEntry(true));
            action(R.string.ide_delete_project, this::deleteProject);
        }
        action(R.string.ide_task, this::showTask);
        SettingsMenuBuilder menu = new SettingsMenuBuilder(this, content);
        menu.row(directory.getPath(), null, null);
        if (!directory.equals(workspace.root)) menu.row("..", null, v -> { directory = directory.getParentFile(); showDirectory(); });
        background(() -> {
            List<File> result;
            if (directory.equals(workspace.root)) result = workspace.projects();
            else {
                File[] files = directory.listFiles(); if (files == null) throw new IOException("Cannot list directory");
                result = new ArrayList<>(Arrays.asList(files));
                result.sort((a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
            }
            List<File> safe = new ArrayList<>();
            for (File file : result) { try { workspace.checked(file); safe.add(file); } catch (IOException ignored) { } }
            return safe;
        }, files -> {
            for (File file : files) menu.row(file.getName(), file.isDirectory() ? "/" : android.text.format.Formatter.formatFileSize(this, file.length()), v -> {
                if (busy) return;
                if (file.isDirectory()) { directory = file; showDirectory(); } else openFile(file, null, null);
            });
        });
    }
    private void createProject() {
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        EditText name = input(getString(R.string.ide_project_name), "MyApplication");
        EditText appId = input(getString(R.string.ide_application_id), "com.example.myapplication"); fields.addView(name); fields.addView(appId);
        new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog).setTitle(R.string.ide_new_project).setView(fields)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> background(() -> {
                try (InputStream wrapper = getAssets().open("ide/gradle/wrapper/gradle-wrapper.jar");
                     InputStream license = getAssets().open("ide/GRADLE-LICENSE.txt")) {
                    return IdeTemplate.create(workspace, name.getText().toString().trim(), appId.getText().toString().trim(), wrapper, license);
                }
            }, project -> { directory = project; showDirectory(); }))
            .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void createEntry(boolean folder) {
        EditText name = input(getString(R.string.ide_name), "");
        new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(folder ? R.string.ide_new_folder : R.string.ide_new_file).setView(name)
            .setPositiveButton(android.R.string.ok, (d, w) -> background(() -> {
                String value = name.getText().toString().trim();
                if (value.isEmpty() || value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")) throw new IOException("Invalid file name");
                File file = workspace.checked(new File(directory, value));
                if (folder ? !file.mkdir() : !file.createNewFile()) throw new IOException("Already exists or cannot create");
                return file;
            }, file -> { if (folder) showDirectory(); else openFile(file, null, null); }))
            .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void deleteProject() {
        try {
            File project = currentProject();
            if (project == null) return;
            new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
                .setTitle(R.string.ide_delete_project).setMessage(getString(R.string.ide_delete_warning, project.getName()))
                .setPositiveButton(android.R.string.ok, (d, w) -> background(() -> {
                    if (IdeJobStore.active(new IdeJobStore(this).read())) throw new IOException("Wait for the active task or cancel it first.");
                    workspace.deleteProject(project); return true;
                }, done -> { directory = workspace.root; showDirectory(); }))
                .setNegativeButton(android.R.string.cancel, null).show();
        } catch (IOException e) { fail(e); }
    }
    private void openFile(File file, @Nullable String restored, @Nullable String saved) {
        background(() -> {
            File checked = workspace.checked(file);
            if (!checked.isFile() || checked.length() > MAX_EDITOR_SIZE) throw new IOException(getString(R.string.ide_text_only));
            byte[] bytes = new byte[(int)checked.length()];
            try (DataInputStream in = new DataInputStream(new FileInputStream(checked))) { in.readFully(bytes); }
            for (byte value : bytes) if (value == 0) throw new IOException(getString(R.string.ide_text_only));
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }, value -> {
            reset(file.getName()); editing = file; savedText = saved == null ? value : saved;
            action(R.string.ide_save, () -> save(null));
            action(R.string.ide_build, () -> save(this::build));
            action(R.string.ide_files, () -> afterDiscard(this::showDirectory));
            action(R.string.ide_task, () -> afterDiscard(this::showTask));
            editor = new EditText(this);
            editor.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.local_games_on_surface));
            editor.setGravity(Gravity.TOP | Gravity.START); editor.setHorizontallyScrolling(true);
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            editor.setTypeface(Typeface.MONOSPACE); editor.setTextSize(14);
            // Source code needs neither paragraph optimization nor dictionary hyphenation.
            if (Build.VERSION.SDK_INT >= 23) {
                editor.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                editor.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            }
            editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            editor.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(MAX_EDITOR_SIZE)});
            editor.setText(restored == null ? value : restored); editor.setMinLines(18);
            content.addView(editor, new LinearLayout.LayoutParams(-1, -2)); syntax = new IdeSyntax(editor, file.getName());
        });
    }
    private void save(@Nullable Runnable after) {
        if (editing == null || editor == null) return;
        File file = editing; String text = editor.getText().toString();
        background(() -> {
            AtomicFile target = new AtomicFile(workspace.checked(file)); FileOutputStream output = null;
            try { output = target.startWrite(); output.write(text.getBytes(StandardCharsets.UTF_8)); target.finishWrite(output); }
            catch (IOException e) { target.failWrite(output); throw e; }
            return text;
        }, saved -> { savedText = saved; Toast.makeText(this, R.string.ide_saved, Toast.LENGTH_SHORT).show(); if (after != null) after.run(); });
    }
    private void build() {
        try {
            File project = currentProject(); if (project == null) return;
            new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog).setTitle(R.string.ide_build)
                .setMessage(R.string.ide_build_warning).setPositiveButton(R.string.ide_build, (d, w) -> startTask("build", project.getName()))
                .setNegativeButton(android.R.string.cancel, null).show();
        } catch (IOException e) { fail(e); }
    }
    private void startTask(String operation, String project) {
        try {
            if (Build.VERSION.SDK_INT < 26) throw new IOException(getString(R.string.ide_android_version));
            if (!LocalGames.requireHost(this).isRuntimeAvailable()) throw new IOException(getString(R.string.ide_runtime_missing));
            if (IdeJobStore.active(new IdeJobStore(this).read())) { Toast.makeText(this, R.string.ide_already_running, Toast.LENGTH_LONG).show(); showTask(); return; }
            IdeBuildService.start(this, operation, project); showTask();
        } catch (Exception e) { fail(e); }
    }
    private void showTools() {
        reset(getString(R.string.ide_tools));
        action(R.string.ide_files, this::showDirectory);
        action(R.string.ide_check_tools, () -> startTask("check", null));
        action(R.string.ide_prepare_tools, () -> new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(R.string.ide_prepare_tools).setMessage(R.string.ide_prepare_warning)
            .setPositiveButton(R.string.ide_accept_prepare, (d, w) -> startTask("prepare", null))
            .setNegativeButton(android.R.string.cancel, null).show());
        background(() -> {
            IdeToolchain tools = new IdeToolchain(this);
            return android.util.Pair.create(tools, tools.describe());
        }, result -> {
            TextView description = text(13); description.setText(result.second + "\n\n" + getString(R.string.ide_ndk_limit)); content.addView(description);
            Button paths = new Button(this); paths.setText(R.string.ide_tool_paths); paths.setOnClickListener(v -> editPaths(result.first)); content.addView(paths);
        });
    }
    private void editPaths(IdeToolchain tools) {
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        EditText java = input("JAVA_HOME", tools.javaHome.getPath()), sdk = input("ANDROID_HOME", tools.sdk.getPath());
        fields.addView(java); fields.addView(sdk);
        new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog).setTitle(R.string.ide_tool_paths).setView(fields)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                getSharedPreferences("termux_ide", MODE_PRIVATE).edit().putString("java_home", java.getText().toString().trim())
                    .putString("sdk_home", sdk.getText().toString().trim()).apply(); showTools();
            }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void showTask() {
        reset(getString(R.string.ide_task)); action(R.string.ide_files, this::showDirectory);
        action(android.R.string.cancel, () -> IdeBuildService.start(this, "cancel", null));
        action(R.string.ide_export_apk, this::chooseArtifact);
        logView = text(12); logView.setTypeface(Typeface.MONOSPACE); logView.setTextIsSelectable(true); content.addView(logView); refreshTask();
    }
    private void refreshTask() {
        if (status == null || refreshingTask || io.isShutdown()) return;
        refreshingTask = true;
        TextView targetLog = logView;
        io.execute(() -> {
            try {
                IdeJobStore store = new IdeJobStore(this); Properties task = store.read();
                String log = targetLog == null ? "" : store.log(task);
                if (targetLog != null) {
                    int count = Integer.parseInt(task.getProperty("apk.count", "0"));
                    for (int i = 0; i < count; i++) {
                        File apk = checkedArtifact(task.getProperty("apk." + i));
                        log += "\n" + apk.getName() + " · " + android.text.format.Formatter.formatFileSize(this, apk.length())
                            + " · " + new java.util.Date(apk.lastModified()) + "\n" + apk;
                    }
                }
                String output = log;
                handler.post(() -> {
                    refreshingTask = false;
                    if (isFinishing() || isDestroyed()) return;
                    String summary = getString(taskStateLabel(task.getProperty("state", "idle")))
                        + " · " + task.getProperty("project", "");
                    if (!summary.contentEquals(status.getText())) status.setText(summary);
                    if (targetLog != null && logView == targetLog) {
                        String display = summary + "\n" + task.getProperty("message", "") + "\n" + output;
                        if (!display.contentEquals(targetLog.getText())) targetLog.setText(display);
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    refreshingTask = false;
                    if (!isFinishing() && !isDestroyed()) status.setText(e.getMessage());
                });
            }
        });
    }
    private static int taskStateLabel(String state) {
        switch (state) {
            case "queued": return R.string.ide_state_queued;
            case "running": return R.string.ide_state_running;
            case "cancelling": return R.string.ide_state_cancelling;
            case "cancelled": return R.string.ide_state_cancelled;
            case "succeeded": return R.string.ide_state_succeeded;
            case "failed": return R.string.ide_state_failed;
            case "interrupted": return R.string.ide_state_interrupted;
            default: return R.string.ide_state_idle;
        }
    }
    private void chooseArtifact() {
        try {
            Properties task = new IdeJobStore(this).read();
            if (!"succeeded".equals(task.getProperty("state"))) throw new IOException(getString(R.string.ide_no_apk));
            int count = Integer.parseInt(task.getProperty("apk.count", "0"));
            if (count == 0) throw new IOException(getString(R.string.ide_no_apk));
            String[] names = new String[count]; File[] files = new File[count];
            for (int i = 0; i < count; i++) { files[i] = checkedArtifact(task.getProperty("apk." + i)); names[i] = files[i].getName(); }
            new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog).setTitle(R.string.ide_export_apk)
                .setItems(names, (d, which) -> {
                    exportFile = files[which];
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/vnd.android.package-archive")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, exportFile.getName()), EXPORT);
                }).show();
        } catch (Exception e) { fail(e); }
    }
    private File checkedArtifact(String path) throws IOException {
        if (path == null) throw new IOException("Missing APK");
        File file = new File(path).getCanonicalFile();
        File jobs = new IdeJobStore(this).root().getCanonicalFile();
        if (!file.getPath().startsWith(jobs.getPath() + File.separator) || !file.isFile() || !file.getName().endsWith(".apk")) throw new IOException("APK not found");
        return file;
    }
    @Override protected void onActivityResult(int request, int result, @Nullable Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == IMPORT) {
            EditText name = input(getString(R.string.ide_project_name), "ImportedProject");
            new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog).setTitle(R.string.ide_import_project).setView(name)
                .setPositiveButton(android.R.string.ok, (d, w) -> background(() -> importProject(uri, name.getText().toString().trim()),
                    project -> { directory = project; showDirectory(); }))
                .setNegativeButton(android.R.string.cancel, null).show();
        } else if (request == EXPORT && exportFile != null) {
            File source = exportFile;
            background(() -> {
                try (InputStream in = new FileInputStream(checkedArtifact(source.getPath())); OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("Cannot open export destination"); copy(in, out, new long[] {0}, Long.MAX_VALUE);
                }
                return true;
            }, done -> Toast.makeText(this, R.string.ide_exported, Toast.LENGTH_LONG).show());
        }
    }
    private File importProject(Uri tree, String name) throws IOException {
        File target = workspace.project(name);
        if (target.exists()) throw new IOException("Project already exists");
        DocumentFile source = DocumentFile.fromTreeUri(this, tree);
        if (source == null || !source.isDirectory()) throw new IOException("Cannot open project folder");
        File staging = new File(workspace.root, ".import-" + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("Cannot create import folder");
        try {
            importTree(source, staging, "", new long[] {0, 0}, 0);
            if (!new File(staging, "gradlew").isFile() || !(new File(staging, "settings.gradle").isFile() || new File(staging, "settings.gradle.kts").isFile()))
                throw new IOException("Select a Gradle project root containing gradlew and settings.gradle(.kts).");
            workspace.markCreated(staging);
            if (!staging.renameTo(target)) throw new IOException("Cannot publish imported project");
            return target;
        } finally { if (staging.exists()) workspace.deleteTree(staging); }
    }
    private void importTree(DocumentFile source, File staging, String prefix, long[] budget, int depth) throws IOException {
        if (depth > 40 || Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled or tree too deep");
        for (DocumentFile child : source.listFiles()) {
            String name = child.getName();
            if (name == null || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) throw new IOException("Invalid import entry");
            if (name.equals(".gradle") || name.equals(".git") || (prefix.isEmpty() && name.equals("local.properties"))) continue;
            if (name.equals("build") && child.isDirectory()
                && (source.findFile("build.gradle") != null || source.findFile("build.gradle.kts") != null)) continue;
            if (++budget[1] > 50000) throw new IOException("Too many imported files");
            String relative = prefix + name; File target = workspace.importTarget(staging, relative);
            if (child.isDirectory()) {
                if (!target.mkdir()) throw new IOException("Cannot create imported folder");
                importTree(child, staging, relative + "/", budget, depth + 1);
            } else if (child.isFile()) {
                try (InputStream in = getContentResolver().openInputStream(child.getUri()); OutputStream out = new FileOutputStream(target)) {
                    if (in == null) throw new IOException("Cannot read imported file"); copy(in, out, budget, 2L * 1024 * 1024 * 1024);
                }
            } else throw new IOException("Unreadable import entry");
        }
    }
    private static void copy(InputStream in, OutputStream out, long[] budget, long max) throws IOException {
        byte[] buffer = new byte[32768]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
            budget[0] += n; if (budget[0] > max) throw new IOException("Import exceeds 2 GiB"); out.write(buffer, 0, n);
        }
    }
    private void afterDiscard(Runnable action) {
        if (busy) return;
        if (editor != null && !editor.getText().toString().equals(savedText)) {
            new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog).setMessage(R.string.ide_unsaved)
                .setPositiveButton(android.R.string.ok, (d, w) -> { editor.setText(savedText); action.run(); })
                .setNegativeButton(android.R.string.cancel, null).show();
        } else action.run();
    }
    private interface Work<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T result); }
    private <T> void background(Work<T> task, Done<T> done) {
        if (busy) return; busy = true;
        io.execute(() -> {
            try { T result = task.run(); handler.post(() -> { busy = false; if (!isFinishing() && !isDestroyed()) done.accept(result); }); }
            catch (Exception e) { handler.post(() -> { busy = false; if (!isFinishing() && !isDestroyed()) fail(e); }); }
        });
    }
    private EditText input(String hint, String value) { EditText input = new EditText(this); input.setSingleLine(); input.setHint(hint); input.setText(value); return input; }
    private TextView text(int sp) { TextView view = new TextView(this); view.setTextSize(sp); view.setPadding(dp(12), dp(8), dp(12), dp(8)); view.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.local_games_on_surface)); return view; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void fail(Exception e) { Toast.makeText(this, e.getMessage() == null ? getString(R.string.ide_io_error) : e.getMessage(), Toast.LENGTH_LONG).show(); }
}
