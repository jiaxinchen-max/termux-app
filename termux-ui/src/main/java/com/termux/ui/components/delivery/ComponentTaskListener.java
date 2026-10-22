package com.termux.ui.components.delivery;

import com.termux.ui.domain.ComponentTask;

public interface ComponentTaskListener {
    ComponentTaskListener NONE = task -> { };
    void onTaskUpdated(ComponentTask task);
}
