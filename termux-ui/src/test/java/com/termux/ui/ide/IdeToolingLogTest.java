package com.termux.ui.ide;

import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class IdeToolingLogTest {
    @Test public void oversizedNotificationAndFollowingOutputStayBounded() throws Exception {
        File folder = Files.createTempDirectory("ide-tooling-log-").toFile();
        try {
            try (IdeToolingLog log = new IdeToolingLog(folder)) {
                byte[] huge = new byte[3 * 1024 * 1024]; Arrays.fill(huge, (byte) 'x');
                log.write(huge, huge.length);
                assertEquals(2 * 1024 * 1024, new File(folder, "output.log").length());
                log.write(new byte[] {65}, 1);
                assertEquals(1, new File(folder, "output.log").length());
                assertEquals(2 * 1024 * 1024, new File(folder, "output.previous.log").length());
            }
        } finally { for (File file : folder.listFiles()) assertTrue(file.delete()); assertTrue(folder.delete()); }
    }
}
