package com.termux.app.localgames;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class RuntimeProcessKillerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String PREFIX = "/data/data/com.termux/files/front/containers/a/prefix";

    @Test public void killsMatchingTreeAndDetachedWineButPreservesOtherContainersAndApp() throws Exception {
        File proc = temporary.newFolder();
        process(proc, 10, 1, 100, "app", "WINEPREFIX=" + PREFIX);
        process(proc, 20, 10, 100, "wine", "WINEPREFIX=" + PREFIX);
        process(proc, 21, 20, 100, "box64", "");
        process(proc, 22, 1, 100, "wineserver", "WINEPREFIX=" + PREFIX);
        process(proc, 30, 10, 100, "wine", "WINEPREFIX=" + PREFIX + "-other");
        process(proc, 40, 10, 200, "wine", "WINEPREFIX=" + PREFIX);
        List<String> signals = new ArrayList<>();
        new RuntimeProcessKiller(proc, 10, 100, (pid, freeze) -> {
            signals.add(pid + ":" + freeze);
            if (!freeze) assertTrue(new File(proc, pid + "/status").delete());
        }).kill(Collections.singletonList(PREFIX));
        for (int pid : new int[] {20, 21, 22}) {
            assertTrue(signals.contains(pid + ":true")); assertTrue(signals.contains(pid + ":false"));
        }
        for (int pid : new int[] {10, 30, 40}) assertTrue(new File(proc, pid + "/status").isFile());
    }

    @Test public void oldTaskPidIsNotEnoughToKillAnUnrelatedProcess() throws Exception {
        File proc = temporary.newFolder(); process(proc, 20, 1, 100, "unrelated", "");
        new RuntimeProcessKiller(proc, 10, 100, (pid, freeze) -> fail("no unrelated process may be signalled"))
            .kill(Collections.singletonList(PREFIX));
    }

    @Test public void capturesChildForkedDuringFreeze() throws Exception {
        File proc = temporary.newFolder(); process(proc, 20, 10, 100, "wine", "WINEPREFIX=" + PREFIX);
        Set<Integer> killed = new HashSet<>();
        new RuntimeProcessKiller(proc, 10, 100, (pid, freeze) -> {
            if (freeze) process(proc, 21, 20, 100, "worker", "");
            else { killed.add(pid); assertTrue(new File(proc, pid + "/status").delete()); }
        }).kill(Collections.singletonList(PREFIX));
        assertEquals(new HashSet<>(Arrays.asList(20, 21)), killed);
    }

    @Test public void failureToFreezeStillKillsAlreadySelectedProcesses() throws Exception {
        File proc = temporary.newFolder(); process(proc, 20, 10, 100, "wine", "WINEPREFIX=" + PREFIX);
        List<Boolean> signals = new ArrayList<>();
        assertThrows(IOException.class, () -> new RuntimeProcessKiller(proc, 10, 100, (pid, freeze) -> {
            signals.add(freeze);
            if (freeze) throw new IOException("freeze failed");
            assertTrue(new File(proc, pid + "/status").delete());
        }).kill(Collections.singletonList(PREFIX)));
        assertEquals(Arrays.asList(true, false), signals);
    }

    @Test public void noScopeMatchesAPartialDirectoryName() {
        assertFalse(RuntimeProcessKiller.matchesPath("WINEPREFIX=" + PREFIX + "-other\0", PREFIX));
        assertFalse(RuntimeProcessKiller.matchesPath("/unrelated" + PREFIX, PREFIX));
        assertTrue(RuntimeProcessKiller.matchesPath("--bind=" + PREFIX + ":/prefix\0", PREFIX));
        assertTrue(RuntimeProcessKiller.matchesPath("\0WINEPREFIX=" + PREFIX + "\0", PREFIX));
    }

    @Test public void sessionScopeKillsDetachedProcessesButNotOtherLaunches() throws Exception {
        File proc = temporary.newFolder();
        String spec = "/data/user/0/com.termux/files/front/launches/specs/launch-one.launchspec";
        process(proc, 20, 10, 100, "sh " + spec, "");
        process(proc, 21, 20, 100, "proot", "GAMES_SESSION_SPEC=" + spec);
        process(proc, 22, 1, 100, "wineserver", "GAMES_SESSION_SPEC=" + spec);
        process(proc, 30, 10, 100, "wine", "GAMES_SESSION_SPEC=" + spec + "-other");
        Set<Integer> killed = new HashSet<>();
        new RuntimeProcessKiller(proc, 10, 100, (pid, freeze) -> {
            if (!freeze) { killed.add(pid); new File(proc, pid + "/status").delete(); }
        }).kill(Collections.singletonList(spec));
        assertEquals(new HashSet<>(Arrays.asList(20, 21, 22)), killed);
        assertTrue(new File(proc, "30/status").isFile());
    }

    @Test public void realStubbornProcessTreeStopsQuicklyAndPreservesAnotherSession() throws Exception {
        org.junit.Assume.assumeTrue(new File("/proc/self/status").isFile());
        String spec = temporary.newFile("session.launchspec").getCanonicalPath();
        ProcessBuilder ownedBuilder = new ProcessBuilder("sh", "-c", "trap '' TERM; sleep 60 & wait");
        ownedBuilder.environment().put("GAMES_SESSION_SPEC", spec);
        Process owned = ownedBuilder.start();
        ProcessBuilder otherBuilder = new ProcessBuilder("sleep", "60");
        otherBuilder.environment().put("GAMES_SESSION_SPEC", spec + "-other");
        Process other = otherBuilder.start();
        try {
            int ownPid = Integer.parseInt(new File("/proc/self").getCanonicalFile().getName());
            String status = new String(Files.readAllBytes(new File("/proc/self/status").toPath()), StandardCharsets.UTF_8);
            int uid = Integer.parseInt(status.split("Uid:\\s+")[1].split("\\s+")[0]);
            Thread.sleep(100); // Let the child inherit its session scope before scanning.
            long start = System.nanoTime();
            new RuntimeProcessKiller(new File("/proc"), ownPid, uid, (pid, freeze) -> {
                Process command = new ProcessBuilder("kill", freeze ? "-STOP" : "-KILL", Integer.toString(pid)).start();
                try { command.waitFor(); } catch (InterruptedException error) { throw new IOException(error); }
            }).kill(Collections.singletonList(spec));
            assertTrue(owned.waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue("stop exceeded short confirmation budget", System.nanoTime() - start < 3_000_000_000L);
            assertTrue("unrelated session must survive", other.isAlive());
        } finally { owned.destroyForcibly(); other.destroyForcibly(); }
    }

    private void process(File proc, int pid, int parent, int uid, String cmd, String env) throws IOException {
        File directory = new File(proc, Integer.toString(pid));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        write(new File(directory, "status"), "State:\tS (sleeping)\nPPid:\t" + parent + "\nUid:\t" + uid + "\n");
        write(new File(directory, "cmdline"), cmd + "\0"); write(new File(directory, "environ"), env + "\0");
    }
    private void write(File file, String value) throws IOException {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
