package com.termux.ui.desktop.api;

import android.content.Context;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.api.LaunchRequest;
import com.termux.ui.api.LocalGames;
import com.termux.ui.api.LocalGamesHost;
import com.termux.ui.api.RuntimeSetupRequest;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.data.DesktopDistroRepository;
import com.termux.ui.desktop.data.FileDesktopDistroRepository;
import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.domain.DesktopSessionState;
import com.termux.ui.desktop.runtime.DesktopEnvironment;
import com.termux.ui.desktop.runtime.DesktopLaunchSpec;
import com.termux.ui.desktop.runtime.DesktopRendererPolicy;
import com.termux.ui.desktop.runtime.DesktopSessionStateResolver;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.service.LaunchScriptInstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Command surface of the Desktop feature: manages the distribution inventory and drives the
 * install, start and stop scripts through the host's existing Termux runners.
 */
public final class DesktopTasks {

    private final Context context;
    private final GameStoragePaths paths;
    private final DesktopDistroRepository repository;
    private final DesktopEnvironment environment;

    public DesktopTasks(@NonNull Context context) {
        this.context = context.getApplicationContext();
        File filesDirectory = this.context.getFilesDir();
        this.paths = new GameStoragePaths(filesDirectory);
        this.repository = new FileDesktopDistroRepository(paths.getDesktopDistrosDirectory());
        this.environment = new DesktopEnvironment(filesDirectory);
    }

    @NonNull public List<DesktopDistro> list() throws IOException { return repository.list(); }

    @NonNull public Optional<DesktopDistro> find(@NonNull String id) throws IOException {
        return repository.find(id);
    }

    public void save(@NonNull DesktopDistro distro) throws IOException {
        if (repository.find(distro.getId()).isPresent()) forceStop(distro);
        synchronized (DesktopOperationGate.LOCK) {
            try { DesktopRendererPolicy.requireSupported(distro); }
            catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
            repository.save(distro);

        }
    }

    static void requireNotStopping(DesktopSessionState state) throws IOException {
        if (state == DesktopSessionState.STOPPING) throw new IOException("desktop_stopping");
    }

    private void requireNotStoppingForLaunch(DesktopDistro distro) throws LaunchHostException {
        try { requireNotStopping(state(distro)); }
        catch (IOException stopping) {
            throw new LaunchHostException("desktop_stopping", true, stopping);
        }
    }

    /**
     * Uninstalls a container desktop's proot instance in a background shell. Only meaningful for
     * {@link DesktopRunMode#PROOT}; the record must already be deleted or being deleted.
     */
    public void removeContainer(@NonNull DesktopDistro distro) throws LaunchHostException {
        synchronized (DesktopOperationGate.LOCK) {
            // delete() removes the old spec before this call; deploy a fresh explicit removal spec.
            LaunchScriptInstaller.DesktopScripts scripts = deploy(distro);
            commandStatus(distro, "removing");
            try {
                LocalGames.requireHost(context).startLaunch(new LaunchRequest("desktop-remove-"
                    + distro.getId() + "-" + System.currentTimeMillis(), scripts.remove.getPath(),
                    paths.getDesktopSpecFile(distro.getId()).getPath(),
                    paths.getRuntimeDirectory().getPath(), LaunchExecutionMode.APP_SHELL));
            } catch (LaunchHostException error) {
                commandStatus(distro, "exit:1");
                throw error;
            }

        }
    }

    /** Force-stops related work, then removes the container and its inventory record. */
    public void deleteAndRemoveContainer(@NonNull DesktopDistro distro)
        throws IOException, LaunchHostException {
        forceStop(distro);
        synchronized (DesktopOperationGate.LOCK) {
            // Submit removal before deleting inventory. Failed submission preserves the record.
            removeContainer(distro);
            new com.termux.ui.platform.X11SessionOverrides(context).restore(distro.getId(), null, null);
            repository.delete(distro.getId());
        }
    }

    public void delete(@NonNull DesktopDistro distro) throws IOException {
        forceStop(distro);
        synchronized (DesktopOperationGate.LOCK) {
            new com.termux.ui.platform.X11SessionOverrides(context).restore(distro.getId(), null, null);
            repository.delete(distro.getId());
            paths.getDesktopSpecFile(distro.getId()).delete();
            paths.getDesktopPidFile(distro.getId()).delete();
            paths.getDesktopStatusFile(distro.getId()).delete();
            File log = paths.getDesktopLogFile(distro.getId());
            log.delete();
            // Companions the start script writes next to the log and the pid file.
            new File(log.getPath() + ".out").delete();
            new File(paths.getDesktopPidFile(distro.getId()).getPath() + ".container").delete();
            new File(paths.getDesktopPidFile(distro.getId()).getPath() + ".gnome-ready").delete();

        }
    }

    public boolean isInstalled(@NonNull DesktopDistro distro) {
        return environment.isInstalled(distro);
    }

    @NonNull
    public DesktopSessionState state(@NonNull DesktopDistro distro) {
        boolean installed = environment.isInstalled(distro);
        if (!installed) return DesktopSessionState.NOT_INSTALLED;
        File pidFile = paths.getDesktopPidFile(distro.getId());
        long pid = readPid(pidFile);
        boolean alive = isAlive(pid);
        boolean socket;
        boolean ready;
        if (distro.getDe() == DesktopDe.KWIN_PLASMA) {
            socket = alive && environment.waylandSocket(Process.myUid()).exists();
            ready = socket && DesktopEnvironment.logShowsReady(
                paths.getDesktopLogFile(distro.getId()));
        } else if (distro.getDe() == DesktopDe.GNOME) {
            socket = alive && (distro.getRunMode() == DesktopRunMode.NATIVE
                ? environment.gnomeWaylandSocket(distro).exists() : environment.x11Socket().exists());
            ready = socket && DesktopEnvironment.gnomeReadyMarkerMatches(pidFile, pid);
        } else {
            // Xlorie sessions: the X socket coming up is the ready marker.
            socket = alive && environment.x11Socket().exists();
            ready = socket;
        }
        return DesktopSessionStateResolver.resolve(true, alive, socket, ready,
            readStatus(paths.getDesktopStatusFile(distro.getId())));
    }

    public boolean hasTerminalFailureStatus(@NonNull DesktopDistro distro) {
        return DesktopSessionStateResolver.isFailureStatus(readStatus(paths.getDesktopStatusFile(distro.getId())));
    }

    /** Last part of the distribution's log, for the in-app log viewer. */
    @NonNull
    public String logTail(@NonNull DesktopDistro distro, int maxBytes) {
        return DesktopEnvironment.tail(paths.getDesktopLogFile(distro.getId()), maxBytes);
    }

    /** Runs the install script in a visible Termux terminal; returns the task id. */
    @NonNull
    public String install(@NonNull DesktopDistro distro) throws LaunchHostException {
        synchronized (DesktopOperationGate.LOCK) {
            try { DesktopOperationGate.requireAvailable(); } catch (IOException busy) {
                throw new LaunchHostException("desktop_archive_busy", true, busy);
            }
            requireNotStoppingForLaunch(distro);
            String taskId = "desktop-install-" + distro.getId() + "-" + System.currentTimeMillis();
            LaunchScriptInstaller.DesktopScripts scripts = deploy(distro);
            commandStatus(distro, "installing");
            try {
                LocalGames.requireHost(context).startTermuxPackageInstall(new RuntimeSetupRequest(
                    taskId, scripts.install.getPath(), paths.getDesktopSpecFile(distro.getId()).getPath(),
                    paths.getRuntimeDirectory().getPath()));
            } catch (LaunchHostException error) {
                commandStatus(distro, "exit:1");
                throw error;
            }
            return taskId;
        }
    }

    /** The visible terminal of an install task, once the host has created it. */
    @Nullable
    public TerminalSession installTerminal(@NonNull String taskId) {
        return LocalGames.requireHost(context).getRuntimeSetupTerminal(taskId);
    }

    /**
     * Starts the desktop in the background. The display bridge socket must come up shortly
     * after, so call this once the session view is bound.
     */
    public void start(@NonNull DesktopDistro distro) throws LaunchHostException {
        synchronized (DesktopOperationGate.LOCK) {
            try { DesktopOperationGate.requireAvailable(); } catch (IOException busy) {
                throw new LaunchHostException("desktop_archive_busy", true, busy);
            }
            requireNotStoppingForLaunch(distro);
            LaunchScriptInstaller.DesktopScripts scripts = deploy(distro);
            LocalGamesHost host = LocalGames.requireHost(context);
            host.prepareLaunchRuntime();
            commandStatus(distro, "starting");
            try {
                host.startLaunch(new LaunchRequest("desktop-start-" + distro.getId() + "-"
                    + System.currentTimeMillis(), scripts.start.getPath(),
                    paths.getDesktopSpecFile(distro.getId()).getPath(),
                    paths.getRuntimeDirectory().getPath(), LaunchExecutionMode.APP_SHELL));
            } catch (LaunchHostException error) {
                commandStatus(distro, "exit:1");
                throw error;
            }

        }
    }

    /** Editing and deletion use immediate termination, with no state-based refusal. */
    public void forceStop(@NonNull DesktopDistro distro) throws IOException {
        new DesktopArchiveTasks(context).stopForMutation(distro);
        synchronized (DesktopOperationGate.LOCK) {
            synchronized (com.termux.ui.wine.runtime.RuntimeEditingStop.LOCK) {
                com.termux.ui.wine.runtime.RuntimeEditingStop.cancelOwner(context.getFilesDir(), "desktop:" + distro.getId());
                java.util.List<String> scopes = new java.util.ArrayList<>();
                scopes.add(paths.getDesktopSpecFile(distro.getId()).getAbsolutePath());
                scopes.add(paths.getDesktopPidFile(distro.getId()).getAbsolutePath());
                scopes.add(paths.getDesktopPidFile(distro.getId()).getAbsolutePath() + ".gnome-ready");
                scopes.add(paths.getDesktopLogFile(distro.getId()).getAbsolutePath());
                if (distro.getRunMode() == DesktopRunMode.PROOT) {
                    scopes.add(new File(paths.getTermuxPrefixDirectory(), "var/lib/proot-distro/installed-rootfs/" + distro.getProotDistro()).getAbsolutePath());
                    scopes.add(new File(paths.getTermuxPrefixDirectory(), "var/lib/proot-distro/containers/" + distro.getProotDistro()).getAbsolutePath());
                }
                try { LocalGames.requireHost(context).forceStopRuntimeProcesses(scopes); }
                catch (LaunchHostException error) { throw new IOException(error.getErrorCode(), error); }
                File pid = paths.getDesktopPidFile(distro.getId());
                for (File file : new File[] {pid, new File(pid + ".container"), new File(pid + ".gnome-ready"), new File(pid + ".owned-session")})
                    if (file.exists() && !file.delete()) throw new IOException("desktop_force_stop_cleanup_failed");
                File status = paths.getDesktopStatusFile(distro.getId());
                if (!status.getParentFile().isDirectory() && !status.getParentFile().mkdirs()) throw new IOException("desktop_state_directory_failed");
                try (FileOutputStream output = new FileOutputStream(status)) {
                    output.write("stopped\n".getBytes(StandardCharsets.US_ASCII));
                }
            }
        }
    }

    /** Stops the compositor and, in proot mode, the container's Plasma processes. */
    public void stop(@NonNull DesktopDistro distro) throws LaunchHostException {
        synchronized (DesktopOperationGate.LOCK) {
            try { DesktopOperationGate.requireAvailable(); } catch (IOException busy) {
                throw new LaunchHostException("desktop_archive_busy", true, busy);
            }
            if (state(distro) == DesktopSessionState.STOPPING) return;
            LaunchScriptInstaller.DesktopScripts scripts = deploy(distro);
            commandStatus(distro, "stopping");
            try {
                LocalGames.requireHost(context).startLaunch(new LaunchRequest("desktop-stop-"
                    + distro.getId() + "-" + System.currentTimeMillis(), scripts.stop.getPath(),
                    paths.getDesktopSpecFile(distro.getId()).getPath(),
                    paths.getRuntimeDirectory().getPath(), LaunchExecutionMode.APP_SHELL));
            } catch (LaunchHostException error) {
                commandStatus(distro, "exit:1");
                throw error;
            }

        }
    }

    private void commandStatus(DesktopDistro distro, String status) throws LaunchHostException {
        try {
            File file = paths.getDesktopStatusFile(distro.getId());
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("desktop_state_directory_failed");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write((status + "\n").getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (IOException error) {
            throw new LaunchHostException("desktop_state_write_failed", true, error);
        }
    }

    private LaunchScriptInstaller.DesktopScripts deploy(DesktopDistro distro)
        throws LaunchHostException {
        try {
            writeSpec(distro);
            return new LaunchScriptInstaller(context, paths).installDesktopScripts();
        } catch (IOException | RuntimeException error) {
            throw new LaunchHostException("desktop_prepare_failed", true, error);
        }
    }

    private void writeSpec(DesktopDistro distro) throws IOException {
        File spec = paths.getDesktopSpecFile(distro.getId());
        File directory = spec.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("desktop_spec_directory_failed");
        }
        String content = DesktopLaunchSpec.encode(distro,
            paths.getDesktopPidFile(distro.getId()).getPath(),
            paths.getDesktopStatusFile(distro.getId()).getPath(),
            paths.getDesktopLogFile(distro.getId()).getPath());
        File temporary = new File(directory, spec.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(spec)) {
            temporary.delete();
            throw new IOException("desktop_spec_publish_failed");
        }
    }

    private boolean isAlive(long pid) {
        return pid > 0 && LocalGames.requireHost(context).isProcessAlive(pid);
    }

    private static long readPid(File file) {
        try {
            String text = DesktopEnvironment.tail(file, 32).trim();
            return text.isEmpty() ? -1 : Long.parseLong(text);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String readStatus(File file) {
        return DesktopEnvironment.tail(file, 64).trim();
    }
}
