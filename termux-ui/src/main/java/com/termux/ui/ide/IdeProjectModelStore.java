package com.termux.ui.ide;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Private per-project snapshots; a failed synchronization never replaces the last good model.
 * Readers must still synchronize after Gradle configuration changes before using semantic results.
 */
public final class IdeProjectModelStore {
    private static final Object LOCK = new Object();
    private static final int LIMIT = 16 * 1024 * 1024;
    private final File root;
    public IdeProjectModelStore(File filesDirectory) {
        root = new File(filesDirectory, "ide-project-models");
    }
    public void write(File project, JSONObject model) throws Exception {
        synchronized (LOCK) { writeSnapshot(project, model); }
    }
    private void writeSnapshot(File project, JSONObject model) throws Exception {
        String path = project.getCanonicalPath();
        if (!path.equals(model.getString("project"))) throw new IOException("Project model identity mismatch");
        model.getJSONArray("modules");
        byte[] bytes = new JSONObject().put("schemaVersion", 1).put("model", model)
            .toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("Project model exceeds size limit");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create model storage");
        AtomicFile target = file(path);
        FileOutputStream output = null;
        try {
            output = target.startWrite(); output.write(bytes); target.finishWrite(output);
        } catch (Exception error) { target.failWrite(output); throw error; }
    }
    /** Null means this project has never completed a synchronization. Malformed caches fail closed. */
    public JSONObject read(File project) throws Exception {
        synchronized (LOCK) { return readSnapshot(project); }
    }
    private JSONObject readSnapshot(File project) throws Exception {
        String path = project.getCanonicalPath();
        byte[] buffer = new byte[8192];
        try (InputStream input = file(path).openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > LIMIT) throw new IOException("Project model exceeds size limit");
                bytes.write(buffer, 0, count);
            }
            JSONObject envelope = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (envelope.getInt("schemaVersion") != 1) throw new IOException("Unsupported project model schema");
            JSONObject model = envelope.getJSONObject("model");
            if (!path.equals(model.getString("project"))) throw new IOException("Project model identity mismatch");
            model.getJSONArray("modules");
            return model;
        } catch (FileNotFoundException absent) { return null; }
    }
    private AtomicFile file(String project) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(project.getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for (byte value : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return new AtomicFile(new File(root, key + ".json"));
    }
}
