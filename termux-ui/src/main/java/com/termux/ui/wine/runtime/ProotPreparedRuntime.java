package com.termux.ui.wine.runtime;

import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.ProotBindingStore;
import com.termux.ui.wine.domain.ProotBindingSnapshot;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.GameRuntimeBackendType;

import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Properties;

/** Read-only admission for the new layout. Preparation is the only publisher of these records. */
public final class ProotPreparedRuntime {
    public static ProotBindingStore configurations(GameStoragePaths paths) {
        return new ProotBindingStore(new File(paths.getRuntimeDirectory(), "proot-bindings/configurations"));
    }

    public static boolean usesExternalLayout(File rootfs) throws IOException {
        Properties manifest = manifest(rootfs);
        if (!"3".equals(manifest.getProperty("schemaVersion"))) return false;
        if (!"external-components-v1".equals(manifest.getProperty("layout"))) {
            throw new IOException("rootfs_runtime_layout_unsupported");
        }
        return true;
    }

    public static ProotBindingSnapshot require(GameStoragePaths paths, RuntimeProfile profile)
        throws IOException {
        Properties manifest = manifest(paths.getSharedRootfsDirectory());
        if (!"3".equals(manifest.getProperty("schemaVersion")) ||
            !"external-components-v1".equals(manifest.getProperty("layout"))) {
            throw new IOException("rootfs_runtime_layout_unsupported");
        }
        ProotBindingSnapshot snapshot = configurations(paths).read(profile.getContainerId());
        String box64 = profile.getEnvironment().getOrDefault("GAMES_CUSTOM_BOX64_ID", "");
        if (!profile.getWinePackage().equals(snapshot.getWinePackage()) ||
            !profile.getDxWrapper().equals(snapshot.getDxWrapper()) ||
            !box64.equals(snapshot.getBox64ComponentId()) ||
            GameContainerProfileResolver.resolveTranslator(profile.getWinePackage(), paths) != snapshot.getTranslator()) {
            throw new IOException("rootfs_configuration_prepare_required");
        }
        // A prefix recipe includes scripts/assets that may change without changing the
        // prebuilt image. Compare it to its own successful preparation receipt.
        File preparation = new File(paths.getContainerPrefixDirectory(profile.getContainerId(),
            GameRuntimeBackendType.ROOTFS_PROOT), ".games-runtime/preparation-recipe");
        String preparationRecipe = manifest.getProperty("recipeSha256");
        if (!preparation.getCanonicalFile().equals(new File(
            preparation.getParentFile().getCanonicalFile(), preparation.getName()))) {
            throw new IOException("rootfs_configuration_recipe_mismatch");
        }
        if (preparation.exists()) {
            if (!preparation.isFile() || preparation.length() != 65)
                throw new IOException("rootfs_configuration_recipe_mismatch");
            byte[] data = new byte[65];
            try (DataInputStream input = new DataInputStream(new FileInputStream(preparation))) {
                input.readFully(data);
                if (input.read() != -1) throw new IOException("rootfs_configuration_recipe_mismatch");
            }
            String record = new String(data, java.nio.charset.StandardCharsets.US_ASCII);
            if (!record.matches("[0-9a-f]{64}\\n")) throw new IOException("rootfs_configuration_recipe_mismatch");
            preparationRecipe = record.substring(0, 64);
        }
        if (!snapshot.getRecipeSha256().equals(preparationRecipe)) {
            throw new IOException("rootfs_configuration_recipe_mismatch");
        }
        new ProotBindingResolver(new ComponentStoragePaths(paths.getTermuxPrefixDirectory().getParentFile()).getInstallDirectory())
            .validate(snapshot.getBindings());
        return snapshot;
    }

    private static Properties manifest(File rootfs) throws IOException {
        Properties value = new Properties();
        File file = new File(rootfs, "etc/games-runtime.properties");
        if (!file.isFile()) return value;
        try (FileInputStream input = new FileInputStream(file)) { value.load(input); }
        return value;
    }
    private ProotPreparedRuntime() {}
}
