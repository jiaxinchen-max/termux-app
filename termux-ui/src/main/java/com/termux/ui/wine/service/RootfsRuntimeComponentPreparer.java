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
 * component's private active pointer matches the catalog descriptor -- there is no host activation
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
    private final Context context;
    private final ComponentIndex index;
    private final FileComponentTaskRepository tasks;
    private final ComponentInstallationReader installations;

    RootfsRuntimeComponentPreparer(Context context, String parentTaskId) throws IOException {
        this.context = context.getApplicationContext();
        this.parentTaskId = parentTaskId;
        console = new SetupPreparationLog(context.getFilesDir(), parentTaskId);
        ComponentStoragePaths paths = new ComponentStoragePaths(context.getFilesDir());
        tasks = new FileComponentTaskRepository(paths.getTasksDirectory());
        installations = new ComponentInstallationReader(paths.getInstallDirectory());
        try (InputStream input = context.getAssets().open(INDEX_ASSET)) {
            index = new ComponentIndexParser().parse(input);
        }
    }

    /** Downloads and installs every component id that is not already installed at the catalog
     *  version; returns once all are present, or throws if any cannot be prepared in time. */
    void ensure(Collection<String> componentIds) throws IOException {
        for (String componentId : componentIds) {
            if (componentId.startsWith("custom-")) {
                if (!installations.read(componentId).getActive().isPresent()) {
                    throw new IOException("rootfs_custom_component_prepare_required:" + componentId);
                }
            } else ensure(requiredDescriptor(componentId));
        }
    }

    private ComponentDescriptor requiredDescriptor(String componentId) throws IOException {
        return index.find(componentId).orElseThrow(() ->
            new IOException("component_unknown:" + componentId));
    }

    private void ensure(ComponentDescriptor descriptor) throws IOException {
        com.termux.ui.wine.runtime.RuntimeEditingStop.requireNotCancelled(context.getFilesDir(), parentTaskId);
        console.append("Preparing component: " + descriptor.getDisplayName() + " (" + descriptor.getId() + ")");
        if (isInstalled(descriptor)) return;

        ComponentTask reusable = findReusableTask(descriptor, tasks.list());
        String taskId;
        if (reusable == null) {
            taskId = ComponentTasks.enqueueRuntimeDependency(context, descriptor.getId(), parentTaskId);
        } else {
            taskId = reusable.getTaskId();
            com.termux.ui.service.RuntimeInstallationGate.registerDependency(context.getFilesDir(), parentTaskId, taskId);
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

    /** RootFS components are "ready" once installed into the private tree at the catalog version;
     *  unlike GLIBC components they are not published to the host prefix. An empty descriptor
     *  sha256 means a descriptor was resolved without a pre-known digest --
     *  the bundled Hangover catalog pins both version and SHA-256. For unpinned descriptors,
     *  identity falls back to the version alone, since the installed receipt always holds
     *  the real computed hash and could never equal an empty string. */
    private boolean isInstalled(ComponentDescriptor descriptor) throws IOException {
        InstalledComponent active = installations.read(descriptor.getId()).getActive().orElse(null);
        if (active == null || active.getVersion() != descriptor.getVersion()) return false;
        return true;
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
