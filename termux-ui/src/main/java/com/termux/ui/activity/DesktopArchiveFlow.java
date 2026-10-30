package com.termux.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.termux.ui.R;
import com.termux.ui.api.DesktopArchiveTasks;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopRunMode;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UI observer for the application-owned archive service. Detaching never cancels its task. */
final class DesktopArchiveFlow {
    interface Observer {
        void onSnapshot(@Nullable DesktopArchiveTasks.Snapshot snapshot, boolean preparing);
    }

    private interface Request { void start(DesktopArchiveTasks tasks) throws IOException; }

    private static final DesktopArchiveFlow INSTANCE = new DesktopArchiveFlow();
    private static final String EXTENSION = ".desktop.tar.gz";
    private static final String ACKNOWLEDGED = "acknowledged_task";
    private static final String PENDING_ERROR = "pending_error";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "DesktopArchiveUi"));
    private final Runnable poll = this::readSnapshot;
    @Nullable private Activity host;
    @Nullable private Observer observer;
    @Nullable private Runnable refresh;
    @Nullable private DesktopArchiveTasks tasks;
    @Nullable private SharedPreferences preferences;
    @Nullable private DesktopArchiveTasks.Snapshot snapshot;
    @Nullable private SharedStorageChooserDialog chooser;
    @Nullable private AlertDialog confirmation;
    @Nullable private AlertDialog progress;
    @Nullable private AlertDialog failure;
    @Nullable private TextView logView;
    @Nullable private ScrollView logScroll;
    private boolean preparing;
    private boolean wantProgress;
    private long attachment;
    private String lastRefreshTask = "";
    private String displayedLog = "";

    static DesktopArchiveFlow instance() { return INSTANCE; }

    private DesktopArchiveFlow() { }

    void attach(Activity activity, Observer listener, Runnable onFinished) {
        if (host != null) detach(host);
        host = activity;
        observer = listener;
        refresh = onFinished;
        Context app = activity.getApplicationContext();
        tasks = new DesktopArchiveTasks(app);
        preferences = app.getSharedPreferences("desktop_archive_ui", Context.MODE_PRIVATE);
        wantProgress = true;
        listener.onSnapshot(snapshot, preparing);
        readSnapshot();
    }

    void detach(Activity activity) {
        if (host != activity) return;
        attachment++;
        handler.removeCallbacks(poll);
        if (chooser != null) chooser.dismiss();
        chooser = null;
        if (confirmation != null) confirmation.dismiss();
        confirmation = null;
        if (progress != null) progress.dismiss();
        progress = null;
        logView = null;
        logScroll = null;
        if (failure != null) failure.dismiss();
        failure = null;
        host = null;
        observer = null;
        refresh = null;
    }

    boolean isBusy() { return preparing || DesktopArchiveTasks.isBusy(); }

    void startImport() {
        Activity activity = host;
        if (activity == null || refuseBusy()) return;
        chooser = SharedStorageChooserDialog.chooseArchive(activity,
            Environment.getExternalStorageDirectory(), EXTENSION, file -> {
                chooser = null;
                if (host != activity) return;
                confirmation = new FrostedDialogBuilder(activity)
                    .setTitle(R.string.desktop_archive_import)
                    .setMessage(activity.getString(R.string.desktop_archive_import_scope)
                        + "\n\n" + file.getAbsolutePath())
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.desktop_archive_import,
                        (dialog, which) -> begin(current -> current.importDesktop(file)))
                    .setOnDismissListener(dialog -> confirmation = null)
                    .show();
            });
    }

    void startExport(DesktopDistro distro, boolean active) {
        Activity activity = host;
        if (activity == null) return;
        if (active || distro.getRunMode() != DesktopRunMode.PROOT) {
            toast(R.string.desktop_archive_running_forbidden);
            return;
        }
        if (refuseBusy()) return;
        confirmation = new FrostedDialogBuilder(activity)
            .setTitle(R.string.desktop_archive_export)
            .setMessage(R.string.desktop_archive_export_scope)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.desktop_archive_export, (dialog, which) -> {
                String date = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                chooser = SharedStorageChooserDialog.chooseNewSaveFile(activity,
                    Environment.getExternalStorageDirectory(),
                    distro.getId() + "-" + date + EXTENSION, EXTENSION, target -> {
                        chooser = null;
                        if (host != activity) return;
                        // Recheck after the chooser closes; the worker also refuses races.
                        if (target.exists()) {
                            toast(R.string.desktop_archive_destination_exists);
                            return;
                        }
                        begin(current -> current.exportDesktop(distro.getId(), target));
                    });
            })
            .setOnDismissListener(dialog -> confirmation = null)
            .show();
    }

    private boolean refuseBusy() {
        if (!isBusy()) return false;
        toast(R.string.desktop_archive_busy);
        showProgress();
        return true;
    }

    private void begin(Request request) {
        DesktopArchiveTasks current = tasks;
        SharedPreferences prefs = preferences;
        if (current == null || prefs == null || refuseBusy()) return;
        preparing = true;
        wantProgress = true;
        publish();
        executor.execute(() -> {
            try {
                request.start(current);
            } catch (IOException | RuntimeException error) {
                String message = error.getMessage();
                prefs.edit().putString(PENDING_ERROR,
                    message == null || message.isEmpty() ? error.toString() : message).apply();
            }
            handler.post(() -> {
                preparing = false;
                publish();
                handler.removeCallbacks(poll);
                readSnapshot();
            });
        });
    }

    private void readSnapshot() {
        DesktopArchiveTasks current = tasks;
        if (host == null || current == null) return;
        long generation = attachment;
        executor.execute(() -> {
            DesktopArchiveTasks.Snapshot result = current.snapshot();
            handler.post(() -> {
                if (host == null || generation != attachment) return;
                snapshot = result;
                publish();
                showPendingError();
                if (!preparing && result.status != DesktopArchiveTasks.Status.IDLE) {
                    if (isTerminal(result) && !taskId(result).equals(lastRefreshTask)) {
                        lastRefreshTask = taskId(result);
                        if (refresh != null) refresh.run();
                    }
                    boolean unacknowledged = isTerminal(result) && preferences != null
                        && !taskId(result).equals(preferences.getString(ACKNOWLEDGED, ""));
                    if (failure == null && ((wantProgress
                        && result.status == DesktopArchiveTasks.Status.RUNNING)
                        || unacknowledged)) showProgress();
                    updateProgress();
                }
                handler.removeCallbacks(poll);
                handler.postDelayed(poll, 750);
            });
        });
    }

    private void publish() {
        if (observer != null) observer.onSnapshot(snapshot, preparing);
    }

    void showProgress() {
        Activity activity = host;
        DesktopArchiveTasks.Snapshot current = snapshot;
        if (activity == null || current == null
            || current.status == DesktopArchiveTasks.Status.IDLE) return;
        wantProgress = false;
        if (progress != null) { updateProgress(); return; }
        logView = new TextView(activity);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        logView.setPadding(padding, padding / 2, padding, padding / 2);
        logScroll = new ScrollView(activity);
        logScroll.addView(logView);
        ConfigurationAwareFrameLayout root = new ConfigurationAwareFrameLayout(activity);
        root.addView(logScroll, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progress = new FrostedDialogBuilder(activity)
            .setTitle(R.string.desktop_archive_task_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> acknowledge())
            .setOnCancelListener(dialog -> acknowledge())
            .setOnDismissListener(dialog -> {
                progress = null;
                logView = null;
                logScroll = null;
                displayedLog = "";
            })
            .show();
        Runnable resize = () -> {
            if (progress == null) return;
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            root.setMaxHeightPx(DialogSizing.listHeightPx(metrics.heightPixels, metrics.density));
            Window window = progress.getWindow();
            if (window != null) window.setLayout(
                DialogSizing.maxWidthPx(metrics.widthPixels, metrics.density),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        };
        root.setListener(configuration -> resize.run());
        resize.run();
        updateProgress();
    }

    private void updateProgress() {
        Activity activity = host;
        DesktopArchiveTasks.Snapshot current = snapshot;
        if (activity == null || current == null || progress == null || logView == null) return;
        if (current.status == DesktopArchiveTasks.Status.SUCCEEDED) {
            progress.setTitle(R.string.desktop_archive_succeeded);
        } else if (current.status == DesktopArchiveTasks.Status.FAILED) {
            progress.setTitle(R.string.desktop_archive_failed);
        } else if (current.message != null && !current.message.isEmpty()) {
            progress.setTitle(localize(activity, current.message));
        } else {
            progress.setTitle(R.string.desktop_archive_running);
        }
        progress.getButton(AlertDialog.BUTTON_POSITIVE).setText(isTerminal(current)
            ? android.R.string.ok : R.string.desktop_archive_background);
        String content = statusText(activity, current);
        if (current.log != null && !current.log.isEmpty()) content += "\n\n" + current.log;
        if (!content.equals(displayedLog)) {
            displayedLog = content;
            logView.setText(content);
            if (logScroll != null) {
                ScrollView scroll = logScroll;
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private void acknowledge() {
        if (snapshot != null && isTerminal(snapshot) && preferences != null) {
            preferences.edit().putString(ACKNOWLEDGED, taskId(snapshot)).apply();
        }
    }

    private void showPendingError() {
        Activity activity = host;
        SharedPreferences prefs = preferences;
        if (activity == null || prefs == null || failure != null) return;
        String message = prefs.getString(PENDING_ERROR, "");
        if (message == null || message.isEmpty()) return;
        failure = new FrostedDialogBuilder(activity)
            .setTitle(R.string.desktop_archive_failed)
            .setMessage(localize(activity, message))
            .setPositiveButton(android.R.string.ok,
                (dialog, which) -> prefs.edit().remove(PENDING_ERROR).apply())
            .setOnCancelListener(dialog -> prefs.edit().remove(PENDING_ERROR).apply())
            .setOnDismissListener(dialog -> failure = null)
            .show();
    }

    static String statusText(Context context, @Nullable DesktopArchiveTasks.Snapshot snapshot) {
        if (snapshot == null || snapshot.status == DesktopArchiveTasks.Status.IDLE) return "";
        int label = snapshot.status == DesktopArchiveTasks.Status.RUNNING
            ? R.string.desktop_archive_running
            : snapshot.status == DesktopArchiveTasks.Status.SUCCEEDED
                ? R.string.desktop_archive_succeeded : R.string.desktop_archive_failed;
        String message = snapshot.message;
        if (message != null) message = localize(context, message);
        if (context.getString(label).equals(message)) return message;
        return context.getString(label)
            + (message == null || message.isEmpty() ? "" : "\n" + message);
    }

    private static boolean isTerminal(DesktopArchiveTasks.Snapshot snapshot) {
        return snapshot.status == DesktopArchiveTasks.Status.SUCCEEDED
            || snapshot.status == DesktopArchiveTasks.Status.FAILED;
    }

    private static String localize(Context context, String message) {
        if (!message.startsWith("desktop_archive_")) return message;
        int resource = context.getResources().getIdentifier(message, "string", context.getPackageName());
        return resource == 0 ? message : context.getString(resource);
    }

    private static String taskId(DesktopArchiveTasks.Snapshot snapshot) {
        return snapshot.taskId == null ? "" : snapshot.taskId;
    }

    private void toast(int message) {
        if (host != null) Toast.makeText(host, message, Toast.LENGTH_LONG).show();
    }
}
