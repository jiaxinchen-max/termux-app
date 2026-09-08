package com.termux.localgames.components.install;

import com.termux.localgames.domain.ComponentTask;

public interface ComponentInstallListener {
    ComponentInstallListener NONE = task -> { };
    void onTaskUpdated(ComponentTask task);
}
