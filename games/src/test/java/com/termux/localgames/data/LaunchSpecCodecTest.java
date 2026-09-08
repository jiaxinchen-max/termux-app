package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.termux.localgames.domain.LaunchSpec;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.domain.GameRuntimeBackendType;

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
    public void readsSchemaOneWithXInputDefault() throws Exception {
        File target = temporary.newFile("legacy.launchspec");
        LaunchSpecCodec codec = new LaunchSpecCodec();
        codec.write(target, spec(new LinkedHashMap<>()));
        String raw = new String(Files.readAllBytes(target.toPath()), StandardCharsets.US_ASCII)
            .replace("schemaVersion=4", "schemaVersion=1")
            .replaceAll("(?m)^inputProfileId=.*\\n", "")
            .replaceAll("(?m)^launchExecutionMode=.*\\n", "")
            .replaceAll("(?m)^(runtimeBackendType|rootfsPackage|runtimeRootPath)=.*\\n", "");
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(raw.getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals("xinput", codec.read(target).getInputProfileId());
        assertEquals(LaunchExecutionMode.APP_SHELL,
            codec.read(target).getLaunchExecutionMode());
        assertEquals(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
            codec.read(target).getRuntimeBackendType());
    }

    @Test
    public void readsSchemaTwoWithAppShellDefault() throws Exception {
        File target = temporary.newFile("schema-two.launchspec");
        LaunchSpecCodec codec = new LaunchSpecCodec();
        codec.write(target, spec(new LinkedHashMap<>()));
        String raw = new String(Files.readAllBytes(target.toPath()), StandardCharsets.US_ASCII)
            .replace("schemaVersion=4", "schemaVersion=2")
            .replaceAll("(?m)^launchExecutionMode=.*\\n", "")
            .replaceAll("(?m)^(runtimeBackendType|rootfsPackage|runtimeRootPath)=.*\\n", "");
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(raw.getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals(LaunchExecutionMode.APP_SHELL,
            codec.read(target).getLaunchExecutionMode());
    }

    @Test
    public void readsSchemaThreeWithGlibcBackendDefault() throws Exception {
        File target = temporary.newFile("schema-three.launchspec");
        LaunchSpecCodec codec = new LaunchSpecCodec();
        codec.write(target, spec(new LinkedHashMap<>()));
        String raw = new String(Files.readAllBytes(target.toPath()), StandardCharsets.US_ASCII)
            .replace("schemaVersion=4", "schemaVersion=3")
            .replaceAll("(?m)^(runtimeBackendType|rootfsPackage|runtimeRootPath)=.*\\n", "");
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(raw.getBytes(StandardCharsets.US_ASCII));
        }

        LaunchSpec restored = codec.read(target);
        assertEquals(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
            restored.getRuntimeBackendType());
        assertEquals("", restored.getRuntimeRootPath());
    }

    @Test
    public void preservesRootfsBackendIdentityAndRootPath() throws Exception {
        LaunchSpec base = spec(new LinkedHashMap<>());
        LaunchSpec rootfs = new LaunchSpec(base.getTaskId(), base.getGameId(),
            base.getGameRootPath(), base.getExecutable(), base.getWorkingDirectory(),
            base.getArguments(),
            "/data/data/com.termux/files/games/prefixes/rootfs_proot/game-1",
            "hangover-11.9", "rootfs-virgl-mesa", "rootfs-wined3d", base.getAudioDriver(),
            base.getResolution(), base.getBox64Preset(), base.getInputProfileId(),
            base.getLaunchExecutionMode(), base.getEnvironment(), base.getEventPath(),
            base.getLogPath(), base.getLockPath(), base.getCancelPath(),
            base.getTimeoutSeconds(), GameRuntimeBackendType.ROOTFS_PROOT,
            "debian-13-games-rootfs",
            "/data/data/com.termux/files/games/components/install/debian/versions/v1/rootfs");
        File target = temporary.newFile("rootfs.launchspec");

        new LaunchSpecCodec().write(target, rootfs);
        LaunchSpec restored = new LaunchSpecCodec().read(target);

        assertEquals(GameRuntimeBackendType.ROOTFS_PROOT, restored.getRuntimeBackendType());
        assertEquals("debian-13-games-rootfs", restored.getRootfsPackage());
        assertEquals(rootfs.getRuntimeRootPath(), restored.getRuntimeRootPath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownInputMapper() {
        new LaunchSpec("task-1", "game-1", "/storage/emulated/0/Games", "game.exe",
            ".", Arrays.asList(), "/private/prefix", "wine", "Turnip",
            "DXVK", "ALSA", "1280x720", "INTERMEDIATE", "unknown:3",
            new LinkedHashMap<>(), "/private/events", "/private/log", "/private/lock",
            "/private/cancel", 60);
    }

    private static LaunchSpec spec(Map<String, String> environment) {
        return new LaunchSpec("task-1", "game-1", "/storage/emulated/0/Games Root",
            "Game $1/it's game.exe", "Game $1",
            Arrays.asList("--name=hello world", "$HOME", "it's-safe", "back\\slash"),
            "/data/data/com.termux/files/games/prefixes/game-1", "wine-9.3-vanilla-wow64",
            "Turnip", "DXVK", "ALSA", "1280x720", "INTERMEDIATE", "dinput:3",
            LaunchExecutionMode.TERMINAL_SESSION, environment,
            "/data/data/com.termux/files/games/events/task-1.jsonl",
            "/data/data/com.termux/files/games/logs/task-1.log",
            "/data/data/com.termux/files/games/locks/task-1",
            "/data/data/com.termux/files/games/cancel/task-1.cancel", 60);
    }
}
