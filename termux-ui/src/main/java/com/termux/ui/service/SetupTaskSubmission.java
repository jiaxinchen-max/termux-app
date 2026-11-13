package com.termux.ui.service;

import java.io.IOException;

/** Publishes an installation identity before asynchronous dispatch can expose a second caller. */
public final class SetupTaskSubmission {
    private SetupTaskSubmission() { }
    public interface Read<T> { T get() throws IOException; }
    public interface Write<T> { void accept(T task) throws IOException; }

    public static <T> T submit(Object lock, Read<T> active, Read<T> create,
                               Write<T> persist, Write<T> dispatch, Write<T> reject) throws IOException {
        synchronized (lock) {
            T existing = active.get();
            if (existing != null) return existing;
            T task = create.get();
            persist.accept(task);
            try {
                dispatch.accept(task);
            } catch (IOException | RuntimeException error) {
                try { reject.accept(task); }
                catch (IOException | RuntimeException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
            return task;
        }
    }
}
