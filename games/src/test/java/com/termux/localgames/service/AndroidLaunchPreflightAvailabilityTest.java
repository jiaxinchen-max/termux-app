package com.termux.localgames.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.components.index.ComponentIndexParser;
import com.termux.localgames.components.install.ComponentInstallationReader;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.runtime.InstalledComponentVersion;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

public class AndroidLaunchPreflightAvailabilityTest {

    private static final String SHA =
        "1111111111111111111111111111111111111111111111111111111111111111";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void glibcDoesNotTreatPrivateInstallAsLauncherAvailability() throws Exception {
        ComponentInstallationReader reader = installedReader("runtime-component");

        Map<String, InstalledComponentVersion> result =
            AndroidLaunchPreflight.resolveInstalledComponents(index(), reader,
                GameRuntimeBackendType.GLIBC_TERMUX_BOX, host(false));

        assertFalse(result.containsKey("runtime-component"));
    }

    @Test
    public void glibcUsesHostRuntimeProbe() throws Exception {
        Map<String, InstalledComponentVersion> result =
            AndroidLaunchPreflight.resolveInstalledComponents(index(),
                installedReader("runtime-component"),
                GameRuntimeBackendType.GLIBC_TERMUX_BOX, host(true));

        assertTrue(result.containsKey("runtime-component"));
    }

    @Test
    public void rootfsUsesVerifiedPrivateInstall() throws Exception {
        Map<String, InstalledComponentVersion> result =
            AndroidLaunchPreflight.resolveInstalledComponents(index(),
                installedReader("runtime-component"),
                GameRuntimeBackendType.ROOTFS_PROOT, host(false));

        assertTrue(result.containsKey("runtime-component"));
    }

    private ComponentIndex index() throws Exception {
        String json = "{\"schemaVersion\":2,\"generatedAt\":\"test\",\"packages\":[" +
            "{\"id\":\"runtime-component\",\"category\":\"runtime\",\"type\":\"runtime_support\"," +
            "\"displayName\":\"Runtime\",\"versionName\":\"1\",\"summary\":\"Runtime\"," +
            "\"framework\":\"Test\",\"base\":true,\"recommended\":false,\"profileValue\":\"\"," +
            "\"runtimeBackends\":[\"glibc_termux_box\",\"rootfs_proot\"],\"version\":1," +
            "\"url\":\"https://example.invalid/runtime.tar.xz\",\"size\":1,\"sha256\":\"" + SHA + "\"}]}";
        return new ComponentIndexParser().parse(new ByteArrayInputStream(
            json.getBytes(StandardCharsets.UTF_8)));
    }

    private ComponentInstallationReader installedReader(String componentId) throws Exception {
        File root = temporaryFolder.newFolder();
        File packageRoot = new File(root, componentId);
        File version = new File(packageRoot, "versions/v1-test");
        assertTrue(version.mkdirs());
        Properties receipt = new Properties();
        receipt.setProperty("schemaVersion", "1");
        receipt.setProperty("packageName", componentId);
        receipt.setProperty("version", "1");
        receipt.setProperty("sha256", SHA);
        store(new File(version, ".games-component.properties"), receipt);
        Properties pointer = new Properties();
        pointer.setProperty("schemaVersion", "1");
        pointer.setProperty("active", "v1-test");
        pointer.setProperty("previous", "");
        store(new File(packageRoot, "active.properties"), pointer);
        return new ComponentInstallationReader(root);
    }

    private static void store(File file, Properties value) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create test directory");
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            value.store(output, "test");
        }
    }

    private static LocalGamesHost host(boolean available) {
        return new LocalGamesHost() {
            @Override
            public boolean isRuntimeAvailable() { return true; }

            @Override
            public boolean isRuntimeComponentAvailable(String componentId) {
                return available && "runtime-component".equals(componentId);
            }
        };
    }
}
