package com.termux.ui.api;

import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.GameRuntimeBackendType;

/** An installed selectable runtime variant; host-owned filesystem paths stay private. */
public final class RuntimeComponentVariant {
    private final String id;
    private final String displayName;
    private final CustomRuntimeComponent.Kind kind;
    private final GameRuntimeBackendType backendType;

    public RuntimeComponentVariant(String id, String displayName,
                                   CustomRuntimeComponent.Kind kind,
                                   GameRuntimeBackendType backendType) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}"))
            throw new IllegalArgumentException("invalid_runtime_component_variant_id");
        if (displayName == null || displayName.trim().isEmpty())
            throw new IllegalArgumentException("runtime_component_variant_name_required");
        if (kind == null || backendType == null)
            throw new IllegalArgumentException("runtime_component_variant_target_required");
        this.id = id;
        this.displayName = displayName.trim();
        this.kind = kind;
        this.backendType = backendType;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public CustomRuntimeComponent.Kind getKind() { return kind; }
    public GameRuntimeBackendType getBackendType() { return backendType; }
}
