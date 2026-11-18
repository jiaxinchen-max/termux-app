package com.termux.ui.files;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Preflight the entire selection, then stop on the first error with an explicit outcome. */
public final class BrowserBatchOperations {
    public enum Action { COPY, MOVE, DELETE }

    public static final class Result {
        public final List<File> completed;
        public final File failed;
        public final List<File> unattempted;
        public final Exception error;
        public final boolean preflightFailure;

        private Result(List<File> completed, File failed, List<File> unattempted,
                       Exception error, boolean preflightFailure) {
            this.completed = Collections.unmodifiableList(new ArrayList<>(completed));
            this.failed = failed;
            this.unattempted = Collections.unmodifiableList(new ArrayList<>(unattempted));
            this.error = error;
            this.preflightFailure = preflightFailure;
        }

        public boolean isSuccess() { return error == null; }
        public boolean needsManualCheck() {
            String message = error == null ? "" : String.valueOf(error.getMessage());
            return message.startsWith("move_source_delete_failed")
                || message.startsWith("delete_failed_may_be_partial");
        }
    }

    private final BrowserFileOperations files;
    public BrowserBatchOperations(BrowserFileOperations files) { this.files = files; }

    public Result run(List<File> selection, File destination, Action action) {
        List<File> sources = new ArrayList<>(selection);
        List<File> completed = new ArrayList<>();
        File checking = null;
        try {
            if (sources.isEmpty()) throw new IOException("empty_selection");
            Set<String> targets = new HashSet<>();
            List<File> checked = new ArrayList<>();
            for (File source : sources) {
                checking = source;
                if (Thread.currentThread().isInterrupted()) throw new IOException("operation_interrupted");
                File path = files.validateDelete(source);
                for (File other : checked) {
                    if (BrowserFileOperations.contains(path, other) || BrowserFileOperations.contains(other, path))
                        throw new IOException("overlapping_sources: " + path);
                }
                checked.add(path);
                if (action != Action.DELETE) {
                    File target = files.validatePaste(source, destination);
                    if (!targets.add(target.getPath())) throw new IOException("destination_exists: " + target);
                }
            }
        } catch (IOException | RuntimeException error) {
            List<File> unattempted = new ArrayList<>(sources);
            unattempted.remove(checking);
            return new Result(completed, checking, unattempted, error, true);
        }
        for (int i = 0; i < sources.size(); i++) {
            File source = sources.get(i);
            try {
                if (action == Action.DELETE) files.delete(source);
                else files.paste(source, destination, action == Action.MOVE);
                completed.add(source);
            } catch (IOException | RuntimeException error) {
                return new Result(completed, source, sources.subList(i + 1, sources.size()), error, false);
            }
        }
        return new Result(completed, null, Collections.emptyList(), null, false);
    }
}
