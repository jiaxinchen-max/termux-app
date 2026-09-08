package com.termux.localgames.components;

import com.termux.localgames.domain.ComponentTask;

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
