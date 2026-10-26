package com.termux.ui.data;

import com.termux.ui.domain.DesktopDe;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopGlMode;
import com.termux.ui.domain.DesktopRunMode;

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

    private static final String SUFFIX = ".properties";
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList("schemaVersion", "id",
        "name", "runMode", "prootDistro", "de", "glMode", "resolution", "refreshRate"));

    private final File directory;

    public FileDesktopDistroRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("desktopDirectoryRequired");
        this.directory = directory;
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

    private DesktopDistro read(File file) throws IOException {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) { properties.load(input); }
        String schema = properties.getProperty("schemaVersion");
        // Schema 1 predates the desktop-environment field: those desktops only ran Plasma.
        if ("1".equals(schema)) {
            properties.setProperty("de", DesktopDe.legacyDefault().getStorageValue());
            schema = String.valueOf(DesktopDistro.SCHEMA_VERSION);
        }
        if (!String.valueOf(DesktopDistro.SCHEMA_VERSION).equals(schema)) {
            throw new IOException("unsupported_desktop_schema");
        }
        for (Object key : properties.keySet()) {
            if (!KEYS.contains(key.toString())) throw new IOException("unknown_desktop_field");
        }
        try {
            DesktopDistro distro = new DesktopDistro(required(properties, "id"),
                required(properties, "name"),
                DesktopRunMode.fromStorageValue(required(properties, "runMode")),
                properties.getProperty("prootDistro", ""),
                DesktopDe.fromStorageValue(required(properties, "de")),
                DesktopGlMode.fromStorageValue(required(properties, "glMode")),
                required(properties, "resolution"),
                Integer.parseInt(required(properties, "refreshRate")));
            if (!file.getName().equals(distro.getId() + SUFFIX)) {
                throw new IOException("desktop_id_file_mismatch");
            }
            return distro;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_desktop", error);
        }
    }

    private static Properties encode(DesktopDistro distro) {
        Properties result = new Properties();
        result.setProperty("schemaVersion", Integer.toString(DesktopDistro.SCHEMA_VERSION));
        result.setProperty("id", distro.getId());
        result.setProperty("name", distro.getName());
        result.setProperty("runMode", distro.getRunMode().getStorageValue());
        result.setProperty("prootDistro", distro.getProotDistro());
        result.setProperty("de", distro.getDe().getStorageValue());
        result.setProperty("glMode", distro.getGlMode().getStorageValue());
        result.setProperty("resolution", distro.getResolution());
        result.setProperty("refreshRate", Integer.toString(distro.getRefreshRate()));
        return result;
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
