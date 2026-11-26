package com.termux.ui.ide;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public class IdeProjectModelStoreTest {
    private JSONObject model(File project, String module) throws Exception {
        return new JSONObject().put("project", project.getCanonicalPath()).put("syncedAt", 123L)
            .put("modules", new JSONArray().put(module));
    }
    @Test public void reopeningStorePreservesModelsAndSeparatesSameNamedProjects() throws Exception {
        File files = Files.createTempDirectory("ide-models-").toFile();
        File first = new File(files, "one/app"), second = new File(files, "two/app");
        IdeProjectModelStore store = new IdeProjectModelStore(files);
        assertNull(store.read(first));
        store.write(first, model(first, ":first")); store.write(second, model(second, ":second"));
        IdeProjectModelStore reopened = new IdeProjectModelStore(files);
        assertEquals(":first", reopened.read(first).getJSONArray("modules").getString(0));
        assertEquals(":second", reopened.read(second).getJSONArray("modules").getString(0));
        assertEquals(":first", reopened.read(new File(files, "one/../one/app")).getJSONArray("modules").getString(0));
    }
    @Test public void invalidUpdatePreservesPreviousSnapshotAndCorruptionIsRejected() throws Exception {
        File files = Files.createTempDirectory("ide-models-").toFile(), project = new File(files, "app");
        IdeProjectModelStore store = new IdeProjectModelStore(files);
        store.write(project, model(project, ":good"));
        try { store.write(project, model(new File(files, "other"), ":bad")); fail(); }
        catch (java.io.IOException expected) { }
        assertEquals(":good", store.read(project).getJSONArray("modules").getString(0));
        File snapshot = new File(files, "ide-project-models").listFiles()[0];
        Files.write(snapshot.toPath(), "{\"schemaVersion\":99}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try { store.read(project); fail(); } catch (java.io.IOException expected) { }
    }
}
