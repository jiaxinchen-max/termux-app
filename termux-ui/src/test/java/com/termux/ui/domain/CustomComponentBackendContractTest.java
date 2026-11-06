package com.termux.ui.domain;

import com.termux.ui.data.CustomComponentInstallSpecCodec;
import com.termux.ui.data.FileCustomComponentInstallTaskRepository;
import com.termux.ui.data.FileCustomRuntimeComponentRepository;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeTranslator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

import static org.junit.Assert.*;

public final class CustomComponentBackendContractTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nativeAllKindsRoundTripWithExplicitBackend() throws Exception {
        FileCustomRuntimeComponentRepository repository =
            new FileCustomRuntimeComponentRepository(temporary.newFolder());
        for (CustomRuntimeComponent.Kind kind : CustomRuntimeComponent.Kind.values()) {
            CustomRuntimeComponent component = component(kind, GameRuntimeBackendType.GLIBC_TERMUX_BOX);
            repository.save(component);
            CustomRuntimeComponent loaded = repository.find(component.getId()).get();
            assertEquals(kind, loaded.getKind());
            assertEquals(GameRuntimeBackendType.GLIBC_TERMUX_BOX, loaded.getBackendType());
            assertEquals(SHA, loaded.getSha256());
        }
        assertEquals(5, repository.list().size());
    }

    @Test public void rootfsRejectsUnsupportedKindsAndMissingWineTranslator() {
        for (CustomRuntimeComponent.Kind kind : new CustomRuntimeComponent.Kind[] {
                CustomRuntimeComponent.Kind.VKD3D, CustomRuntimeComponent.Kind.TURNIP}) {
            assertThrows(IllegalArgumentException.class, () -> component(kind, GameRuntimeBackendType.ROOTFS_PROOT));
        }
        assertThrows(IllegalArgumentException.class, () -> new CustomRuntimeComponent(
            "custom-wine-test", CustomRuntimeComponent.Kind.WINE, null, "test", 1, SHA,
            GameRuntimeBackendType.ROOTFS_PROOT));
    }

    @Test public void nullBackendNeverSelectsMode() {
        assertThrows(IllegalArgumentException.class, () -> new CustomRuntimeComponent(
            "custom-box64-test", CustomRuntimeComponent.Kind.BOX64, null, "test", 1, SHA, null));
        assertThrows(IllegalArgumentException.class, () -> CustomComponentInstallTask.queued(
            "install-test", "custom-box64-test", CustomRuntimeComponent.Kind.BOX64, null,
            "test", SHA, 1, null));
    }

    @Test public void nativeWineRejectsRootfsTranslatorInsteadOfChangingBackend() {
        assertThrows(IllegalArgumentException.class, () -> new CustomRuntimeComponent(
            "custom-wine-test", CustomRuntimeComponent.Kind.WINE, RuntimeTranslator.BOX64,
            "test", 1, SHA, GameRuntimeBackendType.GLIBC_TERMUX_BOX));
    }

    @Test public void eachKindRequiresItsOwnIdPrefix() {
        for (CustomRuntimeComponent.Kind kind : CustomRuntimeComponent.Kind.values()) {
            assertThrows(IllegalArgumentException.class, () -> new CustomRuntimeComponent(
                "custom-wrong-test", kind, null, "test", 1, SHA,
                GameRuntimeBackendType.GLIBC_TERMUX_BOX));
        }
    }

    @Test public void registryMissingBackendFailsRatherThanInferringFromTranslator() throws Exception {
        File directory = temporary.newFolder();
        FileCustomRuntimeComponentRepository repository = new FileCustomRuntimeComponentRepository(directory);
        CustomRuntimeComponent component = component(CustomRuntimeComponent.Kind.BOX64,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        repository.save(component);
        File record = new File(directory, component.getId() + ".properties");
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(record)) { properties.load(input); }
        properties.remove("runtimeBackend");
        try (FileOutputStream output = new FileOutputStream(record)) { properties.store(output, "test"); }
        assertThrows(IOException.class, () -> repository.find(component.getId()));
    }

    @Test public void taskTransitionAndPersistencePreserveBackend() throws Exception {
        FileCustomComponentInstallTaskRepository repository =
            new FileCustomComponentInstallTaskRepository(temporary.newFolder());
        CustomComponentInstallTask task = CustomComponentInstallTask.queued("install-test",
            "custom-dxvk-test", CustomRuntimeComponent.Kind.DXVK, null, "DXVK", SHA, 1,
            GameRuntimeBackendType.ROOTFS_PROOT);
        repository.save(task.transition(CustomComponentInstallTaskState.RUNNING, "", 2));
        CustomComponentInstallTask loaded = repository.find("install-test").get();
        assertEquals(GameRuntimeBackendType.ROOTFS_PROOT, loaded.getBackendType());
        assertEquals(CustomComponentInstallTaskState.RUNNING, loaded.getState());
    }

    @Test public void specSerializesEveryKindBackendAndPayloadHash() throws Exception {
        for (CustomRuntimeComponent.Kind kind : CustomRuntimeComponent.Kind.values()) {
            CustomRuntimeComponent component = component(kind, GameRuntimeBackendType.GLIBC_TERMUX_BOX);
            CustomComponentInstallTask task = CustomComponentInstallTask.queued("install-test",
                component.getId(), kind, null, "test", SHA, 1, component.getBackendType());
            File spec = temporary.newFile();
            new CustomComponentInstallSpecCodec().write(spec, task, temporary.newFile(),
                temporary.newFile(), temporary.newFile());
            Properties values = new Properties();
            try (FileInputStream input = new FileInputStream(spec)) { values.load(input); }
            assertEquals(kind.name().toLowerCase(Locale.ROOT), values.getProperty("kind"));
            assertEquals(kind.name().toLowerCase(Locale.ROOT) + "-tree", values.getProperty("payloadKind"));
            assertEquals("glibc_termux_box", values.getProperty("runtimeBackend"));
            assertEquals(SHA, values.getProperty("payloadSha256"));
            assertEquals("none", values.getProperty("runtimeTranslator"));
        }
    }

    @Test public void specPersistsRootfsWineTranslatorExplicitly() throws Exception {
        for (RuntimeTranslator translator : new RuntimeTranslator[] {RuntimeTranslator.BOX64, RuntimeTranslator.HANGOVER}) {
            CustomComponentInstallTask task = CustomComponentInstallTask.queued("install-test",
                "custom-wine-test", CustomRuntimeComponent.Kind.WINE, translator, "test", SHA, 1,
                GameRuntimeBackendType.ROOTFS_PROOT);
            File spec = temporary.newFile();
            new CustomComponentInstallSpecCodec().write(spec, task, temporary.newFile(),
                temporary.newFile(), temporary.newFile());
            Properties values = new Properties();
            try (FileInputStream input = new FileInputStream(spec)) { values.load(input); }
            assertEquals("rootfs_proot", values.getProperty("runtimeBackend"));
            assertEquals(translator.getStorageValue(), values.getProperty("runtimeTranslator"));
        }
    }

    private static CustomRuntimeComponent component(CustomRuntimeComponent.Kind kind,
            GameRuntimeBackendType backend) {
        RuntimeTranslator translator = kind == CustomRuntimeComponent.Kind.WINE &&
            backend == GameRuntimeBackendType.ROOTFS_PROOT ? RuntimeTranslator.BOX64 : null;
        return new CustomRuntimeComponent("custom-" + kind.name().toLowerCase(Locale.ROOT) + "-test",
            kind, translator, "test", 1, SHA, backend);
    }
}
