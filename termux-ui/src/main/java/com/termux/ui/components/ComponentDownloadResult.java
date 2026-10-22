package com.termux.ui.components;

import com.termux.ui.domain.ComponentTask;

import java.io.File;

public final class ComponentDownloadResult {

    private final ComponentTask task;
    private final File artifact;

    ComponentDownloadResult(ComponentTask task, File artifact) {
        this.task = task;
        this.artifact = artifact;
    }

    public ComponentTask getTask() {
        return task;
    }

    public File getArtifact() {
        return artifact;
    }
}
