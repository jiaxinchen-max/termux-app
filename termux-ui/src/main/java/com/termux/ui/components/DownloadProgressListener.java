package com.termux.ui.components;

import com.termux.ui.domain.ComponentTask;

public interface DownloadProgressListener {
    DownloadProgressListener NONE = task -> { };

    void onTaskUpdated(ComponentTask task);
}
