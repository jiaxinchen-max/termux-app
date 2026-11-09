package com.termux.ui.wine.service;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class NativePrefixAssetsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String WINE = "glibc_wine_10.0";
    private static final String BUNDLE = "local-games/native-prefix/" + WINE + "/bundle.json";
    private final Map<String, String> assets = new HashMap<>();
    private final NativePrefixAssets.AssetReader reader = path -> {
        if (!assets.containsKey(path)) throw new IOException("missing asset: " + path);
        return new ByteArrayInputStream(assets.get(path).getBytes(StandardCharsets.UTF_8));
    };

    @Before public void setUp() {
        assets.put(BUNDLE, "{\"winePackage\":\"glibc_wine_10.0\",\"schemaVersion\":3}");
        assets.put("bootstrap_termux_box.sh", "bootstrap v1");
        assets.put("local-games/restore_wine_prefix.py", "restore v1");
        assets.put("local-games/glibc_prefix_extras.sh", "locale v1");
        assets.put("local-games/native_runtime_components.sh", "components v1");
    }

    private void marker(File prefix, String name, String value) throws Exception {
        File file = new File(prefix, ".games-runtime/" + name);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), (value + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private File preparedPrefix() throws Exception {
        File prefix = temporary.newFolder();
        marker(prefix, "prepared-identity", NativePrefixAssets.identity(reader, WINE));
        marker(prefix, "preparation-script-identity", NativePrefixAssets.preparationIdentity(reader));
        return prefix;
    }

    @Test public void scriptUpdateDoesNotBlockPreparedPrefix() throws Exception {
        String data = NativePrefixAssets.identity(reader, WINE);
        for (String script : new String[] {"bootstrap_termux_box.sh", "local-games/restore_wine_prefix.py",
                "local-games/glibc_prefix_extras.sh", "local-games/native_runtime_components.sh"}) {
            File prefix = preparedPrefix();
            String before = NativePrefixAssets.preparationIdentity(reader);
            assertTrue(NativePrefixAssets.isPrepared(reader, WINE, prefix));
            assets.put(script, assets.get(script) + " updated");
            assertEquals(data, NativePrefixAssets.identity(reader, WINE));
            assertNotEquals(before, NativePrefixAssets.preparationIdentity(reader));
            assertTrue(NativePrefixAssets.isPrepared(reader, WINE, prefix));
        }
    }

    @Test public void bundleIdentifierChangeDoesNotBlockPreparedPrefix() throws Exception {
        File prefix = preparedPrefix();
        String before = NativePrefixAssets.identity(reader, WINE);
        assets.put(BUNDLE, "{\"winePackage\":\"glibc_wine_10.0\",\"schemaVersion\":3,\"sourceComponentSha256\":\"changed\"}");
        assertNotEquals(before, NativePrefixAssets.identity(reader, WINE));
        assertTrue(NativePrefixAssets.isPrepared(reader, WINE, prefix));
    }

    @Test public void missingScriptReceiptCannotReportPrepared() throws Exception {
        File prefix = temporary.newFolder();
        marker(prefix, "prepared-identity", NativePrefixAssets.identity(reader, WINE));
        assertFalse(NativePrefixAssets.isPrepared(reader, WINE, prefix));
    }

    @Test public void dataMarkerContentIsNotComparedOrRewritten() throws Exception {
        File prefix = preparedPrefix();
        String old = new String(new char[64]).replace('\0', 'a');
        marker(prefix, "prepared-identity", old);
        assertTrue(NativePrefixAssets.isPrepared(reader, WINE, prefix));
        assertEquals(old + "\n", new String(Files.readAllBytes(
            new File(prefix, ".games-runtime/prepared-identity").toPath()), StandardCharsets.UTF_8));
    }

    @Test(expected = IOException.class) public void missingBundleCannotProduceIdentity() throws Exception {
        assets.remove(BUNDLE);
        NativePrefixAssets.identity(reader, WINE);
    }

    @Test(expected = IllegalArgumentException.class) public void unsupportedWineIsRejected() throws Exception {
        NativePrefixAssets.identity(reader, "custom-wine");
    }
}
