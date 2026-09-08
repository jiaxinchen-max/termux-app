package com.termux.localgames.runtime;

import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.RuntimeProfile;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/** Backend-owned runtime requirements and private-path resolution. */
public interface GameRuntimeBackend {

    GameRuntimeBackendType getType();

    Set<String> requiredComponentIds(RuntimeProfile profile);

    Set<String> requiredHostCapabilityIds(RuntimeProfile profile);

    /** Resolves the prefix/C drive owned by the profile's single bound container. */
    File resolvePrefix(GameStoragePaths paths, RuntimeProfile profile) throws IOException;

    /** Empty only for backends that do not execute inside a versioned RootFS. */
    String resolveRuntimeRoot(GameStoragePaths paths, ComponentStoragePaths componentPaths,
                              RuntimeProfile profile) throws IOException;

    String getLauncherAssetPath();

    String getLauncherFileName();
}
