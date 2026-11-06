package com.termux.ui.wine.runtime;

import com.termux.ui.components.install.ComponentInstallationReader;
import com.termux.ui.components.install.InstalledComponent;
import com.termux.ui.wine.domain.ProotComponentBinding;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.data.GameStoragePaths;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Freezes installed versions once, during preparation, rather than once per launch. */
public final class ProotBindingResolver {
    private final File installRoot;
    private final ComponentInstallationReader installations;

    public ProotBindingResolver(File installRoot) {
        this.installRoot = installRoot;
        installations = new ComponentInstallationReader(installRoot);
    }

    public List<ProotComponentBinding> freeze(RuntimeProfile profile, GameStoragePaths paths)
        throws IOException {
        List<ProotComponentBinding> result = new ArrayList<>();
        for (String id : new ProotDependencyResolver().requiredComponentIds(profile, paths)) {
            if (id.equals(RootfsSetupRecipe.DEFAULT_SOURCE)) continue; // installed in guest /usr
            InstalledComponent component = installations.read(id).getActive()
                .orElseThrow(() -> new IOException("rootfs_component_prepare_required:" + id));
            if (id.startsWith("custom-wine-")) {
                add(result, component, component.getDirectory(), "/opt/custom-wine/" + id,
                    ProotComponentBinding.Role.WINE);
            } else if (id.startsWith("custom-box64-")) {
                add(result, component, component.getDirectory(), "/opt/box64/" + id,
                    ProotComponentBinding.Role.BOX64);
            } else if (id.startsWith("custom-dxvk-")) {
                add(result, component, new File(component.getDirectory(), "x64"), "/opt/dxvk/" + id + "/x64",
                    ProotComponentBinding.Role.DXVK64);
                add(result, component, new File(component.getDirectory(), "x32"), "/opt/dxvk/" + id + "/x32",
                    ProotComponentBinding.Role.DXVK32);
            } else if (id.equals(RootfsSetupRecipe.BOX64_WINE_COMPONENT)) {
                add(result, component, contentRoot(component, "bin/wine64"),
                    "/opt/box64-wine", ProotComponentBinding.Role.WINE);
            } else if (id.equals(RootfsSetupRecipe.BOX64_PROTON_COMPONENT)) {
                add(result, component, contentRoot(component, "bin/wine"),
                    "/opt/box64-proton", ProotComponentBinding.Role.WINE);
            } else if (id.equals("rootfs_box64_0.4.5")) {
                add(result, component, contentRoot(component, "bin/box64"),
                    "/opt/box64/" + id, ProotComponentBinding.Role.BOX64);
            } else if (id.startsWith("rootfs_dxvk_")) {
                File root = contentRoot(component, "x64/d3d11.dll");
                File bits32 = new File(root, "x32");
                if (!new File(bits32, "d3d11.dll").isFile()) bits32 = new File(root, "x86");
                if (!new File(bits32, "d3d11.dll").isFile()) {
                    throw new IOException("rootfs_dxvk_payload_incomplete:" + id);
                }
                add(result, component, new File(root, "x64"),
                    "/opt/games-runtime/" + id + "/system32", ProotComponentBinding.Role.DXVK64);
                add(result, component, bits32,
                    "/opt/games-runtime/" + id + "/syswow64", ProotComponentBinding.Role.DXVK32);
            } else throw new IOException("rootfs_binding_component_unsupported:" + id);
        }
        return ProotComponentBinding.immutableBindings(result);
    }

    public void validate(List<ProotComponentBinding> bindings) throws IOException {
        for (ProotComponentBinding b : ProotComponentBinding.immutableBindings(bindings)) {
            File content = b.validatePaths(installRoot);
            installations.readFrozen(b.getComponentId(), new File(b.getVersionDirectory()),
                b.getVersion(), b.getSha256());
            validateTree(content);
        }
    }

    private void add(List<ProotComponentBinding> result, InstalledComponent component,
                     File contentRoot, String guestTarget, ProotComponentBinding.Role role)
        throws IOException {
        File version = component.getDirectory().getCanonicalFile();
        File content = contentRoot.getCanonicalFile();
        if (!(content.equals(version) || content.getPath().startsWith(version.getPath() + File.separator))) {
            throw new IOException("rootfs_component_content_escape");
        }
        ProotComponentBinding b = new ProotComponentBinding(component.getPackageName(),
            component.getVersion(), component.getSha256(), version.getPath(),
            content.equals(version) ? "" : content.getPath().substring(version.getPath().length() + 1),
            guestTarget, role);
        b.validatePaths(installRoot);
        validateTree(content);
        result.add(b);
    }

    private static void validateTree(File version) throws IOException {
        java.util.ArrayDeque<File> pending = new java.util.ArrayDeque<>();
        pending.add(version);
        int count = 0;
        while (!pending.isEmpty()) {
            if (++count > 100000) throw new IOException("rootfs_component_tree_too_large");
            File path = pending.removeFirst();
            File resolved = path.getCanonicalFile();
            if (!(resolved.equals(version) || resolved.getPath().startsWith(version.getPath() + File.separator))) {
                throw new IOException("rootfs_component_link_escape");
            }
            // Do not recurse through links (including internal cycles).
            if (path.equals(resolved) && path.isDirectory()) {
                File[] children = path.listFiles();
                if (children == null) throw new IOException("rootfs_component_unreadable");
                java.util.Collections.addAll(pending, children);
            }
        }
    }

    /** Portable archives may have one wrapper directory. Ambiguous layouts are never guessed. */
    private static File contentRoot(InstalledComponent component, String required) throws IOException {
        File version = component.getDirectory().getCanonicalFile();
        List<File> candidates = new ArrayList<>();
        if (new File(version, required).isFile()) candidates.add(version);
        File[] children = version.listFiles();
        if (children == null) throw new IOException("rootfs_component_unreadable");
        for (File child : children) {
            if (child.isDirectory() && new File(child, required).isFile()) candidates.add(child);
        }
        if (candidates.size() != 1) throw new IOException("rootfs_component_layout_ambiguous:" + component.getPackageName());
        File candidate = candidates.get(0).getCanonicalFile();
        File payload = new File(candidate, required).getCanonicalFile();
        if (!payload.getPath().startsWith(version.getPath() + File.separator)) {
            throw new IOException("rootfs_component_payload_escape");
        }
        return candidate;
    }
}
