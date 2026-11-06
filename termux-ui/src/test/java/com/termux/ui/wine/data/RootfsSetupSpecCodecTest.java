package com.termux.ui.wine.data;

import com.termux.ui.wine.domain.RuntimeSetupTask;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RootfsSetupSpecCodecTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private RuntimeSetupTask task() {
        return RuntimeSetupTask.queued("task", "debian-13-games-rootfs", 11,
            String.join("", Collections.nCopies(64, "a")), "rootfs_hangover_11.16",
            "container", "container", true, 0);
    }
    @Test public void newInputsPreserveUnicodeEqualsAndLiteralShellCharacters() throws Exception {
        File root = temp.newFolder("资料 = $' ;");
        File spec = new File(root, "setup");
        new RootfsSetupSpecCodec().write(spec, task(), root,
            Collections.singletonMap("rootfs_hangover_11.16", root), root, root, root,
            "", null, null, root, "", "", Collections.emptyList());
        List<String> lines = Files.readAllLines(spec.toPath(), StandardCharsets.US_ASCII);
        assertEquals("schemaVersion=4", lines.get(0));
        assertTrue(lines.contains("componentCount=1"));
        assertTrue(lines.contains("bindingCount=0"));
        String expected = "buildContext=" + Base64Text.encode(root.getCanonicalPath().getBytes(StandardCharsets.UTF_8));
        assertTrue(lines.contains(expected));
        assertFalse(lines.stream().anyMatch(line -> line.contains("$'")));
    }
    @Test public void originalOverloadStillWritesOldSnapshotWithoutNewFields() throws Exception {
        File root = temp.newFolder("legacy");
        File spec = new File(root, "setup");
        new RootfsSetupSpecCodec().write(spec, task(), root,
            Collections.singletonMap("rootfs_hangover_11.16", root), root, root, root,
            "", null, null, root, "", "");
        String raw = new String(Files.readAllBytes(spec.toPath()), StandardCharsets.US_ASCII);
        assertTrue(raw.startsWith("schemaVersion=3\n"));
        assertTrue(raw.contains("component=rootfs_hangover_11.16|"));
        assertFalse(raw.contains("bindingCount="));
    }
}
