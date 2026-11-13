package com.termux.ui.desktop.data;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.HyprlandComponents;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopGuestGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** One durable, validated properties file per desktop distribution. */
public final class FileDesktopDistroRepository implements DesktopDistroRepository {

    public interface NewRecordIdentity {
        void created(File temporary, long device, long inode) throws IOException;
    }

    private static final String SUFFIX = ".properties";
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList("schemaVersion", "id",
        "name", "runMode", "prootDistro", "de", "hostGlMode", "guestGlMode", "resolution", "refreshRate", "usbStorage", "hyprlandRuntimeId", "hyprlandThemeId"));

    private final File directory;
    private final File home;

    public FileDesktopDistroRepository(File directory) {
        this(directory, defaultHome(directory));
    }

    private static File defaultHome(File directory) {
        if (directory == null) throw new IllegalArgumentException("desktopDirectoryRequired");
        File ancestor = directory.getAbsoluteFile();
        for (int i = 0; i < 3 && ancestor.getParentFile() != null; i++) ancestor = ancestor.getParentFile();
        return new File(ancestor, "home");
    }

    public FileDesktopDistroRepository(File directory, File home) {
        if (directory == null) throw new IllegalArgumentException("desktopDirectoryRequired");
        this.directory = directory;
        this.home = home;
    }

    @Override public synchronized List<DesktopDistro> list() throws IOException {
        if (!directory.exists()) return Collections.emptyList();
        File[] files = directory.listFiles((parent, name) -> name.endsWith(SUFFIX));
        if (files == null) throw new IOException("desktop_repository_unreadable");
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<DesktopDistro> result = new ArrayList<>();
        for (File file : files) result.add(read(file));
        return Collections.unmodifiableList(result);
    }

    @Override public synchronized Optional<DesktopDistro> find(String distroId) throws IOException {
        File file = fileFor(distroId);
        return file.isFile() ? Optional.of(read(file)) : Optional.empty();
    }

    @Override public synchronized void save(DesktopDistro distro) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("desktop_repository_create_failed");
        }
        File target = fileFor(distro.getId());
        File temporary = new File(directory, distro.getId() + SUFFIX + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            encode(distro).store(output, null);
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("desktop_publish_failed");
        }
    }

    @Override public synchronized void delete(String distroId) throws IOException {
        File file = fileFor(distroId);
        if (file.exists() && !file.delete()) throw new IOException("desktop_delete_failed");
    }

    /** Publishes a new record without replacement; persist its identity before writing bytes. */
    public synchronized void saveNew(DesktopDistro distro, NewRecordIdentity identity) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("desktop_repository_create_failed");
        }
        File target = fileFor(distro.getId());
        File temporary = new File(directory, distro.getId() + ".new-" + java.util.UUID.randomUUID());
        com.termux.ui.desktop.archive.DesktopArchiveFileSystem fs =
            new com.termux.ui.desktop.archive.AndroidDesktopArchiveFileSystem();
        com.termux.ui.desktop.archive.DesktopArchiveFileSystem.Stat created = null;
        try {
            try (com.termux.ui.desktop.archive.DesktopArchiveFileSystem.ExclusiveOutput output =
                     fs.createExclusiveFile(temporary)) {
                created = output.getIdentity();
                identity.created(temporary, created.device, created.inode);
                encode(distro).store(output, null);
                output.sync();
            }
            com.termux.shared.file.AtomicFilePublisher.moveNoReplace(temporary, target);
        } finally {
            if (created != null) {
                com.termux.ui.desktop.archive.DesktopArchiveFileSystem.Stat current = fs.lstat(temporary);
                if (current != null && current.kind == com.termux.ui.desktop.archive.DesktopArchiveFileSystem.Kind.FILE
                        && current.device == created.device && current.inode == created.inode
                        && !temporary.delete()) throw new IOException("desktop_inventory_temporary_cleanup_failed");
            }
        }
    }

    private DesktopDistro read(File file) throws IOException {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) { properties.load(input); }
        String schema = properties.getProperty("schemaVersion");
        if (!"3".equals(schema) && !"4".equals(schema))
            throw new IOException("unsupported_desktop_schema");
        for (Object key : properties.keySet()) {
            if (!KEYS.contains(key.toString())) throw new IOException("unknown_desktop_field");
        }
        try {
            DesktopRunMode mode = DesktopRunMode.fromStorageValue(required(properties, "runMode"));
            DesktopDe de = DesktopDe.fromStorageValue(required(properties, "de"));
            if (("4".equals(schema) && de != DesktopDe.HYPRLAND)
                    || ("3".equals(schema) && (properties.containsKey("hyprlandRuntimeId")
                        || properties.containsKey("hyprlandThemeId"))))
                throw new IOException("invalid_desktop_component_schema");
            String runtime = de != DesktopDe.HYPRLAND ? "" : "4".equals(schema)
                ? required(properties, "hyprlandRuntimeId") : HyprlandComponents.RUNTIME_ID;
            String theme = de != DesktopDe.HYPRLAND ? "" : "4".equals(schema)
                ? required(properties, "hyprlandThemeId") : legacyTheme(required(properties, "id"));
            DesktopGlMode host = DesktopGlMode.fromStorageValue(required(properties, "hostGlMode"));
            DesktopGuestGlMode guest = DesktopGuestGlMode.fromStorageValue(required(properties, "guestGlMode"));
            DesktopDistro distro = new DesktopDistro(required(properties, "id"),
                required(properties, "name"), mode, properties.getProperty("prootDistro", ""), de,
                host, guest, required(properties, "resolution"),
                Integer.parseInt(required(properties, "refreshRate")),
                DesktopUsbStorageMode.fromStorageValue(properties.getProperty("usbStorage", "off")), runtime, theme);
            if (!file.getName().equals(distro.getId() + SUFFIX)) {
                throw new IOException("desktop_id_file_mismatch");
            }
            if (de == DesktopDe.HYPRLAND && "3".equals(schema)) {
                File backup = new File(file.getPath() + ".schema3-backup");
                if (!backup.exists()) java.nio.file.Files.copy(file.toPath(), backup.toPath());
                else if (!java.util.Arrays.equals(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.file.Files.readAllBytes(backup.toPath())))
                    throw new IOException("desktop_migration_backup_conflict");
                save(distro);
            }
            return distro;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_desktop", error);
        }
    }

    private static Properties encode(DesktopDistro distro) {
        Properties result = new Properties();
        result.setProperty("schemaVersion", Integer.toString(distro.getSchemaVersion()));
        result.setProperty("id", distro.getId());
        result.setProperty("name", distro.getName());
        result.setProperty("runMode", distro.getRunMode().getStorageValue());
        result.setProperty("prootDistro", distro.getProotDistro());
        result.setProperty("de", distro.getDe().getStorageValue());
        result.setProperty("hostGlMode", distro.getHostGlMode().getStorageValue());
        result.setProperty("guestGlMode", distro.getGuestGlMode().getStorageValue());
        result.setProperty("resolution", distro.getResolution());
        result.setProperty("refreshRate", Integer.toString(distro.getRefreshRate()));
        result.setProperty("usbStorage", distro.getUsbStorage().getStorageValue());
        if (distro.getDe() == DesktopDe.HYPRLAND) {
            result.setProperty("hyprlandRuntimeId", distro.getHyprlandRuntimeId());
            result.setProperty("hyprlandThemeId", distro.getHyprlandThemeId());
        }
        return result;
    }

    private String legacyTheme(String id) throws IOException {
        if (!DesktopDistro.isValidId(id)) throw new IOException("invalidDesktopId");
        File config = new File(home, ".termux/hyprland-" + id + "/config");
        File marker = new File(config, "desktop-theme");
        if (java.nio.file.Files.isSymbolicLink(marker.toPath())) throw new IOException("unsafe_theme_marker");
        if (marker.exists()) {
            if (marker.length() > 128) throw new IOException("invalid_theme_marker");
            String value = new String(java.nio.file.Files.readAllBytes(marker.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim();
            if ("r7".equals(value) || HyprlandComponents.CLASSIC.equals(value)) return HyprlandComponents.CLASSIC;
            if (HyprlandComponents.DEFAULT_THEME.equals(value)) return value;
            throw new IOException("unknown_hyprland_theme_marker");
        }
        File lua = new File(config, "hyprland.lua");
        if (!lua.exists()) return HyprlandComponents.DEFAULT_THEME;
        if (java.nio.file.Files.isSymbolicLink(lua.toPath()) || lua.length() > 1024 * 1024)
            return HyprlandComponents.CLASSIC;
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(lua.toPath()));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return "e0de98acf8ad690a0bcfb0e0814b58630d5e6919ab9f1c59d11068262cf1bbd8".equals(hex.toString())
                ? HyprlandComponents.DEFAULT_THEME : HyprlandComponents.CLASSIC;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private File fileFor(String id) {
        if (!DesktopDistro.isValidId(id)) throw new IllegalArgumentException("invalidDesktopId");
        return new File(directory, id + SUFFIX);
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isEmpty()) throw new IOException("missing_desktop_field:" + key);
        return value;
    }
}
