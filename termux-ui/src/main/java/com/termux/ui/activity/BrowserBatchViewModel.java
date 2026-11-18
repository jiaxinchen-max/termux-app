package com.termux.ui.activity;

import android.app.Application;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.termux.ui.files.BrowserBatchOperations;
import com.termux.ui.files.BrowserFileOperations;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns one batch across Activity recreation; it never retains a View or Activity. */
public final class BrowserBatchViewModel extends AndroidViewModel {
    private static final Object JOURNAL_LOCK = new Object();
    private static BrowserBatchViewModel activeBatch;
    static final class Snapshot {
        final List<File> sources;
        final File destination;
        final BrowserBatchOperations.Action action;
        final BrowserBatchOperations.Result result;
        Snapshot(List<File> sources, File destination, BrowserBatchOperations.Action action,
                 BrowserBatchOperations.Result result) {
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
            this.destination = destination;
            this.action = action;
            this.result = result;
        }
    }

    final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "GamesFileManagerIo"));
    private final MutableLiveData<Snapshot> state = new MutableLiveData<>();
    private final SharedPreferences journal;
    private boolean busy;
    private boolean cleared;
    private boolean finished;
    private String interrupted;

    public BrowserBatchViewModel(@NonNull Application application) {
        super(application);
        journal = application.getSharedPreferences("file_browser_batch", 0);
        synchronized (JOURNAL_LOCK) {
            // A second browser in this process must not mislabel or clear a live request.
            interrupted = activeBatch == null ? journal.getString("pending", null) : null;
        }
    }

    LiveData<Snapshot> state() { return state; }
    boolean isBusy() { return busy; }

    String takeInterrupted() {
        synchronized (JOURNAL_LOCK) {
            if (interrupted == null || activeBatch != null) return null;
            String previous = interrupted;
            interrupted = null;
            journal.edit().remove("pending").commit();
            return previous;
        }
    }

    void start(BrowserFileOperations files, List<File> sources, File destination,
               BrowserBatchOperations.Action action) throws IOException {
        if (busy) return;
        List<File> snapshot = new ArrayList<>(sources);
        StringBuilder detail = new StringBuilder(action.name());
        if (destination != null) detail.append("\n→ ").append(destination.getPath());
        for (File source : snapshot) detail.append('\n').append(source.getPath());
        synchronized (JOURNAL_LOCK) {
            if (activeBatch != null || journal.contains("pending")) throw new IOException("batch_request_pending");
            if (!journal.edit().putString("pending", detail.toString()).commit())
                throw new IOException("batch_request_save_failed");
            activeBatch = this;
            busy = true;
            finished = false;
        }
        state.setValue(new Snapshot(snapshot, destination, action, null));
        executor.execute(() -> {
            BrowserBatchOperations.Result result;
            try {
                result = new BrowserBatchOperations(files).run(snapshot, destination, action);
            } finally {
                synchronized (JOURNAL_LOCK) {
                    finished = true;
                    if (cleared && activeBatch == this) activeBatch = null;
                }
            }
            state.postValue(new Snapshot(snapshot, destination, action, result));
        });
    }

    void consume(Snapshot snapshot) {
        if (state.getValue() != snapshot) return;
        synchronized (JOURNAL_LOCK) {
            journal.edit().remove("pending").commit();
            if (activeBatch == this) activeBatch = null;
            busy = false;
        }
        state.setValue(null);
    }

    @Override protected void onCleared() {
        synchronized (JOURNAL_LOCK) {
            cleared = true;
            if (finished && activeBatch == this) activeBatch = null;
        }
        // Finish the already authorized batch even if its UI is closed. Leave its journal
        // for the next browser to report; never enable a second writer while it is running.
        executor.shutdown();
    }
}
