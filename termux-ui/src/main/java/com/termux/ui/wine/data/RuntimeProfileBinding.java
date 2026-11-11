package com.termux.ui.wine.data;

import com.termux.ui.wine.domain.GameRuntimeBackendType;

/** Validated ownership metadata for display and removal, never an executable RuntimeProfile. */
public final class RuntimeProfileBinding {
    private final String id;
    private final String containerId;
    private final GameRuntimeBackendType backend;
    private final String winePackage;

    RuntimeProfileBinding(String id, String containerId, GameRuntimeBackendType backend, String winePackage) {
        this.id = requireId(id);
        this.containerId = requireId(containerId);
        if ("templates".equals(containerId) || backend == null) throw new IllegalArgumentException("invalid_binding");
        this.backend = backend;
        this.winePackage = requireId(winePackage);
    }

    private static String requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}") || ".".equals(id) || "..".equals(id))
            throw new IllegalArgumentException("invalid_binding_id");
        return id;
    }

    public String getId() { return id; }
    public String getContainerId() { return containerId; }
    public GameRuntimeBackendType getRuntimeBackendType() { return backend; }
    public String getWinePackage() { return winePackage; }
}
