package com.termux.ui.wine.runtime;

import com.termux.ui.wine.domain.ProotBox64Versions;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeTranslator;
import com.termux.ui.data.GameStoragePaths;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Dependencies for the external-component layout; legacy recipes keep their embedded inputs. */
public final class ProotDependencyResolver {
    public Set<String> requiredComponentIds(RuntimeProfile profile, GameStoragePaths paths) {
        // Reuse all existing combination and custom-component validation before resolving.
        new RootfsProotBackend().requiredHostCapabilityIds(profile, paths);
        if (profile.getRuntimeBackendType() != GameRuntimeBackendType.ROOTFS_PROOT) {
            throw new IllegalArgumentException("runtime_backend_profile_mismatch");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(RootfsSetupRecipe.IMAGE_COMPONENT);
        String wine = profile.getWinePackage();
        if (!wine.startsWith("rootfs_hangover_")) result.add(wine);
        RuntimeTranslator translator = GameContainerProfileResolver.resolveTranslator(wine, paths);
        if (translator == RuntimeTranslator.BOX64) {
            String selected = profile.getEnvironment().getOrDefault("GAMES_CUSTOM_BOX64_ID", "");
            result.add(ProotBox64Versions.selectedId(selected));
        }
        String dx = profile.getDxWrapper();
        if (!"rootfs-wined3d".equals(dx)) result.add(dx);
        return Collections.unmodifiableSet(result);
    }
}
