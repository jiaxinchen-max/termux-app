package com.termux.localgames.recovery;

import java.io.IOException;

/** Serializes private asset publication/deletion inside the app process. */
public final class GameAssetMutationLock {

    private static final Object MONITOR = new Object();

    private GameAssetMutationLock() {}

    public static <T> T call(IoCallable<T> operation) throws IOException {
        if (operation == null) throw new IllegalArgumentException("operation required");
        synchronized (MONITOR) {
            return operation.call();
        }
    }

    public interface IoCallable<T> {
        T call() throws IOException;
    }
}
