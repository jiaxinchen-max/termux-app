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
    private static final int MAX_EDITOR_SIZE = IdeEditorBuffer.MAX_LENGTH, IMPORT = 301, EXPORT = 302;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private IdeWorkspace workspace;
    private File directory, editing, exportFile;
    private LinearLayout content, toolbar;
    private TextView title, status, logView;
    private IdeCodeEditor editor;
    private IdeSyntax syntax;
    private String savedText;
    private boolean busy, refreshingTask;
    private FrameLayout pageHost, editorHost;
    private ScrollView pageScroll, treeScroll, logScroll;
    private LinearLayout workbench, treeRows, tabs, logPanel;
    private IdeEditorSession session = new IdeEditorSession();
    private boolean inWorkspace;
    private enum Page { DIRECTORY, TOOLS, TASK }
    private Page page = Page.DIRECTORY;
    private int treeGeneration, treeLimit = 500;
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
        Object retained = getLastCustomNonConfigurationInstance();
        if (retained instanceof IdeEditorSession) session = (IdeEditorSession) retained;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.color.local_games_background);
        title = text(16); title.setSingleLine(); title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); root.addView(title);
        toolbar = new LinearLayout(this);
        HorizontalScrollView actions = new HorizontalScrollView(this); actions.addView(toolbar); root.addView(actions);
        pageHost = new FrameLayout(this);
        pageScroll = new ScrollView(this); pageScroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(8)); pageScroll.addView(content);
        pageHost.addView(pageScroll);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text(12); status.setSingleLine(); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> showTask()); root.addView(status);
        setContentView(root);
        UiNavigation.install(this, R.id.local_games_navigation_ide, destination -> {
            if (destination != R.id.local_games_navigation_ide) afterDiscard(() -> UiNavigation.openDestination(this, destination));
            return false;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (busy) return;
                if (page != Page.DIRECTORY) { showDirectory(); return; }
                if (inWorkspace && session.logVisible) { setLogVisible(false); return; }
                if (inWorkspace && treeScroll.getVisibility() == View.VISIBLE
                    && getResources().getConfiguration().screenWidthDp < 600) { toggleTree(); return; }
                afterDiscard(() -> {
                    if (inWorkspace) { directory = workspace.root; showDirectory(); }
                    else if (editing != null || logView != null) showDirectory();
                    else if (!directory.equals(workspace.root)) { directory = directory.getParentFile(); showDirectory(); }
                    else { UiNavigation.openDestination(IdeActivity.this, R.id.local_games_navigation_library); finish(); }
                });
            }
        });
        if (state != null && Page.TOOLS.name().equals(state.getString("page"))) {
            showTools();
        } else if (state != null && Page.TASK.name().equals(state.getString("page"))) {
            showTask();
        } else if (state != null && state.getString("editing") != null) {
            openFile(new File(state.getString("editing")), state.getString("text"), state.getString("saved"));
        } else showDirectory();
    }
    @Override public Object onRetainCustomNonConfigurationInstance() { captureDocument(); return session; }

    @Override protected void onResume() {
        super.onResume();
        if (!IdePreference.isEnabled(this)) { UiNavigation.openDestination(this, R.id.local_games_navigation_library); finish(); return; }
        handler.post(poll);
    }
    @Override protected void onPause() { handler.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); releaseEditor(); io.shutdownNow(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        captureDocument();
        state.putString("page", page.name());
        if (directory != null) state.putString("directory", directory.getPath());
        if (editing != null && editor != null) {
            state.putString("editing", editing.getPath()); state.putString("text", editor.getText().toString()); state.putString("saved", savedText);
        }
        if (exportFile != null) state.putString("export", exportFile.getPath());
        super.onSaveInstanceState(state);
    }
    private void reset(String heading) {
        captureDocument(); inWorkspace = false; treeGeneration++;
        if (workbench != null) workbench.setVisibility(View.GONE);
        pageScroll.setVisibility(View.VISIBLE);
        releaseEditor();
        editor = null; editing = null; logView = null;
        content.removeAllViews(); toolbar.removeAllViews(); title.setText(heading);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));
        title.setVisibility(View.VISIBLE); ((View) toolbar.getParent()).setVisibility(View.VISIBLE);
    }
    private File currentProject() throws IOException { return workspace.owner(directory); }
    private void action(int label, Runnable run) {
        Button button = new Button(this); button.setText(label); button.setTextSize(12);
        button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setOnClickListener(v -> { if (!busy) run.run(); }); toolbar.addView(button);
    }
    private void showDirectory() {
        page = Page.DIRECTORY;
        if (!directory.equals(workspace.root)) { showWorkspace(); return; }
        reset(getString(R.string.ide_projects));
        session.documents.clear(); session.active = null; session.expanded.clear(); treeLimit = 500;
        title.setVisibility(View.GONE); ((View) toolbar.getParent()).setVisibility(View.GONE);
        content.setPadding(0, 0, 0, 0);
        IdeWelcomeView welcome = new IdeWelcomeView(this, new IdeWelcomeView.Actions() {
            @Override public void onAction(int label) {
                if (busy) return;
                if (label == R.string.ide_new_project) createProject();
                else if (label == R.string.ide_open_project || label == R.string.ide_all_projects) chooseProject();
                else if (label == R.string.ide_import_project) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), IMPORT);
                else if (label == R.string.ide_tools) showTools();
                else if (label == R.string.ide_open_terminal) LocalGames.requireHost(IdeActivity.this).openTerminal();
                else if (label == R.string.ide_task) showTask();
            }
            @Override public void onProject(File project) {
                if (!busy) { directory = project; showDirectory(); }
            }
        });
        content.addView(welcome, new LinearLayout.LayoutParams(-1, -2));
        background(this::recentProjects, projects -> {
            if (welcome.getParent() == content) welcome.setProjects(projects);
        });
    }
    private List<File> recentProjects() throws IOException {
        List<File> projects = workspace.projects();
        SharedPreferences history = getSharedPreferences("termux_ide_recent_projects", MODE_PRIVATE);
        // Stable sort retains creation order for projects that have never been opened.
        projects.sort((a, b) -> Long.compare(history.getLong(b.getAbsolutePath(), 0), history.getLong(a.getAbsolutePath(), 0)));
        return projects;
    }
    private void chooseProject() {
        background(this::recentProjects, projects -> {
            if (projects.isEmpty()) { Toast.makeText(this, R.string.ide_no_projects, Toast.LENGTH_LONG).show(); return; }
            String[] names = new String[projects.size()];
            for (int i = 0; i < names.length; i++) names[i] = projects.get(i).getName();
            new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog).setTitle(R.string.ide_open_project)
                .setItems(names, (dialog, which) -> { directory = projects.get(which); showDirectory(); })
                .setNegativeButton(android.R.string.cancel, null).show();
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
        if (!session.documents.containsKey(file.getAbsolutePath()) && session.documents.size() >= 12) {
            Toast.makeText(this, R.string.ide_tab_limit, Toast.LENGTH_LONG).show(); return;
        }
        background(() -> {
            File checked = workspace.checked(file);
            if (!checked.isFile() || checked.length() > MAX_EDITOR_SIZE) throw new IOException(getString(R.string.ide_text_only));
            byte[] bytes = new byte[(int)checked.length()];
            try (DataInputStream in = new DataInputStream(new FileInputStream(checked))) { in.readFully(bytes); }
            for (byte value : bytes) if (value == 0) throw new IOException(getString(R.string.ide_text_only));
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }, value -> {
            captureDocument();
            String path = file.getAbsolutePath();
            if (!session.documents.containsKey(path)) session.documents.put(path,
                new IdeEditorSession.Document(restored == null ? value : restored, saved == null ? value : saved));
            session.active = path;
            directory = file.getParentFile();
            showWorkspace();
        });
    }
    private void captureDocument() {
        if (editing == null || editor == null) return;
        IdeEditorSession.Document document = session.documents.get(editing.getAbsolutePath());
        if (document == null) return;
        document.text = editor.getText().toString(); document.saved = savedText;
        document.cursor = editor.getSelectionStart(); document.scroll = editor.getOffsetY();
        document.buffer = editor.getText();
    }
    private void displayDocument(String path) {
        captureDocument();
        IdeEditorSession.Document document = session.documents.get(path);
        if (document == null) return;
        releaseEditor();
        editing = new File(path); savedText = document.saved; session.active = path;
        editorHost.removeAllViews();
        editor = new IdeCodeEditor(this);
        editor.setText(document.buffer == null ? document.text : document.buffer);
        editor.setSelection(document.cursor);
        editorHost.addView(editor, new FrameLayout.LayoutParams(-1, -1));
        syntax = new IdeSyntax(editor, editing.getName());
        IdeCodeEditor target = editor;
        target.post(() -> { if (editor == target) target.restoreScroll(document.scroll); });
        editor.subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent.class, (event, unsubscribe) -> {
            boolean wasDirty = document.dirty(); document.text = target.getText().toString();
            if (wasDirty != document.dirty()) renderTabs();
        });
        renderTabs();
    }
    private void releaseEditor() {
        if (syntax != null) { syntax.detach(); syntax = null; }
        if (editor != null) { editor.release(); editor = null; }
    }
    private void showWorkspace() {
        captureDocument();
        final File project;
        try { project = currentProject(); if (project == null) return; }
        catch (IOException e) { fail(e); return; }
        if (!inWorkspace) getSharedPreferences("termux_ide_recent_projects", MODE_PRIVATE).edit()
            .putLong(project.getAbsolutePath(), System.currentTimeMillis()).apply();
        inWorkspace = true; pageScroll.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE); ((View) toolbar.getParent()).setVisibility(View.VISIBLE);
        title.setText(project.getName()); title.setContentDescription(project.getPath()); toolbar.removeAllViews();
        action(R.string.ide_files, this::toggleTree);
        action(R.string.ide_save, () -> save(null));
        action(R.string.ide_build, () -> save(this::build));
        action(R.string.ide_task, () -> setLogVisible(!session.logVisible));
        action(R.string.ide_more, this::workspaceMenu);
        if (workbench == null) {
            workbench = new LinearLayout(this); workbench.setOrientation(LinearLayout.VERTICAL);
            LinearLayout body = new LinearLayout(this);
            treeScroll = new ScrollView(this); treeScroll.setFillViewport(true);
            treeScroll.setBackgroundResource(R.color.local_games_surface);
            treeRows = new LinearLayout(this); treeRows.setOrientation(LinearLayout.VERTICAL); treeScroll.addView(treeRows);
            int width = Math.min(260, Math.max(150, getResources().getConfiguration().screenWidthDp * 2 / 5));
            body.addView(treeScroll, new LinearLayout.LayoutParams(dp(width), -1));
            LinearLayout center = new LinearLayout(this); center.setOrientation(LinearLayout.VERTICAL);
            HorizontalScrollView tabScroll = new HorizontalScrollView(this); tabScroll.setHorizontalScrollBarEnabled(false);
            tabs = new LinearLayout(this); tabs.setBackgroundResource(R.color.local_games_surface);
            tabScroll.addView(tabs); center.addView(tabScroll);
            editorHost = new FrameLayout(this); center.addView(editorHost, new LinearLayout.LayoutParams(-1, 0, 1));
            body.addView(center, new LinearLayout.LayoutParams(0, -1, 1));
            workbench.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
            logPanel = new LinearLayout(this); logPanel.setOrientation(LinearLayout.VERTICAL);
            logPanel.setBackgroundResource(R.color.local_games_surface);
            LinearLayout logActions = new LinearLayout(this);
            panelButton(logActions, R.string.ide_hide_log, () -> setLogVisible(false));
            panelButton(logActions, android.R.string.cancel, () -> IdeBuildService.start(this, "cancel", null));
            panelButton(logActions, R.string.ide_export_apk, this::chooseArtifact);
            HorizontalScrollView logButtons = new HorizontalScrollView(this); logButtons.addView(logActions); logPanel.addView(logButtons);
            logScroll = new ScrollView(this); logPanel.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            int height = Math.max(120, Math.min(240, getResources().getConfiguration().screenHeightDp / 3));
            workbench.addView(logPanel, new LinearLayout.LayoutParams(-1, dp(height)));
            pageHost.addView(workbench);
        }
        workbench.setVisibility(View.VISIBLE);
        treeScroll.setVisibility(session.treeVisible ? View.VISIBLE : View.GONE);
        session.expanded.add(project.getAbsolutePath()); renderTree(project);
        if (session.active != null && session.documents.containsKey(session.active)) displayDocument(session.active);
        else { editorHost.removeAllViews(); TextView hint = text(14); hint.setText(R.string.ide_select_file); editorHost.addView(hint); renderTabs(); }
        setLogVisible(session.logVisible);
    }
    private void panelButton(LinearLayout row, int label, Runnable run) {
        Button button = new Button(this); button.setText(label); button.setTextSize(11); button.setAllCaps(false);
        button.setOnClickListener(v -> { if (!busy) run.run(); }); row.addView(button);
    }
    private void workspaceMenu() {
        int[] labels = {R.string.ide_projects, R.string.ide_new_file, R.string.ide_new_folder,
            R.string.ide_close_tab, R.string.ide_tools, R.string.ide_open_terminal, R.string.ide_delete_project, R.string.ide_undo, R.string.ide_redo, R.string.ide_sync_project};
        PopupMenu popup = new PopupMenu(this, toolbar.getChildAt(toolbar.getChildCount() - 1));
        for (int i = 0; i < labels.length; i++) popup.getMenu().add(0, i, i, labels[i]);
        popup.getMenu().findItem(7).setEnabled(editor != null && editor.canUndo());
        popup.getMenu().findItem(8).setEnabled(editor != null && editor.canRedo());
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 9: save(() -> {
                    try { File project = currentProject(); if (project != null) startTask("sync", project.getName()); }
                    catch (IOException e) { fail(e); }
                }); break;
                case 7: if (editor != null) editor.undo(); break;
                case 8: if (editor != null) editor.redo(); break;
                case 0: afterDiscard(() -> { directory = workspace.root; showDirectory(); }); break;
                case 1: createEntry(false); break;
                case 2: createEntry(true); break;
                case 3: closeDocument(); break;
                case 4: showTools(); break;
                case 5: LocalGames.requireHost(this).openTerminal(); break;
                case 6: afterDiscard(this::deleteProject); break;
            }
            return true;
        }); popup.show();
    }
    private void toggleTree() { session.treeVisible = !session.treeVisible; treeScroll.setVisibility(session.treeVisible ? View.VISIBLE : View.GONE); }
    private void renderTabs() {
        if (tabs == null) return;
        tabs.removeAllViews();
        for (Map.Entry<String, IdeEditorSession.Document> entry : session.documents.entrySet()) {
            String path = entry.getKey(); TextView tab = text(13);
            tab.setText(new File(path).getName() + (entry.getValue().dirty() ? " •" : ""));
            tab.setContentDescription(path); tab.setSingleLine(); tab.setMaxWidth(dp(220));
            tab.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            if (path.equals(session.active)) {
                tab.setTypeface(null, Typeface.BOLD); tab.setBackgroundResource(R.color.local_games_selected_surface);
                tab.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.local_games_primary_text));
            }
            tab.setAlpha(path.equals(session.active) ? 1f : 0.65f);
            tab.setOnClickListener(v -> { if (!busy) displayDocument(path); });
            tab.setOnLongClickListener(v -> { if (!busy) { displayDocument(path); closeDocument(); } return true; });
            LinearLayout tabItem = new LinearLayout(this); tabItem.setGravity(Gravity.CENTER_VERTICAL);
            tabItem.setBackgroundResource(path.equals(session.active) ? R.color.local_games_selected_surface : R.color.local_games_surface);
            tabItem.addView(tab, new LinearLayout.LayoutParams(-2, dp(44)));
            TextView close = text(18); close.setText("×"); close.setGravity(Gravity.CENTER);
            close.setPadding(0, 0, 0, 0); close.setContentDescription(getString(R.string.ide_close_file, new File(path).getName()));
            close.setOnClickListener(v -> { if (!busy) { displayDocument(path); closeDocument(); } });
            tabItem.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
            tabs.addView(tabItem);
        }
    }
    private void closeDocument() {
        captureDocument(); final String path = session.active;
        if (path == null) return;
        Runnable close = () -> {
            releaseEditor();
            editor = null; editing = null; session.documents.remove(path);
            session.active = session.documents.isEmpty() ? null : session.documents.keySet().iterator().next(); showWorkspace();
        };
        if (session.documents.get(path).dirty()) new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setMessage(R.string.ide_unsaved).setPositiveButton(android.R.string.ok, (d, w) -> close.run())
            .setNegativeButton(android.R.string.cancel, null).show();
        else close.run();
    }
    private void renderTree(File project) {
        final int generation = ++treeGeneration, limit = treeLimit;
        final Set<String> expanded = new HashSet<>(session.expanded);
        io.execute(() -> {
            List<File> files = new ArrayList<>(); List<Integer> depths = new ArrayList<>();
            try { collectTree(project, project.getCanonicalFile(), limit, 0, expanded, files, depths, new HashSet<>()); }
            catch (IOException e) { handler.post(() -> { if (!isDestroyed()) fail(e); }); }
            handler.post(() -> {
                if (isDestroyed() || !inWorkspace || generation != treeGeneration) return;
                treeRows.removeAllViews();
                for (int i = 0; i < files.size(); i++) {
                    File file = files.get(i); boolean folder = file.isDirectory();
                    TextView row = text(12); row.setSingleLine(); row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                    row.setPadding(dp(10 + depths.get(i) * 12), dp(10), dp(6), dp(10));
                    row.setText((folder ? (expanded.contains(file.getAbsolutePath()) ? "▾ " : "▸ ") : "  ") + file.getName());
                    row.setContentDescription(file.getName());
                    if (file.getAbsolutePath().equals(session.active)) row.setBackgroundResource(R.color.local_games_selected_surface);
                    row.setOnClickListener(v -> {
                        if (busy) return;
                        if (folder) { directory = file; if (!session.expanded.remove(file.getAbsolutePath())) session.expanded.add(file.getAbsolutePath()); renderTree(project); }
                        else { if (getResources().getConfiguration().screenWidthDp < 600) { session.treeVisible = false; treeScroll.setVisibility(View.GONE); } openFile(file, null, null); }
                    }); treeRows.addView(row);
                }
                if (files.size() >= limit) {
                    TextView more = text(12); more.setText(R.string.ide_more_files);
                    more.setOnClickListener(v -> { treeLimit += 500; renderTree(project); }); treeRows.addView(more);
                }
            });
        });
    }
    private void collectTree(File file, File boundary, int limit, int depth, Set<String> expanded, List<File> files, List<Integer> depths, Set<String> visited) throws IOException {
        if (depth > 30 || files.size() >= limit || Thread.currentThread().isInterrupted()) return;
        File checked = workspace.checked(file);
        if (!checked.equals(boundary) && !checked.getPath().startsWith(boundary.getPath() + File.separator)) return;
        files.add(file); depths.add(depth);
        if (!file.isDirectory() || !expanded.contains(file.getAbsolutePath()) || !visited.add(checked.getCanonicalPath())) return;
        File[] children = checked.listFiles(); if (children == null) return;
        Arrays.sort(children, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) { try { workspace.checked(child); } catch (IOException ignored) { continue; } collectTree(child, boundary, limit, depth + 1, expanded, files, depths, visited); }
    }
    private void setLogVisible(boolean visible) {
        session.logVisible = visible; logPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            if (logScroll.getChildCount() == 0) {
                logView = text(12); logView.setTypeface(Typeface.MONOSPACE); logView.setTextIsSelectable(true); logScroll.addView(logView);
            } else logView = (TextView) logScroll.getChildAt(0);
            refreshTask();
        } else logView = null;
    }

    private void save(@Nullable Runnable after) {
        captureDocument();
        Map<String, String> changes = new LinkedHashMap<>();
        for (Map.Entry<String, IdeEditorSession.Document> entry : session.documents.entrySet())
            if (entry.getValue().dirty()) changes.put(entry.getKey(), entry.getValue().text);
        if (changes.isEmpty()) { if (after != null) after.run(); return; }
        background(() -> {
            for (Map.Entry<String, String> entry : changes.entrySet()) {
                AtomicFile target = new AtomicFile(workspace.checked(new File(entry.getKey()))); FileOutputStream output = null;
                try { output = target.startWrite(); output.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); target.finishWrite(output); }
                catch (IOException e) { target.failWrite(output); throw e; }
            }
            return changes;
        }, saved -> {
            for (Map.Entry<String, String> entry : saved.entrySet()) session.documents.get(entry.getKey()).saved = entry.getValue();
            if (editing != null) savedText = session.documents.get(editing.getAbsolutePath()).saved;
            renderTabs(); Toast.makeText(this, R.string.ide_saved, Toast.LENGTH_SHORT).show(); if (after != null) after.run();
        });
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
        page = Page.TOOLS;
        action(R.string.ide_files, this::showDirectory);
        action(R.string.ide_check_tools, () -> startTask("check", null));
        action(R.string.ide_editor_license, () -> background(() -> {
            StringBuilder licenses = new StringBuilder();
            for (String path : new String[] {"ide/licenses/sora-editor.txt", "ide/tooling/LICENSE.txt"}) {
                licenses.append(path).append("\n\n");
                try (InputStream input = getAssets().open(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[4096]; int count;
                    while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                    licenses.append(output.toString("UTF-8")).append("\n\n");
                }
            }
            return licenses.toString();
        }, license -> new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(R.string.ide_editor_license).setMessage(license)
            .setPositiveButton(android.R.string.ok, null).show()));
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
        if (inWorkspace) { setLogVisible(true); return; }
        reset(getString(R.string.ide_task)); action(R.string.ide_files, this::showDirectory);
        page = Page.TASK;
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
        captureDocument();
        if (session.dirty()) {
            new FrostedDialogBuilder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog).setMessage(R.string.ide_unsaved)
                .setPositiveButton(android.R.string.ok, (d, w) -> { session.discard(); if (editor != null) editor.setText(savedText); action.run(); })
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
