package com.termux.localgames.runtime;

/** Idempotent observer registration handle owned by the UI lifecycle. */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
