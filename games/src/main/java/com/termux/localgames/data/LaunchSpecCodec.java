package com.termux.localgames.data;

import com.termux.localgames.domain.LaunchSpec;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.domain.GameRuntimeBackendType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict Base64 line protocol shared with start_local_game.sh. */
public final class LaunchSpecCodec {

    public void write(File target, LaunchSpec spec) throws IOException {
        if (target == null || spec == null) throw new IllegalArgumentException("target and spec required");
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("launch_spec_directory_failed");
        File temporary = new File(parent, target.getName() + ".tmp");
        List<String> lines = encodeLines(spec);
        try (FileOutputStream bytes = new FileOutputStream(temporary);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(bytes, StandardCharsets.US_ASCII))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
            bytes.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("launch_spec_publish_failed");
        }
    }

    public LaunchSpec read(File source) throws IOException {
        if (source == null || !source.isFile()) throw new IOException("launch_spec_missing");
        Map<String, String> fields = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(source), StandardCharsets.US_ASCII))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (++count > 2048 || line.length() > 65536) throw new IOException("launch_spec_too_large");
                int split = line.indexOf('=');
                if (split < 1) throw new IOException("invalid_launch_spec_line");
                String key = line.substring(0, split);
                String value = line.substring(split + 1);
                if (fields.put(key, value) != null) throw new IOException("duplicate_launch_spec_field");
            }
        }
        return decode(fields);
    }

    public String fingerprint(LaunchSpec spec) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : encodeLines(spec)) {
                digest.update(line.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) '\n');
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static List<String> encodeLines(LaunchSpec spec) {
        List<String> lines = new ArrayList<>();
        plain(lines, "schemaVersion", Integer.toString(LaunchSpec.SCHEMA_VERSION));
        text(lines, "taskId", spec.getTaskId());
        text(lines, "gameId", spec.getGameId());
        text(lines, "gameRootPath", spec.getGameRootPath());
        text(lines, "executable", spec.getExecutable());
        text(lines, "workingDirectory", spec.getWorkingDirectory());
        text(lines, "prefixPath", spec.getPrefixPath());
        text(lines, "winePackage", spec.getWinePackage());
        text(lines, "graphicsDriver", spec.getGraphicsDriver());
        text(lines, "dxWrapper", spec.getDxWrapper());
        text(lines, "audioDriver", spec.getAudioDriver());
        text(lines, "resolution", spec.getResolution());
        text(lines, "box64Preset", spec.getBox64Preset());
        text(lines, "inputProfileId", spec.getInputProfileId());
        text(lines, "launchExecutionMode", spec.getLaunchExecutionMode().getStorageValue());
        text(lines, "runtimeBackendType", spec.getRuntimeBackendType().getStorageValue());
        text(lines, "rootfsPackage", spec.getRootfsPackage());
        text(lines, "runtimeRootPath", spec.getRuntimeRootPath());
        text(lines, "eventPath", spec.getEventPath());
        text(lines, "logPath", spec.getLogPath());
        text(lines, "lockPath", spec.getLockPath());
        text(lines, "cancelPath", spec.getCancelPath());
        plain(lines, "timeoutSeconds", Long.toString(spec.getTimeoutSeconds()));
        plain(lines, "argumentCount", Integer.toString(spec.getArguments().size()));
        for (int index = 0; index < spec.getArguments().size(); index++) {
            text(lines, "argument." + index, spec.getArguments().get(index));
        }
        List<String> envKeys = new ArrayList<>(spec.getEnvironment().keySet());
        Collections.sort(envKeys);
        plain(lines, "environmentCount", Integer.toString(envKeys.size()));
        for (int index = 0; index < envKeys.size(); index++) {
            String key = envKeys.get(index);
            text(lines, "environment." + index + ".key", key);
            text(lines, "environment." + index + ".value", spec.getEnvironment().get(key));
        }
        return Collections.unmodifiableList(lines);
    }

    private static LaunchSpec decode(Map<String, String> fields) throws IOException {
        int schema = integer(fields, "schemaVersion", 1, LaunchSpec.SCHEMA_VERSION);
        int arguments = integer(fields, "argumentCount", 0, LaunchSpec.MAX_ARGUMENTS);
        int environment = integer(fields, "environmentCount", 0, LaunchSpec.MAX_ENVIRONMENT);
        Set<String> expected = new HashSet<>();
        Collections.addAll(expected, "schemaVersion", "taskId", "gameId", "gameRootPath",
            "executable", "workingDirectory", "prefixPath", "winePackage", "graphicsDriver",
            "dxWrapper", "audioDriver", "resolution", "box64Preset", "eventPath", "logPath", "lockPath",
            "cancelPath", "timeoutSeconds", "argumentCount", "environmentCount");
        if (schema >= 2) expected.add("inputProfileId");
        if (schema >= 3) expected.add("launchExecutionMode");
        if (schema >= 4) {
            expected.add("runtimeBackendType");
            expected.add("rootfsPackage");
            expected.add("runtimeRootPath");
        }
        List<String> argumentValues = new ArrayList<>();
        for (int index = 0; index < arguments; index++) {
            String key = "argument." + index;
            expected.add(key);
            argumentValues.add(decoded(fields, key));
        }
        Map<String, String> environmentValues = new LinkedHashMap<>();
        for (int index = 0; index < environment; index++) {
            String keyField = "environment." + index + ".key";
            String valueField = "environment." + index + ".value";
            expected.add(keyField);
            expected.add(valueField);
            String key = decoded(fields, keyField);
            if (environmentValues.put(key, decoded(fields, valueField)) != null) {
                throw new IOException("duplicate_launch_environment_key");
            }
        }
        if (!fields.keySet().equals(expected)) throw new IOException("invalid_launch_spec_fields");
        try {
            return new LaunchSpec(decoded(fields, "taskId"), decoded(fields, "gameId"),
                decoded(fields, "gameRootPath"), decoded(fields, "executable"),
                decoded(fields, "workingDirectory"), argumentValues,
                decoded(fields, "prefixPath"), decoded(fields, "winePackage"),
                decoded(fields, "graphicsDriver"), decoded(fields, "dxWrapper"), decoded(fields, "audioDriver"),
                decoded(fields, "resolution"), decoded(fields, "box64Preset"),
                schema >= 2 ? decoded(fields, "inputProfileId") : "xinput",
                schema >= 3
                    ? LaunchExecutionMode.fromStorageValue(decoded(fields, "launchExecutionMode"))
                    : LaunchExecutionMode.APP_SHELL,
                environmentValues,
                decoded(fields, "eventPath"), decoded(fields, "logPath"),
                decoded(fields, "lockPath"), decoded(fields, "cancelPath"),
                longValue(fields, "timeoutSeconds", 30, 86400),
                schema >= 4
                    ? GameRuntimeBackendType.fromStorageValue(decoded(fields, "runtimeBackendType"))
                    : GameRuntimeBackendType.GLIBC_TERMUX_BOX,
                schema >= 4 ? decoded(fields, "rootfsPackage") : "",
                schema >= 4 ? decoded(fields, "runtimeRootPath") : "");
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_launch_spec", error);
        }
    }

    private static void plain(List<String> lines, String key, String value) { lines.add(key + "=" + value); }
    private static void text(List<String> lines, String key, String value) {
        lines.add(key + "=" + Base64Text.encode(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String required(Map<String, String> fields, String key) throws IOException {
        String value = fields.get(key);
        if (value == null) throw new IOException("missing_launch_spec_field:" + key);
        return value;
    }
    private static String decoded(Map<String, String> fields, String key) throws IOException {
        try { return new String(Base64Text.decode(required(fields, key)), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException error) { throw new IOException("invalid_launch_spec_base64:" + key, error); }
    }
    private static int integer(Map<String, String> fields, String key, int min, int max) throws IOException {
        long value = longValue(fields, key, min, max);
        return (int) value;
    }
    private static long longValue(Map<String, String> fields, String key, long min, long max) throws IOException {
        try {
            long value = Long.parseLong(required(fields, key));
            if (value < min || value > max) throw new IOException("launch_spec_range:" + key);
            return value;
        } catch (NumberFormatException error) {
            throw new IOException("invalid_launch_spec_number:" + key, error);
        }
    }
}
