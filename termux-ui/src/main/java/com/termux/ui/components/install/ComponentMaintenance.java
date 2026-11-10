package com.termux.ui.components.install;

import com.termux.ui.wine.domain.ProotBox64Versions;
import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.data.CustomComponentInstallation;
import com.termux.ui.data.FileCustomRuntimeComponentRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.runtime.GlibcTermuxBoxBackend;
import com.termux.ui.wine.runtime.NativeBox64Versions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Explicit, component-scoped maintenance; never follows a package-supplied deletion list. */
public final class ComponentMaintenance {
    private final File files;
    private final ComponentStoragePaths storage;
    public ComponentMaintenance(File files) { this.files = files; storage = new ComponentStoragePaths(files); }

    public static String sourceId(String id) {
        return id;
    }

    public static String variantArchive(String id) {
        if (GlibcTermuxBoxBackend.isNativeTurnip(id)) return "glibc/opt/libs/mesa/" + id.substring(7) + ".7z";
        if (GlibcTermuxBoxBackend.isNativeDx(id)) return "glibc/opt/libs/d3d/" + id.substring(7) + ".7z";
        return null;
    }

    public static void requireManageable(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}") ||
            !(id.startsWith("custom-") || id.startsWith("glibc_wine_") || id.startsWith("glibc_proton_") ||
              NativeBox64Versions.isBuiltIn(id) || id.startsWith("rootfs_wine_") || id.startsWith("rootfs_proton_") ||
              ProotBox64Versions.isBuiltIn(id) || id.startsWith("rootfs_dxvk_") ||
              variantArchive(id) != null)) {
            throw new IOException("component_not_independently_manageable:" + id);
        }
    }

    public void uninstall(String id) throws IOException {
        requireManageable(id);
        File glibc = new File(files, "usr/glibc");
        String archive = variantArchive(id);
        if (archive != null) {
            delete(new File(new File(files, "usr"), archive));
            delete(new File(glibc, "components/" + id));
        } else if (id.startsWith("custom-")) {
            CustomRuntimeComponent component = new FileCustomRuntimeComponentRepository(
                new GameStoragePaths(files).getCustomRuntimeComponentsDirectory()).find(id)
                .orElseThrow(() -> new IOException("custom_component_unknown:" + id));
            delete(CustomComponentInstallation.directory(new GameStoragePaths(files), component));
        } else if (id.startsWith("glibc_wine_") || id.startsWith("glibc_proton_")) {
            delete(new File(glibc, id));
        } else if (NativeBox64Versions.isBuiltIn(id)) {
            delete(new File(glibc, NativeBox64Versions.relativeDirectory(id)));
        }
        delete(new File(storage.getInstallDirectory(), id));
        for (String root : new String[]{"termux-box/package-manager/installed", "opt/package-manager/installed"}) {
            for (String suffix : new String[]{"", "_lists", "_md5"}) delete(new File(glibc, root + "/" + id + suffix));
        }
    }

    /** Explicit replacement updates prepared PRoot bindings without touching the prefix. */
    public void refreshProotBindings(String id) throws IOException {
        GameStoragePaths paths = new GameStoragePaths(files);
        File configurations = new File(paths.getRuntimeDirectory(), "proot-bindings/configurations");
        File[] entries = configurations.listFiles();
        if (entries == null) {
            if (configurations.exists()) throw new IOException("proot_bindings_unreadable");
            return;
        }
        com.termux.ui.wine.data.ProotBindingStore store =
            com.termux.ui.wine.runtime.ProotPreparedRuntime.configurations(paths);
        com.termux.ui.wine.runtime.ProotBindingResolver resolver =
            new com.termux.ui.wine.runtime.ProotBindingResolver(storage.getInstallDirectory());
        for (File entry : entries) {
            if (!entry.getName().endsWith(".bindings")) continue;
            String name = entry.getName().substring(0, entry.getName().length() - 9);
            com.termux.ui.wine.domain.ProotBindingSnapshot snapshot = store.read(name);
            java.util.List<com.termux.ui.wine.domain.ProotComponentBinding> bindings = new java.util.ArrayList<>();
            boolean replaced = false;
            for (com.termux.ui.wine.domain.ProotComponentBinding binding : snapshot.getBindings()) {
                if (id.equals(binding.getComponentId())) {
                    if (!replaced) {
                        InstalledComponent active = new ComponentInstallationReader(storage.getInstallDirectory())
                            .read(id).getActive().orElseThrow(() -> new IOException("component_installation_missing:" + id));
                        bindings.addAll(resolver.bind(active));
                        replaced = true;
                    }
                } else bindings.add(binding);
            }
            if (replaced) store.write(name, new com.termux.ui.wine.domain.ProotBindingSnapshot(
                snapshot.getWinePackage(), snapshot.getDxWrapper(), snapshot.getTranslator(),
                snapshot.getBox64ComponentId(), snapshot.getRecipeSha256(), bindings));
        }
    }

    public void clearExpandedVariant(String id) throws IOException {
        if (variantArchive(id) == null) throw new IOException("native_variant_invalid");
        delete(new File(files, "usr/glibc/components/" + id));
    }

    public void clearDownload(String id, File downloads) throws IOException {
        requireManageable(id);
        String source = sourceId(id);
        File[] entries = downloads.listFiles();
        if (entries == null) {
            if (downloads.exists()) throw new IOException("component_downloads_unreadable");
            return;
        }
        for (File entry : entries) {
            String name = entry.getName();
            if (name.matches(java.util.regex.Pattern.quote(source) + "-[0-9]+\\.(archive|part)")) {
                if (!entry.delete() && entry.exists()) throw new IOException("component_cache_cleanup_failed");
            }
        }
    }

    private void delete(File target) throws IOException {
        requireChild(files, target);
        // Unlink symlinks, including dangling links; never traverse their targets.
        if (!Files.isSymbolicLink(target.toPath()) && target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("component_directory_unreadable");
            for (File child : children) delete(child);
        }
        if (!target.delete() && (target.exists() || Files.isSymbolicLink(target.toPath()))) {
            throw new IOException("component_delete_failed:" + target.getName());
        }
    }

    private static void requireChild(File root, File target) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        File cursor = parent;
        File absoluteRoot = root.getAbsoluteFile();
        while (cursor != null && !cursor.equals(absoluteRoot)) {
            if (Files.isSymbolicLink(cursor.toPath())) throw new IOException("component_path_escape");
            cursor = cursor.getParentFile();
        }
        if (cursor == null) throw new IOException("component_path_escape");
        String base = root.getCanonicalPath() + File.separator;
        if (parent == null || !parent.getCanonicalPath().startsWith(base)) throw new IOException("component_path_escape");
    }
}
