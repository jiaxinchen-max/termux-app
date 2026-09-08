package com.termux.localgames.components.install;

import com.termux.localgames.domain.ComponentTask;

import java.io.File;
import java.io.IOException;

/**
 * Publishes a verified component into the runtime that game launches actually read.
 *
 * <p>The games module keeps its own transactional install tree, but the launcher consumes the
 * host runtime prefix. This hook runs after the version directory is published and before the
 * task reaches its terminal state, so a component that cannot be made visible to the launcher
 * fails the task instead of reporting a misleading success.
 */
public interface RuntimeComponentActivator {

    /** Activator that performs no work; the component stays private to the games module. */
    RuntimeComponentActivator NONE = (task, preparedDirectory) -> { };

    /**
     * @throws IOException when the component could not be made visible to the launcher.
     */
    void activate(ComponentTask task, File preparedDirectory) throws IOException;
}
