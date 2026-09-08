package com.termux.localgames.data;

import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.domain.LaunchTaskState;

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

/** Strict, atomic one-file-per-task LaunchTask repository. */
public final class FileLaunchTaskRepository implements LaunchTaskRepository {

    private static final String SUFFIX = ".properties";
    private static final Set<String> V1_KEYS = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("schemaVersion", "taskId", "gameId", "state", "stage", "progress",
            "startedAt", "pid", "exitCode", "errorCode", "recoverable", "logRef")));
    private static final Set<String> V2_KEYS;
    private static final Set<String> V3_KEYS;

    static {
        Set<String> keys = new HashSet<>(V1_KEYS);
        keys.add("lastEventSequence");
        keys.add("updatedAt");
        keys.add("environmentFingerprint");
        V2_KEYS = Collections.unmodifiableSet(keys);
        keys = new HashSet<>(V2_KEYS);
        keys.add("displayConnected");
        keys.add("displayUpdatedAt");
        keys.add("firstFrameAt");
        V3_KEYS = Collections.unmodifiableSet(keys);
    }

    private final File directory;

    public FileLaunchTaskRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory must not be null");
        this.directory = directory;
    }

    @Override
    public synchronized List<LaunchTask> list() throws IOException {
        if (!directory.exists()) return Collections.emptyList();
        File[] files = directory.listFiles((parent, name) -> name.endsWith(SUFFIX));
        if (files == null) throw new IOException("launch_task_repository_unreadable");
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<LaunchTask> result = new ArrayList<>();
        for (File file : files) result.add(read(file));
        result.sort(Comparator.comparingLong(LaunchTask::getStartedAt).reversed()
            .thenComparing(LaunchTask::getTaskId));
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized Optional<LaunchTask> find(String taskId) throws IOException {
        File file = fileFor(taskId);
        return file.isFile() ? Optional.of(read(file)) : Optional.empty();
    }

    @Override
    public synchronized void save(LaunchTask task) throws IOException {
        ensureDirectory();
        File target = fileFor(task.getTaskId());
        File temporary = new File(directory, task.getTaskId() + SUFFIX + ".tmp");
        Properties properties = encode(task);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            properties.store(output, null);
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("launch_task_publish_failed");
        }
    }

    public synchronized Optional<LaunchTask> findActiveForGame(String gameId) throws IOException {
        for (LaunchTask task : list()) {
            if (task.getGameId().equals(gameId) && !task.getState().isTerminal()) {
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    private LaunchTask read(File file) throws IOException {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }
        int schema = intValue(properties, "schemaVersion");
        Set<String> expected;
        if (schema == 1) expected = V1_KEYS;
        else if (schema == 2) expected = V2_KEYS;
        else if (schema == LaunchTask.SCHEMA_VERSION) expected = V3_KEYS;
        else throw new IOException("unsupported_launch_task_schema");
        requireExactKeys(properties, expected);
        try {
            String taskId = required(properties, "taskId", false);
            long startedAt = longValue(properties, "startedAt");
            LaunchTask task = new LaunchTask(taskId, required(properties, "gameId", false),
                LaunchTaskState.valueOf(required(properties, "state", false)),
                LaunchStage.valueOf(required(properties, "stage", false)),
                intValue(properties, "progress"), startedAt, longValue(properties, "pid"),
                nullableInteger(properties, "exitCode"), required(properties, "errorCode", true),
                booleanValue(properties, "recoverable"),
                required(properties, "logRef", true),
                schema == 1 ? 0 : longValue(properties, "lastEventSequence"),
                schema == 1 ? startedAt : longValue(properties, "updatedAt"),
                schema == 1 ? "" : required(properties, "environmentFingerprint", true),
                schema >= 3 && booleanValue(properties, "displayConnected"),
                schema >= 3 ? longValueAllowZero(properties, "displayUpdatedAt") : 0,
                schema >= 3 ? longValueAllowZero(properties, "firstFrameAt") : 0);
            if (!file.getName().equals(taskId + SUFFIX)) {
                throw new IOException("launch_task_id_file_mismatch");
            }
            return task;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_launch_task", error);
        }
    }

    private static Properties encode(LaunchTask task) {
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", Integer.toString(LaunchTask.SCHEMA_VERSION));
        properties.setProperty("taskId", task.getTaskId());
        properties.setProperty("gameId", task.getGameId());
        properties.setProperty("state", task.getState().name());
        properties.setProperty("stage", task.getStage().name());
        properties.setProperty("progress", Integer.toString(task.getProgress()));
        properties.setProperty("startedAt", Long.toString(task.getStartedAt()));
        properties.setProperty("pid", Long.toString(task.getPid()));
        properties.setProperty("exitCode", task.getExitCode() == null ? "" : task.getExitCode().toString());
        properties.setProperty("errorCode", task.getErrorCode());
        properties.setProperty("recoverable", Boolean.toString(task.isRecoverable()));
        properties.setProperty("logRef", task.getLogRef());
        properties.setProperty("lastEventSequence", Long.toString(task.getLastEventSequence()));
        properties.setProperty("updatedAt", Long.toString(task.getUpdatedAt()));
        properties.setProperty("environmentFingerprint", task.getEnvironmentFingerprint());
        properties.setProperty("displayConnected", Boolean.toString(task.isDisplayConnected()));
        properties.setProperty("displayUpdatedAt", Long.toString(task.getDisplayUpdatedAt()));
        properties.setProperty("firstFrameAt", Long.toString(task.getFirstFrameAt()));
        return properties;
    }

    private File fileFor(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid_launch_task_id");
        }
        return new File(directory, taskId + SUFFIX);
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("launch_task_repository_create_failed");
        }
    }

    private static void requireExactKeys(Properties properties, Set<String> expected)
        throws IOException {
        for (Object key : properties.keySet()) {
            if (!expected.contains(key.toString())) throw new IOException("unknown_launch_task_field");
        }
        for (String key : expected) {
            if (!properties.containsKey(key)) throw new IOException("missing_launch_task_field:" + key);
        }
    }

    private static String required(Properties properties, String key, boolean allowEmpty)
        throws IOException {
        String value = properties.getProperty(key);
        if (value == null || (!allowEmpty && value.isEmpty())) {
            throw new IOException("missing_launch_task_field:" + key);
        }
        return value;
    }

    private static long longValue(Properties properties, String key) throws IOException {
        try { return Long.parseLong(required(properties, key, false)); }
        catch (NumberFormatException error) { throw new IOException("invalid_launch_task_field:" + key, error); }
    }

    private static long longValueAllowZero(Properties properties, String key) throws IOException {
        long value = longValue(properties, key);
        if (value < 0) throw new IOException("invalid_launch_task_field:" + key);
        return value;
    }

    private static int intValue(Properties properties, String key) throws IOException {
        try { return Integer.parseInt(required(properties, key, false)); }
        catch (NumberFormatException error) { throw new IOException("invalid_launch_task_field:" + key, error); }
    }

    private static Integer nullableInteger(Properties properties, String key) throws IOException {
        String value = required(properties, key, true);
        if (value.isEmpty()) return null;
        try { return Integer.valueOf(value); }
        catch (NumberFormatException error) { throw new IOException("invalid_launch_task_field:" + key, error); }
    }

    private static boolean booleanValue(Properties properties, String key) throws IOException {
        String value = required(properties, key, false);
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IOException("invalid_launch_task_field:" + key);
    }
}
