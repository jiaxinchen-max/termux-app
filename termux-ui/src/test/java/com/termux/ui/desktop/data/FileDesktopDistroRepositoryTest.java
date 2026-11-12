package com.termux.ui.desktop.data;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopGuestGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FileDesktopDistroRepositoryTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private FileDesktopDistroRepository repository() {
        return new FileDesktopDistroRepository(new File(temporaryFolder.getRoot(), "distros"));
    }

    private static DesktopDistro proot() {
        return new DesktopDistro("p-1", "Debian KDE", DesktopRunMode.PROOT, "debian", DesktopDe.KWIN_PLASMA, DesktopGlMode.ZINK, DesktopGuestGlMode.ZINK, "1920x1080", 60, DesktopUsbStorageMode.OFF);
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
        repository.save(new DesktopDistro("a-native", "Native", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280x720", 30, DesktopUsbStorageMode.OFF));
        List<DesktopDistro> all = repository.list();
        assertEquals(2, all.size());
        assertEquals("a-native", all.get(0).getId());
        DesktopDistro loaded = repository.find("p-1").get();
        assertEquals("Debian KDE", loaded.getName());
        assertEquals(DesktopRunMode.PROOT, loaded.getRunMode());
        assertEquals("debian", loaded.getProotDistro());
        assertEquals(DesktopGlMode.ZINK, loaded.getHostGlMode());
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


    @Test public void gnomeUsbSettingsRoundTripAndOldRecordsDefaultOff() throws Exception {
        FileDesktopDistroRepository repository=repository();
        DesktopDistro gnome=new DesktopDistro("gnome", "GNOME", DesktopRunMode.PROOT, "debian-gnome-1", DesktopDe.GNOME, DesktopGlMode.SYSTEM, DesktopGuestGlMode.LLVMPIPE, "1280x720", 60, DesktopUsbStorageMode.OFF).withUsbStorage(DesktopUsbStorageMode.READ_ONLY);
        repository.save(gnome);
        assertEquals(DesktopDe.GNOME,repository.find("gnome").get().getDe());
        assertEquals(DesktopUsbStorageMode.READ_ONLY,repository.find("gnome").get().getUsbStorage());
        File file=new File(temporaryFolder.getRoot(),"distros/gnome.properties");
        Properties props=new Properties();
        try(java.io.FileInputStream in=new java.io.FileInputStream(file)){props.load(in);}
        props.remove("usbStorage");
        try(FileOutputStream out=new FileOutputStream(file)){props.store(out,null);}
        assertEquals(DesktopUsbStorageMode.OFF,repository.find("gnome").get().getUsbStorage());
        props.setProperty("usbStorage","read-write");
        try(FileOutputStream out=new FileOutputStream(file)){props.store(out,null);}
        assertThrows(IOException.class,()->repository.find("gnome"));
    }


    @Test public void splitGnomePairAndCopiesPreserveUsbAndIndependentModes() throws Exception {
        DesktopDistro g = new DesktopDistro("g", "GNOME", DesktopRunMode.PROOT, "debian-gnome-1",
            DesktopDe.GNOME, DesktopGlMode.SYSTEM, DesktopGuestGlMode.LLVMPIPE, "1280x720", 60,
            DesktopUsbStorageMode.READ_ONLY).withName("Renamed").withOutput("1920x1080",90);
        FileDesktopDistroRepository r = repository(); r.save(g);
        DesktopDistro read = r.find("g").get();
        assertEquals(DesktopGlMode.SYSTEM, read.getHostGlMode());
        assertEquals(DesktopGuestGlMode.LLVMPIPE, read.getGuestGlMode());
        assertEquals(DesktopUsbStorageMode.READ_ONLY, read.getUsbStorage());
        assertEquals(90, read.getRefreshRate());
        assertEquals("Renamed", read.getName());
    }

    @Test public void oldSchemasAndMixedFieldsAreRejectedWithoutRewrite() throws Exception {
        FileDesktopDistroRepository repository = repository(); repository.save(proot());
        File file = new File(temporaryFolder.getRoot(), "distros/p-1.properties");
        Properties current = new Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) { current.load(in); }
        for (String schema : new String[]{"1", "2", "99"}) {
            current.setProperty("schemaVersion", schema);
            try (FileOutputStream out = new FileOutputStream(file)) { current.store(out, null); }
            byte[] before = java.nio.file.Files.readAllBytes(file.toPath());
            assertThrows(IOException.class, () -> repository.find("p-1"));
            org.junit.Assert.assertArrayEquals(before, java.nio.file.Files.readAllBytes(file.toPath()));
        }
        current.setProperty("schemaVersion", "3"); current.setProperty("glMode", "system");
        try (FileOutputStream out = new FileOutputStream(file)) { current.store(out, null); }
        assertThrows(IOException.class, () -> repository.find("p-1"));
        assertEquals(1, file.getParentFile().list().length);
    }
}
