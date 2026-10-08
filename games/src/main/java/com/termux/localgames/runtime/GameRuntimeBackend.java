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

    /** Default: ignores paths and delegates to the single-arg overload above -- override only
     *  when the computation genuinely depends on on-disk state. RootfsProotBackend overrides this
     *  to validate a custom-installed Wine build (see CustomRuntimeComponent) actually exists in
     *  its registry before treating it as a satisfiable requirement. */
    default Set<String> requiredComponentIds(RuntimeProfile profile, GameStoragePaths paths) {
        return requiredComponentIds(profile);
    }

    default Set<String> requiredHostCapabilityIds(RuntimeProfile profile, GameStoragePaths paths) {
        return requiredHostCapabilityIds(profile);
    }

    /** Resolves the prefix/C drive owned by the profile's single bound container. */
    File resolvePrefix(GameStoragePaths paths, RuntimeProfile profile) throws IOException;

    /** Resolves a per-container writable directory to bind as $HOME inside the guest, for
     *  backends whose rootfs is shared and conceptually read-only (anything a tool writes under
     *  $HOME needs somewhere real to land that isn't the shared tree). Null for backends that do
     *  not run inside a shared rootfs. */
    default File resolveHomeDirectory(GameStoragePaths paths, RuntimeProfile profile)
        throws IOException {
        return null;
    }

    /** Empty only for backends that do not execute inside a versioned RootFS. */
    String resolveRuntimeRoot(GameStoragePaths paths, ComponentStoragePaths componentPaths,
                              RuntimeProfile profile) throws IOException;

    String getLauncherAssetPath();

    String getLauncherFileName();
}
