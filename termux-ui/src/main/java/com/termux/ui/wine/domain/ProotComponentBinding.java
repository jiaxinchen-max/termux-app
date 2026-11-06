package com.termux.ui.wine.domain;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A frozen component identity. Never resolve an active pointer while consuming this record. */
public final class ProotComponentBinding {
    public static final int REVISION = 1;
    public static final int MAX_BINDINGS = 16;
    public enum Role { WINE, BOX64, DXVK64, DXVK32 }

    private final String componentId;
    private final int version;
    private final String sha256;
    private final String versionDirectory;
    private final String contentRelativePath;
    private final String guestTarget;
    private final Role role;

    public ProotComponentBinding(String componentId, int version, String sha256,
                                 String versionDirectory, String contentRelativePath,
                                 String guestTarget, Role role) {
        if (componentId == null || !componentId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") ||
            version < 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}") || role == null) {
            throw new IllegalArgumentException("invalid_proot_binding_identity");
        }
        if (versionDirectory == null || !new File(versionDirectory).isAbsolute() ||
            invalidText(versionDirectory) || versionDirectory.contains(":") ||
            versionDirectory.contains("\\")) {
            throw new IllegalArgumentException("invalid_proot_binding_directory");
        }
        if (contentRelativePath == null || invalidText(contentRelativePath) ||
            contentRelativePath.contains(":") || contentRelativePath.contains("\\") ||
            contentRelativePath.startsWith("/")) {
            throw new IllegalArgumentException("invalid_proot_binding_content");
        }
        if (!contentRelativePath.isEmpty()) {
            for (String segment : contentRelativePath.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("invalid_proot_binding_content");
                }
            }
        }
        String expected;
        switch (role) {
            case WINE:
                if (componentId.equals("rootfs_wine_10.0")) expected = "/opt/box64-wine";
                else if (componentId.equals("rootfs_proton_11.0")) expected = "/opt/box64-proton";
                else if (componentId.matches("custom-wine-[A-Za-z0-9._-]+")) expected = "/opt/custom-wine/" + componentId;
                else throw new IllegalArgumentException("invalid_proot_binding_role");
                break;
            case BOX64:
                if (!(componentId.equals("rootfs_box64_0.4.5") || componentId.matches("custom-box64-[A-Za-z0-9._-]+"))) {
                    throw new IllegalArgumentException("invalid_proot_binding_role");
                }
                expected = "/opt/box64/" + componentId;
                break;
            default:
                if (!(componentId.matches("rootfs_dxvk_[0-9.]+") || componentId.matches("custom-dxvk-[A-Za-z0-9._-]+"))) {
                    throw new IllegalArgumentException("invalid_proot_binding_role");
                }
                String base = componentId.startsWith("custom-") ? "/opt/dxvk/" : "/opt/games-runtime/";
                expected = base + componentId + (role == Role.DXVK64 ?
                    (componentId.startsWith("custom-") ? "/x64" : "/system32") :
                    (componentId.startsWith("custom-") ? "/x32" : "/syswow64"));
        }
        if (!expected.equals(guestTarget)) throw new IllegalArgumentException("invalid_proot_binding_target");
        this.componentId = componentId;
        this.version = version;
        this.sha256 = sha256;
        this.versionDirectory = versionDirectory;
        this.contentRelativePath = contentRelativePath;
        this.guestTarget = guestTarget;
        this.role = role;
    }

    /** Must be called against the local private install root before executing or publishing. */
    public File validatePaths(File installRoot) throws IOException {
        File root = installRoot.getCanonicalFile();
        File versions = new File(new File(root, componentId), "versions");
        File directory = new File(versionDirectory);
        File canonical = directory.getCanonicalFile();
        if (!directory.equals(canonical) || !canonical.isDirectory() ||
            !versions.equals(versions.getCanonicalFile()) || !versions.equals(canonical.getParentFile())) {
            throw new IOException("proot_binding_version_outside_install_root");
        }
        File content = contentRelativePath.isEmpty() ? canonical : new File(canonical, contentRelativePath);
        File resolved = content.getCanonicalFile();
        if (!resolved.isDirectory() || !(resolved.equals(canonical) ||
            resolved.getPath().startsWith(canonical.getPath() + File.separator))) {
            throw new IOException("proot_binding_content_outside_version");
        }
        return resolved;
    }

    public static List<ProotComponentBinding> immutableBindings(List<ProotComponentBinding> bindings) {
        if (bindings == null || bindings.size() > MAX_BINDINGS) throw new IllegalArgumentException("invalid_proot_binding_count");
        Set<String> targets = new HashSet<>();
        Set<Role> roles = new HashSet<>();
        ProotComponentBinding dxvk = null;
        List<ProotComponentBinding> result = new ArrayList<>();
        for (ProotComponentBinding binding : bindings) {
            if (binding == null || !targets.add(binding.guestTarget) || !roles.add(binding.role)) {
                throw new IllegalArgumentException("duplicate_proot_binding");
            }
            for (ProotComponentBinding previous : result) {
                if (previous.componentId.equals(binding.componentId) &&
                    (previous.version != binding.version || !previous.sha256.equals(binding.sha256) ||
                        !previous.versionDirectory.equals(binding.versionDirectory))) {
                    throw new IllegalArgumentException("conflicting_proot_binding_identity");
                }
            }
            if (binding.role == Role.DXVK64 || binding.role == Role.DXVK32) {
                if (dxvk != null && (!dxvk.componentId.equals(binding.componentId) ||
                    dxvk.version != binding.version || !dxvk.sha256.equals(binding.sha256) ||
                    !dxvk.versionDirectory.equals(binding.versionDirectory))) {
                    throw new IllegalArgumentException("conflicting_proot_dxvk_identity");
                }
                dxvk = binding;
            }
            result.add(binding);
        }
        if (roles.contains(Role.DXVK64) != roles.contains(Role.DXVK32)) {
            throw new IllegalArgumentException("incomplete_proot_dxvk_binding");
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean invalidText(String text) {
        return text.indexOf('\0') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }
    public String getComponentId() { return componentId; }
    public int getVersion() { return version; }
    public String getSha256() { return sha256; }
    public String getVersionDirectory() { return versionDirectory; }
    public String getContentRelativePath() { return contentRelativePath; }
    public String getGuestTarget() { return guestTarget; }
    public Role getRole() { return role; }
}
