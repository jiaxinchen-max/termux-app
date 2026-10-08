package com.termux.localgames.domain;

/** A user-installed Wine or Box64 build, registered after
 *  install_custom_rootfs_component.sh reports success -- see CustomComponentInstallForegroundService.
 *  Lives in a separate, writable registry from the read-only bundled component catalog
 *  (ComponentIndex), since these entries are created at runtime from a locally-picked file rather
 *  than shipped with the app. */
public final class CustomRuntimeComponent {
    public static final int SCHEMA_VERSION = 1;

    public enum Kind { WINE, BOX64 }

    private final String id;
    private final Kind kind;
    private final RuntimeTranslator translator;
    private final String displayName;
    private final long installedAtEpochSeconds;
    private final String sha256;

    public CustomRuntimeComponent(String id, Kind kind, RuntimeTranslator translator,
                                  String displayName, long installedAtEpochSeconds,
                                  String sha256) {
        if (kind == null) throw new IllegalArgumentException("kind required");
        this.kind = kind;
        this.id = requireId(id, kind);
        if (kind == Kind.WINE) {
            if (translator == null || translator == RuntimeTranslator.FEX) {
                throw new IllegalArgumentException("invalid translator for custom wine");
            }
        } else if (translator != null) {
            throw new IllegalArgumentException("box64 kind must not declare a translator");
        }
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

    public String getId() { return id; }
    public Kind getKind() { return kind; }
    public RuntimeTranslator getTranslator() { return translator; }
    public String getDisplayName() { return displayName; }
    public long getInstalledAtEpochSeconds() { return installedAtEpochSeconds; }
    public String getSha256() { return sha256; }

    private static String requireId(String id, Kind kind) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid id");
        }
        if (kind == Kind.WINE && !id.startsWith("custom-wine-")) {
            throw new IllegalArgumentException("wine id must start with custom-wine-");
        }
        if (kind == Kind.BOX64 && !id.startsWith("custom-box64-")) {
            throw new IllegalArgumentException("box64 id must start with custom-box64-");
        }
        return id;
    }
}
