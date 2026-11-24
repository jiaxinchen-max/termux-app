package com.termux.ui.ide;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native projects live only below this private, canonical root. No project scripts run here. */
public final class IdeWorkspace {
    public final File root;
    public IdeWorkspace(File root) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create workspace");
        this.root = root.getCanonicalFile();
    }
    public File checked(File file) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!canonical.equals(root) && !canonical.getPath().startsWith(root.getPath() + File.separator))
            throw new IOException("Path leaves workspace");
        return canonical;
    }
    public File project(String name) throws IOException {
        if (!name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} _.-]{0,79}") || name.equals(".."))
            throw new IOException("Invalid project name");
        return checked(new File(root, name));
    }
    public File owner(File file) throws IOException {
        File cursor = checked(file);
        while (!cursor.equals(root)) {
            if (cursor.getParentFile().equals(root)) return cursor;
            cursor = cursor.getParentFile();
        }
        return null;
    }
    public List<File> projects() throws IOException {
        File[] files = root.listFiles();
        if (files == null) throw new IOException("Cannot read workspace");
        List<File> projects = new ArrayList<>();
        for (File file : files) {
            if (file.getName().startsWith(".")) continue;
            try { if (checked(file).isDirectory()) projects.add(file); } catch (IOException ignored) { }
        }
        projects.sort((a, b) -> {
            int time = Long.compare(created(b), created(a));
            return time != 0 ? time : a.getName().compareToIgnoreCase(b.getName());
        });
        return projects;
    }
    private long created(File file) {
        try {
            File metadata = checked(new File(file, ".termux-ide.properties"));
            if (metadata.length() > 4096) return 0;
            try (InputStream input = new FileInputStream(metadata)) {
            Properties p = new Properties(); p.load(input);
            return Long.parseLong(p.getProperty("created", "0"));
            }
        } catch (Exception ignored) { return 0; }
    }
    public void markCreated(File project) throws IOException {
        write(new File(checked(project), ".termux-ide.properties"), "created=" + System.currentTimeMillis() + "\n");
    }
    public void write(File file, String value) throws IOException {
        File target = checked(file);
        if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
            throw new IOException("Cannot create parent directory");
        try (OutputStream out = new FileOutputStream(target)) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
    }
    /** Renames first, so a failed recursive delete cannot leave a half-visible project. */
    public void deleteProject(File project) throws IOException {
        File target = checked(project);
        if (owner(target) == null || !target.getParentFile().equals(root)) throw new IOException("Not a project");
        File trash = new File(root, ".deleted-" + UUID.randomUUID());
        if (!target.renameTo(trash)) throw new IOException("Cannot move project to trash");
        deleteTree(trash);
    }
    public void deleteTree(File file) throws IOException {
        // Do not follow symlinks, even symlinks pointing back into the workspace.
        File absolute = file.getAbsoluteFile();
        if (!absolute.getPath().startsWith(root.getPath() + File.separator)) throw new IOException("Invalid delete");
        if (absolute.equals(file.getCanonicalFile()) && file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null) throw new IOException("Cannot list directory");
            for (File child : files) deleteTree(child);
        }
        if (!file.delete() && file.exists()) throw new IOException("Cannot delete " + file.getName());
    }
    public File importTarget(File staging, String relative) throws IOException {
        File base = checked(staging);
        File target = checked(new File(base, relative));
        if (new File(relative).isAbsolute() || !target.getPath().startsWith(base.getPath() + File.separator))
            throw new IOException("Invalid imported path");
        return target;
    }
}
