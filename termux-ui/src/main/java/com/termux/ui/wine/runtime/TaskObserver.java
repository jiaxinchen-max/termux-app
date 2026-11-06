package com.termux.ui.wine.runtime;

public interface TaskObserver<T> {
    void onChanged(T value);
    void onError(Throwable error);
}
