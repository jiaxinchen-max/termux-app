package com.termux.localgames.components.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.GameRuntimeBackendType;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Locks the contract between catalog profileValues and the on-device launch scripts.
 *
 * <p>These are static consistency checks only: the scripts themselves are ARM64 Linux
 * runtime packages and must be exercised on the AVD, never on the build host. What this
 * test prevents is a catalog edit that silently produces a value the device script
 * cannot consume — a failure that would otherwise only surface at launch time.
 */
public class ComponentLaunchContractTest {

    private static String read(String resource) throws IOException {
        InputStream input = ComponentLaunchContractTest.class.getClassLoader()
            .getResourceAsStream(resource);
        assertTrue(resource + " must be on the test classpath", input != null);
        try (InputStream closeable = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = closeable.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static ComponentIndex index() throws IOException {
        InputStream input = ComponentLaunchContractTest.class.getClassLoader()
            .getResourceAsStream("termux-box-packages/index-v1.json");
        assertTrue("bundled index must be available to tests", input != null);
        try (InputStream closeable = input) {
            return new ComponentIndexParser().parse(closeable);
        }
    }

    @Test
    public void wineProfileValueMatchesPackageIdBecauseScriptBuildsADirectoryPath()
            throws Exception {
        // start_local_game.sh: WINE_PATH="$TERMUX_GLIBC_DIR/$WINE_PACKAGE"
        for (ComponentDescriptor descriptor : index().selectable(ComponentType.CONTAINER,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX)) {
            assertEquals("Wine profileValue is used verbatim as an install directory name; "
                    + "it must equal the package id or the launch will not find the prefix",
                descriptor.getId(), descriptor.getProfileValue());
        }
    }

    @Test
    public void wineProfileValueSurvivesTheScriptCharacterWhitelist() throws Exception {
        // start_local_game.sh: case "$WINE_PACKAGE" in ''|*[!A-Za-z0-9._-]*) fail
        for (ComponentDescriptor descriptor : index().selectable(ComponentType.CONTAINER,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX)) {
            assertTrue("value rejected by the script whitelist: " + descriptor.getProfileValue(),
                descriptor.getProfileValue().matches("[A-Za-z0-9._-]+"));
        }
    }

    @Test
    public void virglProfileValueMatchesTheLiteralTheScriptCompares() throws Exception {
        // start_local_game.sh: if [ "$GRAPHICS_DRIVER" = VirGL ] || [ "$GRAPHICS_DRIVER" = virgl ]
        ComponentDescriptor virgl = index().find("virgl-mesa").get();
        assertEquals("script compares the bare token, not the package id",
            "virgl", virgl.getProfileValue());
        String script = read("local-games/start_local_game.sh");
        assertTrue("launch script must still branch on the catalog value",
            script.contains("\"$GRAPHICS_DRIVER\" = " + virgl.getProfileValue()));
    }

    @Test
    public void everySelectableValueIsNonEmptyAndTrimmed() throws Exception {
        for (ComponentDescriptor descriptor : index().getComponents()) {
            if (!descriptor.isSelectable()) continue;
            String value = descriptor.getProfileValue();
            assertEquals("profileValue must be pre-trimmed before reaching the script",
                value.trim(), value);
            assertTrue("selectable component must carry a value", !value.isEmpty());
        }
    }
}
