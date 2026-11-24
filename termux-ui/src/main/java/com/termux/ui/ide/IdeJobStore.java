package com.termux.ui.ide;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;

/** One foreground job at a time; state and logs survive Activity recreation. */
public final class IdeJobStore {
    private static final Object LOCK = new Object();
    private final File root;
    public IdeJobStore(Context context) { root = new File(context.getFilesDir(), "ide-jobs"); }
    public File root() throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create task storage");
        return root;
    }
    public File directory(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IOException("Invalid task id");
        return new File(root(), id);
    }
    public Properties read() throws IOException {
        synchronized (LOCK) {
            Properties p = new Properties();
            AtomicFile file = new AtomicFile(new File(root(), "current.properties"));
            try (InputStream in = file.openRead()) { p.load(in); } catch (FileNotFoundException ignored) { }
            return p;
        }
    }
    public void write(Properties state) throws IOException {
        synchronized (LOCK) {
            AtomicFile target = new AtomicFile(new File(root(), "current.properties"));
            FileOutputStream out = null;
            try { out = target.startWrite(); state.store(out, null); target.finishWrite(out); }
            catch (IOException e) { target.failWrite(out); throw e; }
        }
    }
    public static boolean active(Properties state) {
        return Arrays.asList("queued", "running", "cancelling").contains(state.getProperty("state"));
    }
    public String log(Properties state) throws IOException {
        String id = state.getProperty("id");
        if (id == null) return "";
        File log = new File(directory(id), "output.log");
        if (!log.isFile()) return "";
        try (RandomAccessFile in = new RandomAccessFile(log, "r")) {
            long start = Math.max(0, in.length() - 65536);
            in.seek(start);
            byte[] data = new byte[(int)(in.length() - start)]; in.readFully(data);
            return (start == 0 ? "" : "…\n") + new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    /** Stale output is moved away before each build, never inferred from mtime alone. */
    public static void isolatePreviousApks(File project, IdeWorkspace workspace, File previous) throws IOException {
        List<File> old = apks(project, workspace);
        if (!old.isEmpty() && !previous.mkdir()) throw new IOException("Cannot isolate previous APKs");
        for (int i = 0; i < old.size(); i++) if (!old.get(i).renameTo(new File(previous, i + ".apk")))
            throw new IOException("Cannot isolate old APK " + old.get(i));
    }
    public static List<File> apks(File project, IdeWorkspace workspace) throws IOException {
        List<File> result = new ArrayList<>();
        collect(project, workspace, result, 0);
        return result;
    }
    private static void collect(File directory, IdeWorkspace ws, List<File> out, int depth) throws IOException {
        if (depth > 24) return;
        for (File file : Optional.ofNullable(directory.listFiles()).orElse(new File[0])) {
            if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) continue;
            ws.checked(file);
            if (file.isDirectory()) {
                if (!file.getName().equals(".gradle") && !file.getName().equals(".git") && !file.getName().startsWith(".ide-"))
                    collect(file, ws, out, depth + 1);
            } else if (file.getName().endsWith(".apk") && file.getPath().contains(File.separator + "build" + File.separator + "outputs" + File.separator)) out.add(file);
        }
    }
}
