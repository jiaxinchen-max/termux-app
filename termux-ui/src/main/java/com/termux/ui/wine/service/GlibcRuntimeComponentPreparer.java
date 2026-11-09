package com.termux.ui.wine.service;

import android.content.Context;

import com.termux.ui.api.ComponentTasks;
import com.termux.ui.api.LocalGamesHost;
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
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.runtime.GlibcTermuxBoxBackend;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Makes the exact GLIBC component set selected by a profile visible to the host runtime. */
final class GlibcRuntimeComponentPreparer {
    private static final String INDEX_ASSET = "termux-app-packages/index-v1.json";
    private static final long COMPONENT_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long POLL_INTERVAL_MS = 250L;

    private final SetupPreparationLog console;
    private final String parentTaskId;
    private final Context context;
    private final LocalGamesHost host;
    private final ComponentIndex index;
    private final FileComponentTaskRepository tasks;
    private final ComponentInstallationReader installations;

    GlibcRuntimeComponentPreparer(Context context, LocalGamesHost host, String parentTaskId) throws IOException {
        this.parentTaskId = parentTaskId;
        this.context = context.getApplicationContext();
        console = new SetupPreparationLog(context.getFilesDir(), parentTaskId);
        this.host = host;
        ComponentStoragePaths paths = new ComponentStoragePaths(context.getFilesDir());
        tasks = new FileComponentTaskRepository(paths.getTasksDirectory());
        installations = new ComponentInstallationReader(paths.getInstallDirectory());
        try (InputStream input = context.getAssets().open(INDEX_ASSET)) {
            index = new ComponentIndexParser().parse(input);
        }
    }

    void ensure(RuntimeProfile profile) throws IOException {
        // Host-managed packages are not in the downloadable component catalog.
        // Install and verify them before activation can publish files into their tree.
        com.termux.ui.wine.runtime.NativeBaseRuntimePrerequisite.ensure(
            () -> host.isRuntimeComponentAvailable(GlibcTermuxBoxBackend.TERMUX_GLIBC_ID),
            () -> ensure(GlibcTermuxBoxBackend.termuxGlibcDescriptor()));
        for (String componentId : new GlibcTermuxBoxBackend().requiredComponentIds(profile,
            new com.termux.ui.data.GameStoragePaths(context.getFilesDir()))) {
            ComponentDescriptor descriptor = index.find(componentId).orElseThrow(() ->
                new IOException("component_unknown:" + componentId));
            ensure(descriptor);
        }
    }

    private void ensure(ComponentDescriptor descriptor) throws IOException {
        com.termux.ui.wine.runtime.RuntimeEditingStop.requireNotCancelled(context.getFilesDir(), parentTaskId);
        console.append("Preparing component: " + descriptor.getDisplayName() + " (" + descriptor.getId() + ")");
        if (isAvailable(descriptor)) return;

        // The host-managed runtime has no archive installation/activation receipt.
        boolean hostManaged = GlibcTermuxBoxBackend.TERMUX_GLIBC_ID.equals(descriptor.getId());
        InstalledComponent active = hostManaged ? null
            : installations.read(descriptor.getId()).getActive().orElse(null);
        if (matches(active, descriptor)) {
            ComponentTasks.activate(context, descriptor.getId());
            waitForRuntime(descriptor, COMPONENT_TIMEOUT_MS);
            return;
        }

        ComponentTask reusable = findReusableTask(descriptor, tasks.list());
        String taskId;
        if (reusable == null) {
            taskId = ComponentTasks.enqueue(context, descriptor.getId());
        } else {
            taskId = reusable.getTaskId();
            ComponentTaskConsoleLog.clearCommandFailure(context.getFilesDir(), taskId);
            if (reusable.getState() == ComponentTaskState.FAILED) {
                ComponentTasks.resumeAndAwaitAcceptance(context, taskId, true, 30000L);
            } else if (reusable.getState() == ComponentTaskState.PAUSED) {
                ComponentTasks.resumeAndAwaitAcceptance(context, taskId, false, 30000L);
            } else {
                ComponentTasks.reconcile(context);
            }
        }
        waitForTask(descriptor, taskId);
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
            com.termux.ui.wine.runtime.RuntimeEditingStop.requireNotCancelled(context.getFilesDir(), parentTaskId);
            console.component(descriptor.getId(), taskId);
            String rejected = ComponentTaskConsoleLog.commandFailure(context.getFilesDir(), taskId);
            if (!rejected.isEmpty()) throw new IOException("component_prepare_failed:" + descriptor.getId() + ":" + rejected);
            ComponentTask task = tasks.find(taskId).orElse(null);
            if (task != null) {
                if (task.getState() == ComponentTaskState.INSTALLED) {
                    waitForRuntime(descriptor, Math.min(30000L,
                        Math.max(1L, deadline - System.currentTimeMillis())));
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

    private void waitForRuntime(ComponentDescriptor descriptor, long timeoutMs) throws IOException {
        console.append("Activating component: " + descriptor.getDisplayName());
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            com.termux.ui.wine.runtime.RuntimeEditingStop.requireNotCancelled(context.getFilesDir(), parentTaskId);
            if (isAvailable(descriptor)) return;
            sleep();
        }
        throw new IOException("component_activation_timeout:" + descriptor.getId());
    }

    private boolean isAvailable(ComponentDescriptor descriptor) {
        try {
            return host.isRuntimeComponentAvailable(descriptor.getId(), descriptor.getVersion(),
                descriptor.getSha256());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean matches(InstalledComponent installed, ComponentDescriptor descriptor) {
        return installed != null && installed.getVersion() == descriptor.getVersion() ;
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
