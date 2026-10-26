package com.termux.ui.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.termux.ui.domain.DesktopDe;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopGlMode;
import com.termux.ui.domain.DesktopRunMode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

public class FileDesktopDistroRepositoryTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private FileDesktopDistroRepository repository() {
        return new FileDesktopDistroRepository(new File(temporaryFolder.getRoot(), "distros"));
    }

    private static DesktopDistro proot() {
        return new DesktopDistro("p-1", "Debian KDE", DesktopRunMode.PROOT, "debian",
            DesktopGlMode.ZINK, "1920x1080", 60);
    }

    @Test
    public void emptyWhenDirectoryMissing() throws Exception {
        assertTrue(repository().list().isEmpty());
        assertFalse(repository().find("nope").isPresent());
    }

    @Test
    public void roundTripsAllFields() throws Exception {
        FileDesktopDistroRepository repository = repository();
        repository.save(proot());
        repository.save(new DesktopDistro("a-native", "Native", DesktopRunMode.NATIVE, "",
            DesktopGlMode.SYSTEM, "1280x720", 30));
        List<DesktopDistro> all = repository.list();
        assertEquals(2, all.size());
        assertEquals("a-native", all.get(0).getId());
        DesktopDistro loaded = repository.find("p-1").get();
        assertEquals("Debian KDE", loaded.getName());
        assertEquals(DesktopRunMode.PROOT, loaded.getRunMode());
        assertEquals("debian", loaded.getProotDistro());
        assertEquals(DesktopGlMode.ZINK, loaded.getGlMode());
        assertEquals("1920x1080", loaded.getResolution());
        assertEquals(60, loaded.getRefreshRate());
    }

    @Test
    public void saveReplacesAndDeleteRemoves() throws Exception {
        FileDesktopDistroRepository repository = repository();
        repository.save(proot());
        repository.save(proot().withName("Renamed"));
        assertEquals("Renamed", repository.find("p-1").get().getName());
        repository.delete("p-1");
        assertFalse(repository.find("p-1").isPresent());
        repository.delete("p-1");
    }

    @Test
    public void rejectsUnknownFieldsAndWrongSchema() throws Exception {
        FileDesktopDistroRepository repository = repository();
        repository.save(proot());
        File file = new File(new File(temporaryFolder.getRoot(), "distros"), "p-1.properties");
        Properties properties = new Properties();
        properties.load(new java.io.FileInputStream(file));
        properties.setProperty("extra", "x");
        try (FileOutputStream output = new FileOutputStream(file)) { properties.store(output, null); }
        assertThrows(IOException.class, () -> repository.find("p-1"));
        properties.remove("extra");
        properties.setProperty("schemaVersion", "99");
        try (FileOutputStream output = new FileOutputStream(file)) { properties.store(output, null); }
        assertThrows(IOException.class, () -> repository.find("p-1"));
    }

    @Test
    public void rejectsFileWhoseNameDisagreesWithItsId() throws Exception {
        FileDesktopDistroRepository repository = repository();
        repository.save(proot());
        File directory = new File(temporaryFolder.getRoot(), "distros");
        assertTrue(new File(directory, "p-1.properties").renameTo(new File(directory, "other.properties")));
        assertThrows(IOException.class, () -> repository.find("other"));
    }

    @Test
    public void rejectsUnsafeIds() {
        assertThrows(IllegalArgumentException.class, () -> repository().find("../x"));
    }

    @Test
    public void schema1FilesMigrateToPlasma() throws Exception {
        FileDesktopDistroRepository repository = repository();
        File directory = new File(temporaryFolder.getRoot(), "distros");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, "old.properties");
        FileOutputStream out = new FileOutputStream(file);
        out.write(("schemaVersion=1\nid=old\nname=Plasma\nrunMode=native\nprootDistro=\n"
            + "glMode=system\nresolution=1280x720\nrefreshRate=30\n").getBytes("UTF-8"));
        out.close();

        DesktopDistro migrated = repository.find("old").get();

        assertEquals(DesktopDe.KWIN_PLASMA, migrated.getDe());
        assertEquals(2, DesktopDistro.SCHEMA_VERSION);
        // Saving rewrites the file at the current schema.
        repository.save(migrated);
        DesktopDistro reread = repository.find("old").get();
        assertEquals(DesktopDe.KWIN_PLASMA, reread.getDe());
    }
}
