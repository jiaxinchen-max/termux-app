package com.termux.ui.service;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class SetupPreparationLogTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void forwardsSelectedChildrenOnceAndCanBeReopened() throws Exception {
        File files = temporary.newFolder();
        SetupPreparationLog log = new SetupPreparationLog(files, "rootfs-parent");
        ComponentTaskConsoleLog.append(files, "child-one", "DOWNLOADING 25%");
        ComponentTaskConsoleLog.append(files, "unrelated", "unrelated secret output");
        log.component("wine", "child-one");
        log.component("wine", "child-one");
        ComponentTaskConsoleLog.append(files, "child-one", "VERIFYING");
        log.component("wine", "child-one");
        ComponentTaskConsoleLog.append(files, "child-two", "INSTALLING");
        log.component("dxvk", "child-two");
        String output = SetupPreparationLog.readTail(SetupPreparationLog.file(files, "rootfs-parent"));
        assertEquals(output.indexOf("DOWNLOADING"), output.lastIndexOf("DOWNLOADING"));
        assertTrue(output.contains("VERIFYING"));
        assertTrue(output.indexOf("VERIFYING") < output.indexOf("INSTALLING"));
        assertTrue(output.contains("dxvk · child-two"));
        assertFalse(output.contains("unrelated"));
        assertEquals("", SetupPreparationLog.readTail(SetupPreparationLog.file(files, "other-parent")));
    }

    @Test public void readsBoundedUtf8TailAndHandlesTruncatedChildLog() throws Exception {
        File files = temporary.newFolder();
        SetupPreparationLog log = new SetupPreparationLog(files, "parent");
        File child = ComponentTaskConsoleLog.file(files, "child");
        child.getParentFile().mkdirs();
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 50000; i++) large.append("安装");
        large.append("\nVERIFYING\n");
        Files.write(child.toPath(), large.toString().getBytes(StandardCharsets.UTF_8));
        log.component("wine", "child");
        String tail = SetupPreparationLog.readTail(SetupPreparationLog.file(files, "parent"));
        assertTrue(tail.getBytes(StandardCharsets.UTF_8).length <= SetupPreparationLog.MAX_TAIL_BYTES);
        assertFalse(tail.contains("\ufffd"));
        assertTrue(tail.endsWith("VERIFYING\n"));
        Files.write(child.toPath(), "INSTALLING\n".getBytes(StandardCharsets.UTF_8));
        log.component("wine", "child");
        assertTrue(SetupPreparationLog.readTail(SetupPreparationLog.file(files, "parent")).endsWith("INSTALLING\n"));
    }

    @Test public void failuresRemainVisibleBeforeParentOrChildTaskExists() throws Exception {
        File files = temporary.newFolder();
        ComponentTaskConsoleLog.commandFailed(files, "rejected-child", "runtime_installation_busy");
        assertEquals("runtime_installation_busy", ComponentTaskConsoleLog.commandFailure(files, "rejected-child"));
        SetupPreparationLog log = new SetupPreparationLog(files, "parent");
        log.component("wine", "rejected-child");
        SetupPreparationLog.failed(files, "parent", "component_prepare_failed:wine:runtime_installation_busy");
        assertTrue(SetupPreparationLog.failure(files, "parent").contains("runtime_installation_busy"));
        assertTrue(SetupPreparationLog.readTail(SetupPreparationLog.file(files, "parent")).contains("Command failed"));
        assertEquals("", SetupPreparationLog.failure(files, "other-parent"));
        ComponentTaskConsoleLog.clearCommandFailure(files, "rejected-child");
        assertEquals("", ComponentTaskConsoleLog.commandFailure(files, "rejected-child"));
    }

    @Test public void rejectsTaskPathTraversal() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SetupPreparationLog.file(temporary.getRoot(), "../escape"));
    }
}
