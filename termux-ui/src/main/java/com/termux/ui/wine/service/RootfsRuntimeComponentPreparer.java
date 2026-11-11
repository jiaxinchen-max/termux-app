package com.termux.ui.wine.service;

import android.content.Context;

import com.termux.ui.api.ComponentTasks;
import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.components.index.ComponentDescriptor;
import com.termux.ui.components.index.ComponentIndex;
import com.termux.ui.components.index.ComponentIndexParser;
import com.termux.ui.components.install.ComponentInstallationReader;
import com.termux.ui.components.install.InstalledComponent;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.ComponentTaskState;
import com.termux.ui.service.ComponentTaskConsoleLog;
import com.termux.ui.service.SetupPreparationLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

/**
 * Ensures the RootFS base components are downloaded and installed into the games-private component
 * tree before a shared-image build starts.
 *
 * <p>Mirrors {@link GlibcRuntimeComponentPreparer}, but RootFS components are consumed by the
 * in-container build rather than published into the host GLIBC prefix, so "ready" means the
 * component's private active directory exists -- there is no host activation
 * step. This closes the gap where a rebuild threw {@code rootfs_source_component_missing} (and
 * created no task, leaving the UI stuck) when a required component had never been downloaded.</p>
 *
 */
final class RootfsRuntimeComponentPreparer {
    private static final String INDEX_ASSET = "termux-app-packages/index-v1.json";
    private static final long COMPONENT_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long POLL_INTERVAL_MS = 250L;

    private final SetupPreparationLog console;
    private final String parentTaskId;
    private final File filesDirectory;
    private final Commands commands;
    private final ComponentIndex index;
    private final FileComponentTaskRepository tasks;
    private final ComponentInstallationReader installations;

    RootfsRuntimeComponentPreparer(Context context, String parentTaskId) throws IOException {
        this(context.getFilesDir(), parentTaskId, readIndex(context), new Commands() {
            @Override public String enqueue(String id) throws IOException {
                return ComponentTasks.enqueueRuntimeDependency(context, id, parentTaskId);
            }
            @Override public void resume(String id, boolean retry) throws IOException {
                ComponentTasks.resumeAndAwaitAcceptance(context, id, retry, 30000L);
            }
            @Override public void reconcile() { ComponentTasks.reconcile(context); }
        });
    }

    RootfsRuntimeComponentPreparer(File filesDirectory, String parentTaskId,
                                   ComponentIndex index, Commands commands) throws IOException {
        this.filesDirectory = filesDirectory;
        this.commands = commands;
        this.index = index;
        this.parentTaskId = parentTaskId;
        console = new SetupPreparationLog(filesDirectory, parentTaskId);
        ComponentStoragePaths paths = new ComponentStoragePaths(filesDirectory);
        tasks = new FileComponentTaskRepository(paths.getTasksDirectory());
        installations = new ComponentInstallationReader(paths.getInstallDirectory());
    }

    private static ComponentIndex readIndex(Context context) throws IOException {
        try (InputStream input = context.getAssets().open(INDEX_ASSET)) {
            return new ComponentIndexParser().parse(input);
        }
    }

    interface Commands {
        String enqueue(String componentId) throws IOException;
        void resume(String taskId, boolean retry) throws IOException;
        void reconcile();
    }

    /** Ordinary program preparation reuses installed components without catalog upgrades. */
    void ensure(Collection<String> componentIds) throws IOException {
        ensure(componentIds, false);
    }

    /** An explicit base build prepares the current image even when an old installation exists. */
    void ensure(Collection<String> componentIds, boolean rebuildBase) throws IOException {
        for (String componentId : componentIds) {
            if (componentId.startsWith("custom-")) {
                if (!installations.read(componentId).getActive().isPresent()) {
                    throw new IOException("rootfs_custom_component_prepare_required:" + componentId);
                }
            } else ensure(requiredDescriptor(componentId), rebuildBase);
        }
    }

    private ComponentDescriptor requiredDescriptor(String componentId) throws IOException {
        return index.find(componentId).orElseThrow(() ->
            new IOException("component_unknown:" + componentId));
    }

    private void ensure(ComponentDescriptor descriptor, boolean rebuildBase) throws IOException {
        com.termux.ui.wine.runtime.RuntimeEditingStop.requireNotCancelled(filesDirectory, parentTaskId);
        console.append("Preparing component: " + descriptor.getDisplayName() + " (" + descriptor.getId() + ")");
        if (!rebuildBase && isInstalled(descriptor)) return;

        ComponentTask reusable = rebuildBase ? findBuildTask(descriptor, tasks.list())
            : findReusableTask(descriptor, tasks.list());
        String taskId;
        if (reusable == null) {
            taskId = commands.enqueue(descriptor.getId());
        } else {
            taskId = reusable.getTaskId();
            if (rebuildBase) {
                // Recover only this build's child; its own failure must never start a retry.
                if (reusable.getState().shouldRecoverAutomatically()) commands.reconcile();
                waitForTask(descriptor, taskId);
                return;
            }
            com.termux.ui.service.RuntimeInstallationGate.registerDependency(filesDirectory, parentTaskId, taskId);
            ComponentTaskConsoleLog.clearCommandFailure(filesDirectory, taskId);
            if (reusable.getState() == ComponentTaskState.FAILED) {
                commands.resume(taskId, true);
            } else if (reusable.getState() == ComponentTaskState.PAUSED) {
                commands.resume(taskId, false);
            } else {
                commands.reconcile();
            }
        }
        waitForTask(descriptor, taskId);
    }

    private ComponentTask findBuildTask(ComponentDescriptor descriptor,
                                         List<ComponentTask> candidates) {
        for (ComponentTask task : candidates) {
            if (descriptor.getId().equals(task.getPackageName()) &&
                com.termux.ui.service.RuntimeInstallationGate.ownsDependency(
                    filesDirectory, parentTaskId, task.getTaskId())) return task;
        }
        return null;
    }

    private ComponentTask findReusableTask(ComponentDescriptor descriptor,
                                           List<ComponentTask> candidates) {
        ComponentTask best = null;
        for (ComponentTask task : candidates) {
            if (!task.getPackageName().equals(descriptor.getId()) ||
                task.getVersion() != descriptor.getVersion() ||
                task.getState() == ComponentTaskState.CANCELLED ||
                task.getState() == ComponentTaskState.INSTALLED) {
                continue;
            }
            if (best == null || task.getDownloadedBytes() > best.getDownloadedBytes()) best = task;
        }
        return best;
    }

    private void waitForTask(ComponentDescriptor descriptor, String taskId) throws IOException {
        long deadline = System.currentTimeMillis() + COMPONENT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            com.termux.ui.wine.runtime.RuntimeEditingStop.requireNotCancelled(filesDirectory, parentTaskId);
            console.component(descriptor.getId(), taskId);
            String rejected = ComponentTaskConsoleLog.commandFailure(filesDirectory, taskId);
            if (!rejected.isEmpty()) throw new IOException("component_prepare_failed:" + descriptor.getId() + ":" + rejected);
            ComponentTask task = tasks.find(taskId).orElse(null);
            if (task != null) {
                if (task.getState() == ComponentTaskState.INSTALLED && isInstalled(descriptor)) {
                    return;
                }
                if (task.getState() == ComponentTaskState.FAILED) {
                    throw new IOException("component_prepare_failed:" + descriptor.getId() + ":" + task.getErrorMessage());
                }
                if (task.getState() == ComponentTaskState.CANCELLED) {
                    throw new IOException("component_prepare_cancelled:" + descriptor.getId());
                }
                if (task.getState() == ComponentTaskState.PAUSED) {
                    throw new IOException("component_prepare_paused:" + descriptor.getId());
                }
            }
            sleep();
        }
        throw new IOException("component_prepare_timeout:" + descriptor.getId());
    }

    /** Availability follows the installed identity, never the catalog revision. */
    private boolean isInstalled(ComponentDescriptor descriptor) throws IOException {
        InstalledComponent active = installations.read(descriptor.getId()).getActive().orElse(null);
        return active != null && active.getDirectory().isDirectory();
    }

    private static void sleep() throws IOException {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("component_prepare_interrupted", error);
        }
    }
}
