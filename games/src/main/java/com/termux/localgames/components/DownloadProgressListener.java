package com.termux.localgames.components;

import com.termux.localgames.domain.ComponentTask;

public interface DownloadProgressListener {
    DownloadProgressListener NONE = task -> { };

    void onTaskUpdated(ComponentTask task);
}
