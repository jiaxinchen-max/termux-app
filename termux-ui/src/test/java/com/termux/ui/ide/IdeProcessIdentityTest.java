package com.termux.ui.ide;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class IdeProcessIdentityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    @Test public void malformedOrUnrelatedProcessCannotBeSignalled() {
        File runner = new File("/private/task/runner.sh");
        assertFalse(IdeProcessIdentity.isTaskLeader(1, runner.getPath(), "", runner));
        assertFalse(IdeProcessIdentity.isTaskLeader(10, "bash\0" + runner + ".other\0", "10 (bash) S 1 10 10 0 0", runner));
        assertFalse(IdeProcessIdentity.isTaskLeader(10, "bash\0" + runner + "\0", "10 (bash) S 1 8 8 0 0", runner));
        assertFalse(IdeProcessIdentity.isTaskLeader(10, "bash\0" + runner + "\0", "bad proc data", runner));
    }
    @Test public void privateGroupCancellationKillsTaskButPreservesUnrelatedProcess() throws Exception {
        Assume.assumeTrue(new File("/usr/bin/setsid").canExecute() && new File("/proc/self/stat").isFile());
        File directory = temporary.newFolder("job"); File runner = new File(directory, "runner.sh");
        Files.write(runner.toPath(), IdeProcessIdentity.RUNNER_SCRIPT.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, "toolchain.sh").toPath(), "sleep 60 &\nwait\n".getBytes(StandardCharsets.UTF_8));
        Process unrelated = new ProcessBuilder("sleep", "60").start();
        ProcessBuilder launch = new ProcessBuilder("setsid", "bash", runner.getPath(), "build");
        launch.environment().put("IDE_JOB_DIR", directory.getPath());
        Process task = launch.start(); int pid = 0;
        try {
            File pidfile = new File(directory, "pid");
            for (int i = 0; i < 100 && !pidfile.isFile(); i++) Thread.sleep(10);
            pid = Integer.parseInt(read(pidfile));
            assertTrue(IdeProcessIdentity.isTaskLeader(pid, read(new File("/proc/" + pid + "/cmdline")),
                read(new File("/proc/" + pid + "/stat")), runner));
            assertEquals(0, new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + pid).start().waitFor());
            assertTrue(task.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, task.exitValue());
            assertTrue(unrelated.isAlive());
        } finally {
            if (pid > 1) new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + pid).start().waitFor();
            task.destroyForcibly(); unrelated.destroyForcibly();
        }
    }
}
