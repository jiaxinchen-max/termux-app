package com.termux.ui.domain;

import com.termux.ui.wine.domain.RuntimeTranslator;
import com.termux.ui.wine.domain.GameRuntimeBackendType;

/** A locally imported, backend-specific component registered only after atomic publication. */
public final class CustomRuntimeComponent {
    public static final int SCHEMA_VERSION = 2;

    public enum Kind { WINE, BOX64, DXVK, VKD3D, TURNIP }

    private final GameRuntimeBackendType backendType;
    private final String id;
    private final Kind kind;
    private final RuntimeTranslator translator;
    private final String displayName;
    private final long installedAtEpochSeconds;
    private final String sha256;

    public CustomRuntimeComponent(String id, Kind kind, RuntimeTranslator translator,
                                  String displayName, long installedAtEpochSeconds,
                                  String sha256) {
        this(id, kind, translator, displayName, installedAtEpochSeconds, sha256,
            legacyBackend(kind, translator));
    }

    public CustomRuntimeComponent(String id, Kind kind, RuntimeTranslator translator,
                                  String displayName, long installedAtEpochSeconds,
                                  String sha256, GameRuntimeBackendType backendType) {
        validateTarget(kind, translator, backendType);
        this.backendType = backendType;
        this.kind = kind;
        this.id = requireId(id, kind);
        this.translator = translator;
        if (displayName == null || displayName.trim().isEmpty() || displayName.length() > 128) {
            throw new IllegalArgumentException("invalid displayName");
        }
        this.displayName = displayName.trim();
        if (installedAtEpochSeconds <= 0) throw new IllegalArgumentException("invalid installedAt");
        this.installedAtEpochSeconds = installedAtEpochSeconds;
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid sha256");
        }
        this.sha256 = sha256;
    }

    public GameRuntimeBackendType getBackendType() { return backendType; }
    public String getId() { return id; }
    public Kind getKind() { return kind; }
    public RuntimeTranslator getTranslator() { return translator; }
    public String getDisplayName() { return displayName; }
    public long getInstalledAtEpochSeconds() { return installedAtEpochSeconds; }
    public String getSha256() { return sha256; }

    /** Legacy source compatibility only. Persistent records and production installs require a backend. */
    static GameRuntimeBackendType legacyBackend(Kind kind, RuntimeTranslator translator) {
        return kind == Kind.WINE && translator == null
            ? GameRuntimeBackendType.GLIBC_TERMUX_BOX : GameRuntimeBackendType.ROOTFS_PROOT;
    }

    static void validateTarget(Kind kind, RuntimeTranslator translator, GameRuntimeBackendType backend) {
        if (kind == null || backend == null) throw new IllegalArgumentException("custom_component_backend_required");
        if (kind != Kind.WINE && translator != null)
            throw new IllegalArgumentException("custom_component_translator_not_allowed");
        if (kind == Kind.WINE) {
            if (backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX && translator != null)
                throw new IllegalArgumentException("native_wine_translator_not_allowed");
            if (backend == GameRuntimeBackendType.ROOTFS_PROOT &&
                translator != RuntimeTranslator.HANGOVER && translator != RuntimeTranslator.BOX64)
                throw new IllegalArgumentException("custom_wine_translator_required");
        }
        if (backend == GameRuntimeBackendType.ROOTFS_PROOT && (kind == Kind.TURNIP || kind == Kind.VKD3D))
            throw new IllegalArgumentException("custom_component_backend_unsupported");
    }

    private static String requireId(String id, Kind kind) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid id");
        }
        String prefix = "custom-" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-";
        if (!id.startsWith(prefix)) throw new IllegalArgumentException("component id must start with " + prefix);
        return id;
    }
}
