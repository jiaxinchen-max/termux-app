package com.termux.localgames.components.catalog;

import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.install.InstalledComponent;
import com.termux.localgames.domain.ComponentTask;

import java.util.Optional;

public final class ComponentCatalogItem {

    private final ComponentDescriptor descriptor;
    private final ComponentTask task;
    private final InstalledComponent active;
    private final InstalledComponent previous;
    private final ComponentCatalogState state;

    ComponentCatalogItem(ComponentDescriptor descriptor, ComponentTask task,
                         InstalledComponent active, InstalledComponent previous,
                         ComponentCatalogState state) {
        this.descriptor = descriptor;
        this.task = task;
        this.active = active;
        this.previous = previous;
        this.state = state;
    }

    public ComponentDescriptor getDescriptor() { return descriptor; }
    public Optional<ComponentTask> getTask() { return Optional.ofNullable(task); }
    public Optional<InstalledComponent> getActive() { return Optional.ofNullable(active); }
    public Optional<InstalledComponent> getPrevious() { return Optional.ofNullable(previous); }
    public ComponentCatalogState getState() { return state; }

    public long getDownloadedBytes() {
        return task == null ? 0 : task.getDownloadedBytes();
    }

    public int getProgressPercent() {
        if (task == null) return 0;
        return (int) Math.min(100,
            task.getDownloadedBytes() * 100L / task.getExpectedSize());
    }
}
