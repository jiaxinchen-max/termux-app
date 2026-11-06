package com.termux.app.localgames;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class AppUidProcessesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void selectsOnlyOwnedLiveProcessesAndExcludesHelper() throws Exception {
        File proc = temporary.newFolder();
        process(proc, 100, 10234, "S", "10");
        process(proc, 101, 10235, "S", "11");
        process(proc, 102, 10234, "Z", "12");
        process(proc, 103, 10234, "S", "13");
        List<AppUidProcesses.Entry> selected = AppUidProcesses.scan(proc, 10234, 103);
        assertEquals(1, selected.size());
        assertEquals(100, selected.get(0).pid);
    }

    @Test public void recycledPidOrChangedUidIsNotSignalled() throws Exception {
        File proc = temporary.newFolder();
        process(proc, 100, 10234, "S", "10");
        AppUidProcesses.Entry original = AppUidProcesses.scan(proc, 10234, 999).get(0);
        assertTrue(AppUidProcesses.matches(proc, original, 10234));
        process(proc, 100, 10234, "S", "11");
        assertFalse(AppUidProcesses.matches(proc, original, 10234));
        process(proc, 100, 10235, "S", "10");
        assertFalse(AppUidProcesses.matches(proc, original, 10234));
    }

    @Test public void handlesParenthesesInNameAndDisappearingProcesses() throws Exception {
        File proc = temporary.newFolder();
        process(proc, 100, 10234, "S", "1234");
        AppUidProcesses.Entry entry = AppUidProcesses.scan(proc, 10234, 999).get(0);
        assertEquals("1234", entry.startedAt);
        assertTrue(new File(proc, "100/stat").delete());
        assertFalse(AppUidProcesses.matches(proc, entry, 10234));
        assertTrue(AppUidProcesses.scan(proc, 10234, 999).isEmpty());
    }

    @Test public void rejectsMixedCredentialIdentity() throws Exception {
        File proc = temporary.newFolder();
        process(proc, 100, 10234, "S", "10");
        write(new File(proc, "100/status"), "Uid:\t10234\t0\t10234\t10234\n");
        assertTrue(AppUidProcesses.scan(proc, 10234, 999).isEmpty());
    }

    private void process(File proc, int pid, int uid, String state, String start) throws Exception {
        File directory = new File(proc, Integer.toString(pid));
        directory.mkdirs();
        write(new File(directory, "status"), "Name:\ttest\nUid:\t" + uid + "\t" + uid + "\t" + uid + "\t" + uid + "\n");
        write(new File(directory, "stat"), pid + " (a tricky ) process) " + state + " "
            + String.join(" ", Collections.nCopies(18, "0")) + " " + start + " 0\n");
    }

    private void write(File path, String value) throws Exception {
        Files.write(path.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
