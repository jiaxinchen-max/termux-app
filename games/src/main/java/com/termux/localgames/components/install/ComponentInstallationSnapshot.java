package com.termux.localgames.components.install;

import java.util.Optional;

/** Read-only active/previous view used by component management UI. */
public final class ComponentInstallationSnapshot {

    private final InstalledComponent active;
    private final InstalledComponent previous;

    ComponentInstallationSnapshot(InstalledComponent active, InstalledComponent previous) {
        this.active = active;
        this.previous = previous;
    }

    public Optional<InstalledComponent> getActive() { return Optional.ofNullable(active); }
    public Optional<InstalledComponent> getPrevious() { return Optional.ofNullable(previous); }
}
