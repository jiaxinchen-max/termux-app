package com.termux.localgames.components.delivery;

import com.termux.localgames.components.ComponentDownloadResult;
import com.termux.localgames.components.ComponentDownloader;
import com.termux.localgames.components.DownloadControl;
import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.install.ComponentInstaller;
import com.termux.localgames.components.install.InstalledComponent;
import com.termux.localgames.data.ComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Synchronous task coordinator; the Android Service owns its executor. */
public final class ComponentDeliveryCoordinator {

    private final ComponentTaskRepository repository;
    private final ComponentDownloader downloader;
    private final ComponentInstaller installer;
    private final File downloadDirectory;
    private final ConcurrentHashMap<String, AtomicReference<DownloadControl.Decision>> controls =
        new ConcurrentHashMap<>();

    public ComponentDeliveryCoordinator(ComponentTaskRepository repository,
                                        ComponentDownloader downloader,
                                        ComponentInstaller installer,
                                        File downloadDirectory) {
        this.repository = repository;
        this.downloader = downloader;
        this.installer = installer;
        this.downloadDirectory = downloadDirectory;
    }

    public ComponentTask enqueue(ComponentDescriptor descriptor) throws IOException {
        return enqueue(descriptor, UUID.randomUUID().toString());
    }

    public ComponentTask enqueue(ComponentDescriptor descriptor, String taskId) throws IOException {
        ComponentTask task = descriptor.createTask(taskId);
        repository.save(task);
        return task;
    }

    public ComponentTask execute(String taskId, ComponentTaskListener listener) throws IOException {
        ComponentTask task = requiredTask(taskId);
        if (task.getState() == ComponentTaskState.INSTALLED ||
            task.getState() == ComponentTaskState.CANCELLED) {
            return task;
        }
        AtomicReference<DownloadControl.Decision> control = controls.computeIfAbsent(taskId,
            ignored -> new AtomicReference<>(DownloadControl.Decision.CONTINUE));
        try {
            File artifact;
            if (task.getState() == ComponentTaskState.VERIFIED ||
                task.getState() == ComponentTaskState.INSTALLING) {
                artifact = artifactFile(task);
            } else {
                ComponentDownloadResult result = downloader.download(task, control::get,
                    listener::onTaskUpdated);
                task = result.getTask();
                if (task.getState() == ComponentTaskState.PAUSED ||
                    task.getState() == ComponentTaskState.CANCELLED) {
                    return task;
                }
                artifact = result.getArtifact();
            }
            installer.install(task, artifact, control::get, listener::onTaskUpdated);
            return requiredTask(taskId);
        } finally {
            controls.remove(taskId, control);
        }
    }

    public void requestPause(String taskId, ComponentTaskListener listener) throws IOException {
        if (signalPause(taskId)) return;
        transitionIdle(requiredTask(taskId), ComponentTaskState.PAUSED, listener);
    }

    public void requestCancel(String taskId, ComponentTaskListener listener) throws IOException {
        if (signalCancel(taskId)) return;
        transitionIdle(requiredTask(taskId), ComponentTaskState.CANCELLED, listener);
    }

    public boolean signalPause(String taskId) {
        return signal(taskId, DownloadControl.Decision.PAUSE);
    }

    public boolean signalCancel(String taskId) {
        return signal(taskId, DownloadControl.Decision.CANCEL);
    }

    /** Registers control before a Service exposes the task as running. */
    public void prepareExecution(String taskId) {
        controls.computeIfAbsent(taskId,
            ignored -> new AtomicReference<>(DownloadControl.Decision.CONTINUE));
    }

    public void pauseAllRunning() {
        for (AtomicReference<DownloadControl.Decision> control : controls.values()) {
            control.set(DownloadControl.Decision.PAUSE);
        }
    }

    public ComponentTask prepareResume(String taskId, ComponentTaskListener listener)
        throws IOException {
        ComponentTask task = requiredTask(taskId);
        if (task.getState() != ComponentTaskState.PAUSED &&
            task.getState() != ComponentTaskState.FAILED) {
            return task;
        }
        ComponentTask resumed = task.transition(ComponentTaskState.DOWNLOADING,
            task.getDownloadedBytes(), task.getEtag(), task.getLastModified(), "", "");
        repository.save(resumed);
        notifyListener(listener, resumed);
        return resumed;
    }

    public List<String> recoverableTaskIds() throws IOException {
        List<String> result = new ArrayList<>();
        for (ComponentTask task : repository.list()) {
            if (task.getState().shouldRecoverAutomatically()) result.add(task.getTaskId());
        }
        return result;
    }

    public Optional<ComponentTask> find(String taskId) throws IOException {
        return repository.find(taskId);
    }

    public InstalledComponent rollback(String packageName) throws IOException {
        return installer.rollback(packageName);
    }

    private void transitionIdle(ComponentTask task, ComponentTaskState state,
                                ComponentTaskListener listener) throws IOException {
        if (task.getState().isTerminal() || task.getState() == state) return;
        ComponentTask updated = task.transition(state, task.getDownloadedBytes(),
            task.getEtag(), task.getLastModified(), "", "");
        repository.save(updated);
        notifyListener(listener, updated);
    }

    private ComponentTask requiredTask(String taskId) throws IOException {
        return repository.find(taskId).orElseThrow(
            () -> new IOException("Component task not found: " + taskId));
    }

    private File artifactFile(ComponentTask task) {
        return new File(downloadDirectory,
            task.getPackageName() + "-" + task.getVersion() + ".archive");
    }

    private boolean signal(String taskId, DownloadControl.Decision decision) {
        AtomicReference<DownloadControl.Decision> control = controls.get(taskId);
        if (control == null) return false;
        control.set(decision);
        return true;
    }

    private static void notifyListener(ComponentTaskListener listener, ComponentTask task) {
        try {
            listener.onTaskUpdated(task);
        } catch (RuntimeException ignored) {
            // Observers cannot change persisted coordinator state.
        }
    }
}
