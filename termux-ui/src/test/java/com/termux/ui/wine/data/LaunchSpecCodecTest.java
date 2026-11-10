package com.termux.ui.wine.data;

import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.LaunchSpec;
import com.termux.ui.wine.domain.ProotComponentBinding;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LaunchSpecCodecTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void preservesSpecialCharactersAsIndependentValues() throws Exception {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("DOLLAR", "$HOME and 'quotes'");
        LaunchSpec expected = spec(environment);
        File target = new File(temporary.newFolder("specs with space"), "task.launchspec");
        LaunchSpecCodec codec = new LaunchSpecCodec();

        codec.write(target, expected);
        LaunchSpec restored = codec.read(target);

        assertEquals("Game $1/it's game.exe", restored.getExecutable());
        assertEquals(Arrays.asList("--name=hello world", "$HOME", "it's-safe", "back\\slash"),
            restored.getArguments());
        assertEquals("$HOME and 'quotes'", restored.getEnvironment().get("DOLLAR"));
        assertEquals("dinput:3", restored.getInputProfileId());
        assertEquals(LaunchExecutionMode.TERMINAL_SESSION,
            restored.getLaunchExecutionMode());
        assertEquals(codec.fingerprint(expected), codec.fingerprint(restored));
        String raw = new String(Files.readAllBytes(target.toPath()), StandardCharsets.US_ASCII);
        assertFalse(raw.contains("hello world"));
        assertFalse(raw.contains("$HOME"));
    }

    @Test(expected = IOException.class)
    public void rejectsUnknownField() throws Exception {
        File target = temporary.newFile("task.launchspec");
        LaunchSpecCodec codec = new LaunchSpecCodec();
        codec.write(target, spec(new LinkedHashMap<>()));
        try (FileOutputStream output = new FileOutputStream(target, true)) {
            output.write("unexpected=value\n".getBytes(StandardCharsets.US_ASCII));
        }
        codec.read(target);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLineBreakThatShellCommandSubstitutionCannotPreserve() {
        new LaunchSpec("task-1", "game-1", "/storage/emulated/0/Games", "game.exe",
            ".", Arrays.asList("line\nbreak"), "/private/prefix", "wine", "Turnip",
            "DXVK", "ALSA", "1280x720", "INTERMEDIATE", new LinkedHashMap<>(),
            "/private/events", "/private/log", "/private/lock", "/private/cancel", 60);
    }

    @Test
    public void preservesDesktopOnlyFlag() throws Exception {
        LaunchSpec base = spec(new LinkedHashMap<>());
        LaunchSpec desktop = new LaunchSpec(base.getTaskId(), base.getGameId(),
            base.getGameRootPath(), base.getExecutable(), base.getWorkingDirectory(),
            base.getArguments(), base.getPrefixPath(), base.getWinePackage(),
            base.getGraphicsDriver(), base.getDxWrapper(), base.getAudioDriver(),
            base.getResolution(), base.getBox64Preset(), base.getInputProfileId(),
            base.getLaunchExecutionMode(), base.getEnvironment(), base.getEventPath(),
            base.getLogPath(), base.getLockPath(), base.getCancelPath(),
            base.getTimeoutSeconds(), GameRuntimeBackendType.GLIBC_TERMUX_BOX, "", "", "", true);
        File target = temporary.newFile("desktop.launchspec");

        new LaunchSpecCodec().write(target, desktop);
        LaunchSpec restored = new LaunchSpecCodec().read(target);

        assertEquals(true, restored.isDesktopOnly());
        assertEquals(false, spec(new LinkedHashMap<>()).isDesktopOnly());
    }

    @Test
    public void preservesRootfsBackendIdentityAndRootPath() throws Exception {
        LaunchSpec base = spec(new LinkedHashMap<>());
        LaunchSpec rootfs = new LaunchSpec(base.getTaskId(), base.getGameId(),
            base.getGameRootPath(), base.getExecutable(), base.getWorkingDirectory(),
            base.getArguments(),
            "/data/data/com.termux/files/front/prefixes/rootfs_proot/game-1",
            "rootfs_hangover_11.16", "rootfs-virgl-mesa", "rootfs-wined3d", base.getAudioDriver(),
            base.getResolution(), base.getBox64Preset(), base.getInputProfileId(),
            base.getLaunchExecutionMode(), base.getEnvironment(), base.getEventPath(),
            base.getLogPath(), base.getLockPath(), base.getCancelPath(),
            base.getTimeoutSeconds(), GameRuntimeBackendType.ROOTFS_PROOT,
            "debian-13-games-rootfs",
            "/data/data/com.termux/files/front/components/install/debian/versions/v1/rootfs",
            "/data/data/com.termux/files/front/wine/container-1/home-rootfs_proot");
        File target = temporary.newFile("rootfs.launchspec");

        new LaunchSpecCodec().write(target, rootfs);
        LaunchSpec restored = new LaunchSpecCodec().read(target);

        assertEquals(GameRuntimeBackendType.ROOTFS_PROOT, restored.getRuntimeBackendType());
        assertEquals("debian-13-games-rootfs", restored.getRootfsPackage());
        assertEquals(rootfs.getRuntimeRootPath(), restored.getRuntimeRootPath());
        assertEquals(rootfs.getHomeDirectoryPath(), restored.getHomeDirectoryPath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownInputMapper() {
        new LaunchSpec("task-1", "game-1", "/storage/emulated/0/Games", "game.exe",
            ".", Arrays.asList(), "/private/prefix", "wine", "Turnip",
            "DXVK", "ALSA", "1280x720", "INTERMEDIATE", "unknown:3",
            new LinkedHashMap<>(), "/private/events", "/private/log", "/private/lock",
            "/private/cancel", 60);
    }

    @Test
    public void nativeAndLegacyRootfsStillEncodeV6WhileBoundRootfsEncodesV7() throws Exception {
        LaunchSpecCodec codec = new LaunchSpecCodec();
        File f = temporary.newFile();
        codec.write(f, spec(new LinkedHashMap<>()));
        assertEquals("schemaVersion=6", Files.readAllLines(f.toPath()).get(0));
        codec.write(f, externalSpec());
        assertEquals("schemaVersion=7", Files.readAllLines(f.toPath()).get(0));
        LaunchSpec restored = codec.read(f);
        assertEquals(true, restored.hasExternalComponentLayout());
        assertEquals(1, restored.getComponentBindings().size());
        ProotComponentBinding b = restored.getComponentBindings().get(0);
        assertEquals("/private/install $' =/rootfs_wine_10.0/versions/v1-aaaa", b.getVersionDirectory());
        assertEquals("wine $' = 目录", b.getContentRelativePath());
        assertEquals(codec.fingerprint(externalSpec()), codec.fingerprint(restored));
    }

    @Test(expected = IOException.class)
    public void refusesV7OnNativeEvenWithNoBindings() throws Exception {
        File f = temporary.newFile();
        LaunchSpecCodec codec = new LaunchSpecCodec();
        codec.write(f, spec(new LinkedHashMap<>()));
        String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII)
            .replace("schemaVersion=6", "schemaVersion=7") + "bindingCount=0\n";
        Files.write(f.toPath(), raw.getBytes(StandardCharsets.US_ASCII));
        codec.read(f);
    }

    @Test(expected = IOException.class)
    public void refusesExtraBindingFieldsInV6() throws Exception {
        File f = temporary.newFile();
        LaunchSpecCodec codec = new LaunchSpecCodec();
        codec.write(f, spec(new LinkedHashMap<>()));
        Files.write(f.toPath(), "bindingCount=0\n".getBytes(StandardCharsets.US_ASCII),
            java.nio.file.StandardOpenOption.APPEND);
        codec.read(f);
    }

    @Test(expected = IOException.class)
    public void refusesUnknownBindingRevision() throws Exception {
        File f = temporary.newFile();
        LaunchSpecCodec codec = new LaunchSpecCodec();
        codec.write(f, externalSpec());
        String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII)
            .replace("binding.0.revision=MQ==", "binding.0.revision=Mg==");
        Files.write(f.toPath(), raw.getBytes(StandardCharsets.US_ASCII));
        codec.read(f);
    }

    @Test(expected = IOException.class)
    public void rejectsBindingTargetOutsideFixedAllowlist() throws Exception {
        File f = temporary.newFile();
        LaunchSpecCodec codec = new LaunchSpecCodec();
        codec.write(f, externalSpec());
        String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII);
        raw = raw.replace("binding.0.guestTarget=" + Base64Text.encode("/opt/box64-wine".getBytes(StandardCharsets.UTF_8)),
            "binding.0.guestTarget=" + Base64Text.encode("/usr".getBytes(StandardCharsets.UTF_8)));
        Files.write(f.toPath(), raw.getBytes(StandardCharsets.US_ASCII));
        codec.read(f);
    }

    @Test(expected = IOException.class)
    public void rejectsExcessiveBindingCountBeforeAllocatingRecords() throws Exception {
        File f = temporary.newFile();
        LaunchSpecCodec codec = new LaunchSpecCodec();
        codec.write(f, externalSpec());
        String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII)
            .replace("bindingCount=1", "bindingCount=999999999");
        Files.write(f.toPath(), raw.getBytes(StandardCharsets.US_ASCII));
        codec.read(f);
    }

    private static LaunchSpec externalSpec() {
        LaunchSpec b = spec(new LinkedHashMap<>());
        ProotComponentBinding binding = new ProotComponentBinding("rootfs_wine_10.0", 1,
            String.join("", Collections.nCopies(64, "a")),
            "/private/install $' =/rootfs_wine_10.0/versions/v1-aaaa", "wine $' = 目录",
            "/opt/box64-wine", ProotComponentBinding.Role.WINE);
        return new LaunchSpec(b.getTaskId(), b.getGameId(), b.getGameRootPath(), b.getExecutable(),
            b.getWorkingDirectory(), b.getArguments(), b.getPrefixPath(), "rootfs_wine_10.0",
            "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio", b.getResolution(), b.getBox64Preset(),
            b.getInputProfileId(), b.getLaunchExecutionMode(), b.getEnvironment(), b.getEventPath(),
            b.getLogPath(), b.getLockPath(), b.getCancelPath(), b.getTimeoutSeconds(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs", "/private/rootfs",
            "/private/home", false, Collections.singletonList(binding));
    }

    private static LaunchSpec spec(Map<String, String> environment) {
        return new LaunchSpec("task-1", "game-1", "/storage/emulated/0/Games Root",
            "Game $1/it's game.exe", "Game $1",
            Arrays.asList("--name=hello world", "$HOME", "it's-safe", "back\\slash"),
            "/data/data/com.termux/files/front/prefixes/game-1", "glibc_wine_9.3-vanilla",
            "Turnip", "DXVK", "ALSA", "1280x720", "INTERMEDIATE", "dinput:3",
            LaunchExecutionMode.TERMINAL_SESSION, environment,
            "/data/data/com.termux/files/front/events/task-1.jsonl",
            "/data/data/com.termux/files/front/logs/task-1.log",
            "/data/data/com.termux/files/front/locks/task-1",
            "/data/data/com.termux/files/front/cancel/task-1.cancel", 60);
    }
}
