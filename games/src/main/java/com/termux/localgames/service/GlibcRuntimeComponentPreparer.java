package com.termux.localgames.service;

import android.content.Context;

import com.termux.localgames.api.ComponentTasks;
import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.components.index.ComponentIndexParser;
import com.termux.localgames.components.install.ComponentInstallationReader;
import com.termux.localgames.components.install.InstalledComponent;
import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.runtime.GlibcTermuxBoxBackend;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Makes the exact GLIBC component set selected by a profile visible to the host runtime. */
final class GlibcRuntimeComponentPreparer {
    private static final String INDEX_ASSET = "termux-box-packages/index-v1.json";
    private static final long COMPONENT_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long POLL_INTERVAL_MS = 250L;

    private final Context context;
    private final LocalGamesHost host;
    private final ComponentIndex index;
    private final FileComponentTaskRepository tasks;
    private final ComponentInstallationReader installations;

    GlibcRuntimeComponentPreparer(Context context, LocalGamesHost host) throws IOException {
        this.context = context.getApplicationContext();
        this.host = host;
        ComponentStoragePaths paths = new ComponentStoragePaths(context.getFilesDir());
        tasks = new FileComponentTaskRepository(paths.getTasksDirectory());
        installations = new ComponentInstallationReader(paths.getInstallDirectory());
        try (InputStream input = context.getAssets().open(INDEX_ASSET)) {
            index = new ComponentIndexParser().parse(input);
        }
    }

    void ensure(RuntimeProfile profile) throws IOException {
        for (String componentId : new GlibcTermuxBoxBackend().requiredComponentIds(profile)) {
            ComponentDescriptor descriptor = index.find(componentId).orElseThrow(() ->
                new IOException("component_unknown:" + componentId));
            ensure(descriptor);
        }
    }

    private void ensure(ComponentDescriptor descriptor) throws IOException {
        if (isAvailable(descriptor)) return;

        InstalledComponent active = installations.read(descriptor.getId()).getActive().orElse(null);
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
            if (reusable.getState() == ComponentTaskState.FAILED) {
                ComponentTasks.retry(context, taskId);
            } else if (reusable.getState() == ComponentTaskState.PAUSED) {
                ComponentTasks.resume(context, taskId);
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
                !task.getSha256().equals(descriptor.getSha256()) ||
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
            ComponentTask task = tasks.find(taskId).orElse(null);
            if (task != null) {
                if (task.getState() == ComponentTaskState.INSTALLED) {
                    waitForRuntime(descriptor, Math.min(30000L,
                        Math.max(1L, deadline - System.currentTimeMillis())));
                    return;
                }
                if (task.getState() == ComponentTaskState.FAILED) {
                    throw new IOException("component_prepare_failed:" + descriptor.getId());
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
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
        return installed != null && installed.getVersion() == descriptor.getVersion() &&
            installed.getSha256().equals(descriptor.getSha256());
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
