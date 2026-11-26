package com.termux.ui.ide;

import android.app.*;
import android.content.*;
import android.os.*;
import android.system.Os;
import android.system.OsConstants;
import com.termux.ui.R;
import com.termux.ui.activity.IdeActivity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Owns native build/install processes. No dependency on an Activity or terminal session. */
public final class IdeBuildService extends Service {
    private static final String CHANNEL = "termux_ide_build";
    private static final int NOTIFICATION = 24101;
    private static final Object JOB_LOCK = new Object();
    private static volatile boolean owned;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private volatile File taskDirectory;
    private volatile java.lang.Process process;
    private IdeJobStore store;
    private volatile IdeToolingRpc toolingRpc;

    public static void start(Context context, String operation, String project) {
        Intent intent = new Intent(context, IdeBuildService.class).putExtra("operation", operation)
            .putExtra("project", project);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }
    public static void recover(Context context) throws IOException {
        synchronized (JOB_LOCK) {
            if (owned) return;
            IdeJobStore store = new IdeJobStore(context);
            Properties state = store.read();
            if (IdeJobStore.active(state)) {
                killGroup(store.directory(state.getProperty("id")));
                state.setProperty("state", "interrupted");
                state.setProperty("message", "The app process stopped before this task completed. Run again.");
                store.write(state);
            }
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        store = new IdeJobStore(this);
        if (Build.VERSION.SDK_INT >= 26) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
            .createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.ide_title), NotificationManager.IMPORTANCE_LOW));
        Notification.Builder notification = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, IdeActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, IdeBuildService.class).putExtra("operation", "cancel"), PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        startForeground(NOTIFICATION, notification.setSmallIcon(R.drawable.local_games_ic_ide)
            .setContentTitle(getString(R.string.ide_title)).setContentText(getString(R.string.ide_task_running))
            .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(android.R.string.cancel), cancel).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String operation = intent == null ? "" : intent.getStringExtra("operation");
        if ("cancel".equals(operation)) {
            cancelled = true;
            try {
                Properties state = store.read();
                if (IdeJobStore.active(state)) { state.setProperty("state", "cancelling"); store.write(state); }
            } catch (IOException ignored) { }
            if (owned) new Thread(() -> {
                try {
                    IdeToolingRpc rpc = toolingRpc;
                    if (rpc != null) try { rpc.call("server/cancelCurrentBuild", null, 3); } catch (Exception ignored) { }
                    killWhenReady();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "ide-cancel").start();
            else { try { recover(this); } catch (IOException ignored) { } stopSelf(); }
            return START_NOT_STICKY;
        }
        synchronized (JOB_LOCK) {
            if (owned) return START_NOT_STICKY;
            if (!Arrays.asList("check", "prepare", "build", "sync").contains(operation)) { stopSelf(); return START_NOT_STICKY; }
            try { recover(this); } catch (IOException e) { stopSelf(); return START_NOT_STICKY; }
            owned = true;
            cancelled = false;
        }
        String project = intent.getStringExtra("project");
        Properties state = new Properties();
        state.setProperty("id", UUID.randomUUID().toString());
        state.setProperty("operation", operation);
        state.setProperty("project", project == null ? "" : project);
        state.setProperty("state", "queued");
        try { store.write(state); }
        catch (IOException e) { synchronized (JOB_LOCK) { owned = false; } stopSelf(); return START_NOT_STICKY; }
        worker.execute(() -> {
            try { execute(operation, project, state); }
            finally { new Handler(Looper.getMainLooper()).post(() -> {
                synchronized (JOB_LOCK) { owned = false; stopSelf(); }
            }); }
        });
        return START_NOT_STICKY;
    }
    private void execute(String operation, String projectName, Properties state) {
        state.setProperty("state", "running");
        state.setProperty("started", Long.toString(System.currentTimeMillis()));
        try {
            taskDirectory = store.directory(state.getProperty("id"));
            if (!taskDirectory.mkdir()) throw new IOException("Cannot create task directory");
            pruneHistory(taskDirectory);
            store.write(state);
            if (Build.VERSION.SDK_INT < 26) throw new IOException("Native Android development tools require Android 8 or later.");
            IdeWorkspace workspace = new IdeWorkspace(new File(getFilesDir(), "home/projects"));
            File project = ("build".equals(operation) || "sync".equals(operation)) ? workspace.project(projectName) : new File(getFilesDir(), "home");
            if (!project.isDirectory()) throw new IOException("Project does not exist");
            if ("sync".equals(operation)) {
                synchronizeProject(project);
                if (cancelled) throw new InterruptedException();
                state.setProperty("state", "succeeded");
                return;
            }
            // Move all previous APK outputs outside the project before starting. Failure cannot expose them as a new result.
            if ("build".equals(operation)) {
                IdeJobStore.isolatePreviousApks(project, workspace, new File(taskDirectory, "previous-apks"));
            }
            File script = new File(taskDirectory, "toolchain.sh");
            try (InputStream in = getAssets().open("ide/toolchain.sh"); OutputStream out = new FileOutputStream(script)) { copy(in, out); }
            File runner = new File(taskDirectory, "runner.sh");
            try (OutputStream out = new FileOutputStream(runner)) {
                out.write(IdeProcessIdentity.RUNNER_SCRIPT.getBytes(StandardCharsets.UTF_8));
            }
            IdeToolchain tools = new IdeToolchain(this);
            File setsid = new File(tools.prefix, "bin/setsid");
            if (!setsid.canExecute()) setsid = new File("/system/bin/setsid");
            if (!setsid.canExecute()) throw new IOException("Missing setsid. Install the Termux util-linux package first.");
            ProcessBuilder builder = new ProcessBuilder(setsid.getPath(), new File(tools.prefix, "bin/bash").getPath(), runner.getPath(), operation);
            builder.directory(project).redirectErrorStream(true);
            builder.environment().clear(); builder.environment().putAll(tools.environment(this));
            builder.environment().put("IDE_JOB_DIR", taskDirectory.getPath());
            if (cancelled) throw new InterruptedException();
            process = builder.start();
            process.getOutputStream().close();
            if (cancelled) killWhenReady();
            try (InputStream input = process.getInputStream()) {
                File log = new File(taskDirectory, "output.log");
                OutputStream output = new FileOutputStream(log);
                try {
                    byte[] buffer = new byte[8192]; int n; long size = 0;
                    while ((n = input.read(buffer)) != -1) {
                        if (size + n > 2 * 1024 * 1024) {
                            output.close();
                            File previous = new File(taskDirectory, "output.previous.log");
                            if (previous.exists() && !previous.delete()) throw new IOException("Cannot rotate log");
                            if (!log.renameTo(previous)) throw new IOException("Cannot rotate log");
                            output = new FileOutputStream(log); size = 0;
                        }
                        output.write(buffer, 0, n); output.flush(); size += n;
                    }
                } finally { output.close(); }
            }
            int code = process.waitFor();
            state.setProperty("exit", Integer.toString(code));
            if (cancelled) state.setProperty("state", "cancelled");
            else if (code != 0) state.setProperty("state", "failed");
            else if ("build".equals(operation)) {
                List<File> apks = IdeJobStore.apks(project, workspace);
                if (apks.isEmpty()) throw new IOException("Gradle returned success but produced no APK.");
                File artifacts = new File(taskDirectory, "artifacts");
                if (!artifacts.mkdir()) throw new IOException("Cannot save APK artifacts");
                for (int i = 0; i < apks.size(); i++) {
                    File folder = new File(artifacts, Integer.toString(i));
                    if (!folder.mkdir()) throw new IOException("Cannot save APK artifact");
                    try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(apks.get(i))) {
                        if (apk.getEntry("AndroidManifest.xml") == null) throw new IOException("Invalid APK: " + apks.get(i).getName());
                    }
                    File target = new File(folder, apks.get(i).getName());
                    try (InputStream in = new FileInputStream(apks.get(i)); OutputStream out = new FileOutputStream(target)) { copy(in, out); }
                    state.setProperty("apk." + i, target.getPath());
                }
                state.setProperty("apk.count", Integer.toString(apks.size()));
                state.setProperty("state", "succeeded");
            } else state.setProperty("state", "succeeded");
        } catch (Exception e) {
            state.setProperty("state", cancelled ? "cancelled" : "failed");
            state.setProperty("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (taskDirectory != null) killGroup(taskDirectory);
            if (process != null) process.destroy();
            process = null;
            state.setProperty("finished", Long.toString(System.currentTimeMillis()));
            try { store.write(state); } catch (IOException ignored) { }
            taskDirectory = null;
        }
    }
    /** Uses the project's own Wrapper and native JDK. Never invokes bootstrap/install. */
    private void synchronizeProject(File project) throws Exception {
        IdeToolchain tools = new IdeToolchain(this);
        File java = new File(tools.javaHome, "bin/java");
        if (!java.canExecute()) throw new IOException("Missing native JDK. Prepare tools first.");
        if (!new File(project, "gradle/wrapper/gradle-wrapper.properties").isFile()
            || !new File(project, "gradle/wrapper/gradle-wrapper.jar").isFile())
            throw new IOException("Project Gradle Wrapper is incomplete.");
        File jar;
        try (IdeToolingLog preparation = new IdeToolingLog(taskDirectory)) {
            final long[] lastPercent = {-1};
            jar = IdeToolingRuntime.prepare(this,
                () -> cancelled ? com.termux.ui.components.DownloadControl.Decision.CANCEL
                    : com.termux.ui.components.DownloadControl.Decision.CONTINUE,
                (downloaded, total) -> {
                    long percent = downloaded * 100 / total;
                    if (percent == lastPercent[0]) return;
                    lastPercent[0] = percent;
                    byte[] message = ("Preparing IDE component: " + percent + "%\n").getBytes(StandardCharsets.UTF_8);
                    preparation.write(message, message.length);
                });
        }
        File runner = new File(taskDirectory, "runner.sh");
        File script = new File(taskDirectory, "toolchain.sh");
        try (OutputStream out = new FileOutputStream(runner)) { out.write(IdeProcessIdentity.RUNNER_SCRIPT.getBytes(StandardCharsets.UTF_8)); }
        try (OutputStream out = new FileOutputStream(script)) {
            out.write(("set -eu\nexec " + IdeToolchain.quote(java.getPath()) + " -Xmx512m -jar "
                + IdeToolchain.quote(jar.getPath()) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        File setsid = new File(tools.prefix, "bin/setsid");
        if (!setsid.canExecute()) setsid = new File("/system/bin/setsid");
        if (!setsid.canExecute()) throw new IOException("Missing setsid. Install Termux util-linux.");
        ProcessBuilder builder = new ProcessBuilder(setsid.getPath(), new File(tools.prefix, "bin/bash").getPath(), runner.getPath());
        builder.directory(project);
        builder.environment().clear(); builder.environment().putAll(tools.environment(this));
        builder.environment().put("IDE_JOB_DIR", taskDirectory.getPath());
        if (cancelled) throw new InterruptedException();
        process = builder.start();
        try (IdeToolingLog log = new IdeToolingLog(taskDirectory)) {
            java.lang.Process child = process;
            Thread stderr = new Thread(() -> {
                try (InputStream input = child.getErrorStream()) {
                    byte[] bytes = new byte[4096]; int n;
                    while ((n = input.read(bytes)) != -1) log.write(bytes, n);
                } catch (IOException ignored) { }
            }, "ide-tooling-stderr");
            stderr.setDaemon(true); stderr.start();
            try (IdeToolingRpc rpc = new IdeToolingRpc(child.getInputStream(), child.getOutputStream(), new IdeToolingRpc.Client() {
                @Override public Object request(String method, Object params) throws Exception {
                    if (method.equals("client/checkGradleWrapperAvailability"))
                        return new org.json.JSONObject().put("isAvailable", true);
                    if (method.equals("client/getBuildArguments")) return new org.json.JSONArray()
                        .put("--console=plain").put("--max-workers=2").put("--no-watch-fs")
                        .put("-Pandroid.aapt2FromMavenOverride=" + tools.aapt2.getPath())
                        .put("-Dorg.gradle.java.home=" + tools.javaHome.getPath());
                    throw new IOException("Unsupported tooling callback: " + method);
                }
                @Override public void notification(String method, Object params) {
                    if (method.equals("client/logOutput") || method.equals("client/logMessage")) {
                        try { byte[] bytes = (String.valueOf(params) + "\n").getBytes(StandardCharsets.UTF_8); log.write(bytes, bytes.length); }
                        catch (IOException e) { child.destroy(); }
                    }
                }
            })) {
                toolingRpc = rpc;
                try {
                    if (cancelled) throw new InterruptedException();
                    rpc.call("server/metadata", null, 30);
                    org.json.JSONObject params = new org.json.JSONObject().put("directory", project.getCanonicalPath())
                        .put("gradleDistribution", new org.json.JSONObject().put("type", "GRADLE_WRAPPER").put("value", ""))
                        .put("androidParams", new org.json.JSONObject().put("variantSelections", new org.json.JSONObject()));
                    Object initialized = rpc.call("server/initialize", params, 900);
                    if (!(initialized instanceof org.json.JSONObject) || !((org.json.JSONObject) initialized).optBoolean("isSuccessful"))
                        throw new IOException("Gradle synchronization failed: " + initialized);
                    org.json.JSONObject model = IdeToolingModel.read((method, arguments) -> {
                        if (cancelled) throw new InterruptedException();
                        return rpc.call(method, arguments, 90);
                    }, project.getCanonicalPath());
                    if (cancelled) throw new InterruptedException();
                    model.put("languageContexts", new IdeModuleContexts(model).toJson());
                    FileOutputStream output = null;
                    android.util.AtomicFile file = new android.util.AtomicFile(new File(taskDirectory, "project-model.json"));
                    try {
                        output = file.startWrite(); output.write(model.toString(2).getBytes(StandardCharsets.UTF_8)); file.finishWrite(output);
                    } catch (Exception e) { file.failWrite(output); throw e; }
                    new IdeProjectModelStore(getFilesDir()).write(project, model);
                    String summary = "Gradle project synchronized: " + model.getJSONArray("modules").length() + " modules.\n";
                    byte[] bytes = summary.getBytes(StandardCharsets.UTF_8); log.write(bytes, bytes.length);
                } finally {
                    try { rpc.call("server/shutdown", null, 3); } catch (Exception ignored) { }
                    toolingRpc = null;
                }
            }
        }
    }
    private void killWhenReady() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            File directory = taskDirectory;
            if (directory != null && new File(directory, "pid").isFile()) { killGroup(directory); return; }
            if (!owned) return;
            Thread.sleep(25);
        }
    }
    /** Validate this task's session leader before signalling its private process group. */
    private static void killGroup(File task) {
        try {
            int pid = Integer.parseInt(readSmall(new File(task, "pid")).trim());
            if (pid <= 1) return;
            String command = readSmall(new File("/proc/" + pid + "/cmdline"));
            String stat = readSmall(new File("/proc/" + pid + "/stat"));
            if (IdeProcessIdentity.isTaskLeader(pid, command, stat, new File(task, "runner.sh")))
                Os.kill(-pid, OsConstants.SIGKILL);
        } catch (Exception ignored) { }
    }
    private static String readSmall(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { byte[] bytes = new byte[16384]; int n = in.read(bytes); return n <= 0 ? "" : new String(bytes, 0, n, StandardCharsets.UTF_8); }
    }
    private void pruneHistory(File current) throws IOException {
        File[] dirs = store.root().listFiles(File::isDirectory);
        if (dirs == null) return;
        Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = 8; i < dirs.length; i++) if (!dirs[i].equals(current)) deletePrivate(dirs[i]);
    }
    private static void deletePrivate(File file) throws IOException {
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) { if (!file.delete()) throw new IOException("Cannot remove link"); return; }
        if (file.isDirectory()) for (File child : Optional.ofNullable(file.listFiles()).orElse(new File[0])) deletePrivate(child);
        if (!file.delete()) throw new IOException("Cannot prune old task");
    }
    private static void copy(InputStream in, OutputStream out) throws IOException { byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
    @Override public void onDestroy() { cancelled = true; if (taskDirectory != null) killGroup(taskDirectory); worker.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
