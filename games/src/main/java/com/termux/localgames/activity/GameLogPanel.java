package com.termux.localgames.activity;

import android.app.Activity;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.localgames.R;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * The in-game "Logs" page: the tail of the current launch log (see {@link GameLogTailReader}) in a
 * list with line numbers and level highlighting (see {@link GameLogAdapter}).
 *
 * <p>Older lines are paged in by scrolling: when the user scrolls to the top of the list and the
 * file has earlier lines, the previous chunk is read and put in front of the list while the visible
 * row stays where it is. At most {@link #MAX_BUFFERED_LINES} lines are kept; at that cap (or at the
 * file start) paging stops and the status line says so, and Refresh returns to the latest lines.</p>
 *
 * <p>The file is read on the host's I/O executor and delivered on the UI thread; a result that is
 * no longer wanted (page hidden, newer load started, Activity gone) is dropped. The log is loaded
 * when the page is shown and on Refresh; new lines are not streamed.</p>
 */
final class GameLogPanel {

    /** Lines read per backward page. */
    static final int EARLIER_CHUNK_LINES = 2000;
    /** Upper bound on the lines held in memory (each is cut at {@link GameLogTailReader#MAX_LINE_BYTES}). */
    static final int MAX_BUFFERED_LINES = 20_000;

    private final Activity activity;
    private final ExecutorService io;
    private final ListView list;
    private final TextView status;
    private final GameLogAdapter adapter;

    @NonNull private String logRef = "";
    private boolean shown;
    private boolean released;
    /** Bumped to invalidate loads in flight. */
    private int generation;

    @NonNull private List<GameLogLine> lines = Collections.emptyList();
    private int totalLines;
    /** Byte offset where the first buffered line starts; 0 once line 1 is buffered. */
    private long startOffset;
    private boolean loadingEarlier;
    /** Set after a failed backward read so scrolling does not retry in a loop; cleared by Refresh. */
    private boolean earlierBlocked;
    /** True once the user has scrolled the list; programmatic positioning never pages. */
    private boolean userScrolled;

    GameLogPanel(@NonNull Activity activity, @NonNull ExecutorService io, @NonNull ListView list,
                 @NonNull TextView status, @NonNull View refreshButton) {
        this.activity = activity;
        this.io = io;
        this.list = list;
        this.status = status;
        adapter = new GameLogAdapter(activity);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState != SCROLL_STATE_IDLE) userScrolled = true;
                else loadEarlierIfAtTop();
            }

            @Override public void onScroll(AbsListView view, int firstVisibleItem,
                                           int visibleItemCount, int totalItemCount) {
                if (firstVisibleItem == 0) loadEarlierIfAtTop();
            }
        });
        refreshButton.setOnClickListener(view -> load());
    }

    /** The log file of the current launch task; empty/null until the task has produced one. */
    void setLogRef(@Nullable String newLogRef) {
        String value = newLogRef == null ? "" : newLogRef;
        if (value.equals(logRef)) return;
        logRef = value;
        if (shown) load();
    }

    /** The page became visible (true) or not (false); (re)loads the log on becoming visible. */
    void setShown(boolean visible) {
        if (visible == shown) return;
        shown = visible;
        if (visible) {
            load();
        } else {
            generation++; // a read that finishes now would only update a hidden page
            loadingEarlier = false;
        }
    }

    /** The Activity is going away: no result may be delivered any more. */
    void release() {
        released = true;
        generation++;
    }

    /** Loads the latest lines, dropping whatever older lines were paged in. */
    private void load() {
        if (released) return;
        final int expected = ++generation;
        loadingEarlier = false;
        if (logRef.isEmpty()) {
            showLines(Collections.<GameLogLine>emptyList(), 0, 0L);
            showStatus(activity.getString(R.string.local_game_session_log_pending));
            return;
        }
        final File file = new File(logRef);
        try {
            io.execute(() -> {
                GameLogTailReader.Result result = null;
                try {
                    result = GameLogTailReader.read(file, GameLogTailReader.DEFAULT_MAX_LINES);
                } catch (IOException | RuntimeException ignored) {
                    // Reported below as the read-failed state.
                }
                final GameLogTailReader.Result loaded = result;
                activity.runOnUiThread(() -> {
                    if (released || expected != generation) return;
                    if (loaded == null) {
                        showLines(Collections.<GameLogLine>emptyList(), 0, 0L);
                        showStatus(activity.getString(R.string.local_game_session_log_read_failed));
                    } else {
                        showLines(loaded.lines, loaded.totalLines, loaded.startOffset);
                    }
                });
            });
        } catch (RejectedExecutionException shuttingDown) {
            // Activity is closing; nothing to show.
        }
    }

    private void showLines(@NonNull List<GameLogLine> newLines, int total, long newStartOffset) {
        lines = newLines;
        totalLines = total;
        startOffset = newStartOffset;
        earlierBlocked = false;
        userScrolled = false;
        adapter.setLines(newLines);
        if (newLines.isEmpty()) showStatus(activity.getString(R.string.local_game_session_log_empty));
        else showRangeStatus();
        final int last = newLines.size() - 1;
        if (last >= 0) list.post(() -> list.setSelection(last));
    }

    /** Pages in the previous chunk when the list sits at its top and there is something earlier. */
    private void loadEarlierIfAtTop() {
        if (released || !shown || !userScrolled || loadingEarlier || earlierBlocked) return;
        if (startOffset <= 0L || lines.isEmpty() || lines.size() >= MAX_BUFFERED_LINES) return;
        if (list.getFirstVisiblePosition() != 0 || logRef.isEmpty()) return;

        loadingEarlier = true;
        showStatus(activity.getString(R.string.local_game_session_log_loading_earlier));
        final int expected = generation;
        final long before = startOffset;
        final int nextNumber = lines.get(0).number;
        final int count = Math.min(EARLIER_CHUNK_LINES, MAX_BUFFERED_LINES - lines.size());
        final File file = new File(logRef);
        try {
            io.execute(() -> {
                GameLogTailReader.Page page = null;
                try {
                    page = GameLogTailReader.readBefore(file, before, nextNumber, count);
                } catch (IOException | RuntimeException ignored) {
                    // Reported below as the read-failed state.
                }
                final GameLogTailReader.Page loaded = page;
                activity.runOnUiThread(() -> {
                    if (released || expected != generation) return;
                    loadingEarlier = false;
                    if (loaded == null) {
                        earlierBlocked = true;
                        showStatus(activity.getString(R.string.local_game_session_log_read_failed));
                    } else {
                        prepend(loaded);
                    }
                });
            });
        } catch (RejectedExecutionException shuttingDown) {
            loadingEarlier = false;
        }
    }

    /** Puts the page in front of the list; the row at the top of the screen stays where it is. */
    private void prepend(@NonNull GameLogTailReader.Page page) {
        startOffset = page.startOffset;
        if (!page.lines.isEmpty()) {
            int firstVisible = list.getFirstVisiblePosition();
            View firstRow = list.getChildAt(0);
            int top = firstRow == null ? 0 : firstRow.getTop();
            List<GameLogLine> merged = new ArrayList<>(page.lines.size() + lines.size());
            merged.addAll(page.lines);
            merged.addAll(lines);
            lines = merged;
            adapter.setLines(merged);
            list.setSelectionFromTop(firstVisible + page.lines.size(), top);
        }
        showRangeStatus();
    }

    /** Which lines are shown, or nothing when the whole file is buffered from its first line. */
    private void showRangeStatus() {
        if (lines.isEmpty() || startOffset <= 0L) {
            showStatus(null);
            return;
        }
        int first = lines.get(0).number;
        int last = lines.get(lines.size() - 1).number;
        showStatus(activity.getString(lines.size() >= MAX_BUFFERED_LINES
            ? R.string.local_game_session_log_buffer_limit
            : R.string.local_game_session_log_range, first, last, totalLines));
    }

    private void showStatus(@Nullable String message) {
        status.setText(message == null ? "" : message);
    }
}
