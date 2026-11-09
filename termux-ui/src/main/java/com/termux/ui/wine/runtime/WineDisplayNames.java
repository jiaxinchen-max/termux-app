package com.termux.ui.wine.runtime;

import com.termux.ui.api.RuntimeComponentVariant;
import com.termux.ui.components.index.ComponentIndex;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.RuntimeProfile;
import java.util.List;

/** Resolves the saved identity, never a default or the editor's unsaved selection. */
public final class WineDisplayNames {
    private WineDisplayNames() {}

    public static String resolve(RuntimeProfile profile, List<CustomRuntimeComponent> custom,
                                 List<RuntimeComponentVariant> installed, ComponentIndex index) {
        String id = profile.getWinePackage();
        for (CustomRuntimeComponent entry : custom) {
            if (entry.getKind() == CustomRuntimeComponent.Kind.WINE
                    && entry.getBackendType() == profile.getRuntimeBackendType() && entry.getId().equals(id)) {
                return entry.getDisplayName();
            }
        }
        for (RuntimeComponentVariant entry : installed) {
            if (entry.getKind() == CustomRuntimeComponent.Kind.WINE
                    && entry.getBackendType() == profile.getRuntimeBackendType() && entry.getId().equals(id)) {
                return entry.getDisplayName();
            }
        }
        return index.find(id).map(entry -> entry.getDisplayName()).orElse(id);
    }
}
