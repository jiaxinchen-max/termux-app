package com.termux.app.localgames;

import android.content.Context;
import android.system.Os;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.termux.ui.wine.recovery.ReadOnlyTreeMeasure;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReadOnlyTreeMeasureInstrumentedTest {
    @Test public void doesNotFollowLinksOrCountSameFileTwice() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File directory = new File(context.getCacheDir(), "inventory-test-" + System.nanoTime());
        assertTrue(directory.mkdir());
        File source = new File(directory, "source");
        File symlink = new File(directory, "symlink");
        try {
            try (FileOutputStream output = new FileOutputStream(source)) { output.write(new byte[4096]); }
            Os.symlink("/proc", symlink.getPath());
            ReadOnlyTreeMeasure measure = new ReadOnlyTreeMeasure();
            ReadOnlyTreeMeasure.Stats stats = new ReadOnlyTreeMeasure.Stats();
            measure.add(directory, stats);
            assertEquals(4096 + "/proc".length(), stats.bytes);
            assertEquals(2, stats.files);
            assertFalse(stats.incomplete);
            ReadOnlyTreeMeasure.Stats repeated = new ReadOnlyTreeMeasure.Stats();
            measure.add(source, repeated);
            assertEquals(0, repeated.bytes);
            assertTrue(source.isFile());
        } finally {
            symlink.delete(); source.delete(); directory.delete();
        }
    }
}
