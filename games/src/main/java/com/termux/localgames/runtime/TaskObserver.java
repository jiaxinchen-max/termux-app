package com.termux.localgames.runtime;

public interface TaskObserver<T> {
    void onChanged(T value);
    void onError(Throwable error);
}
