package com.termux.ui.components.install;

import com.termux.ui.domain.ComponentTask;

public interface ComponentInstallListener {
    ComponentInstallListener NONE = task -> { };
    void onTaskUpdated(ComponentTask task);
}
