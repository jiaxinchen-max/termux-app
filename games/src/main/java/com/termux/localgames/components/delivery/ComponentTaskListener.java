package com.termux.localgames.components.delivery;

import com.termux.localgames.domain.ComponentTask;

public interface ComponentTaskListener {
    ComponentTaskListener NONE = task -> { };
    void onTaskUpdated(ComponentTask task);
}
