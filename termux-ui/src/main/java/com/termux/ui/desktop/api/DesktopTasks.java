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
    private final com.termux.ui.desktop.data.HyprlandSelectionTransaction selections;

    public DesktopTasks(@NonNull Context context) {
        this.context = context.getApplicationContext();
        File filesDirectory = this.context.getFilesDir();
        this.paths = new GameStoragePaths(filesDirectory);
        this.repository = new FileDesktopDistroRepository(paths.getDesktopDistrosDirectory(), new File(filesDirectory, "home"));
        this.environment = new DesktopEnvironment(filesDirectory);
        this.selections = new com.termux.ui.desktop.data.HyprlandSelectionTransaction(
            new File(paths.getDesktopDistrosDirectory(), "pending-hyprland"), repository);
    }

    @NonNull public List<DesktopDistro> list() throws IOException {
        for (DesktopDistro distro : repository.list()) activeInstallTask(distro);
        return repository.list();
    }

    @NonNull public Optional<DesktopDistro> find(@NonNull String id) throws IOException {
        Optional<DesktopDistro> current = repository.find(id);
        if (current.isPresent()) activeInstallTask(current.get());
        return repository.find(id);
    }

    public void save(@NonNull DesktopDistro distro) throws IOException {
        Optional<DesktopDistro> current = repository.find(distro.getId());
        if (current.isPresent() && componentsChanged(current.get(), distro)) {
            synchronized (DesktopOperationGate.LOCK) {
                try {
                    DesktopOperationGate.requireAvailable();
                    DesktopRendererPolicy.requireSupported(distro);
                } catch (IllegalArgumentException invalid) {
                    throw new IOException(invalid.getMessage(), invalid);
                }
                requireComponentSwitchStopped(sessionState(current.get()));
                if (!environment.isInstalled(distro)) throw new IOException("hyprland_theme_install_required");
                repository.save(distro);
            }
            return;
        }
        if (current.isPresent()) forceStop(distro);
        synchronized (DesktopOperationGate.LOCK) {
            try { requireNoInstallation(distro); }
            catch (LaunchHostException busy) { throw new IOException(busy.getErrorCode(), busy); }
            try { DesktopRendererPolicy.requireSupported(distro); }
            catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
            repository.save(distro);

        }
    }

    static boolean componentsChanged(DesktopDistro before, DesktopDistro after) {
        return before.getDe() == DesktopDe.HYPRLAND
            && (!before.getHyprlandRuntimeId().equals(after.getHyprlandRuntimeId())
                || !before.getHyprlandThemeId().equals(after.getHyprlandThemeId()));
    }

    static void requireComponentSwitchStopped(DesktopSessionState state) throws IOException {
        if (state.isActive()) throw new IOException("desktop_stop_before_theme_change");
    }

    public String changeHyprlandComponents(DesktopDistro candidate) throws IOException, LaunchHostException {
        synchronized (DesktopOperationGate.LOCK) {
            DesktopDistro current = repository.find(candidate.getId()).orElseThrow(
                () -> new IOException("desktop_not_found"));
            if (candidate.getDe() != DesktopDe.HYPRLAND || !componentsChanged(current, candidate))
                throw new IOException("hyprland_selection_required");
            requireComponentSwitchStopped(sessionState(current));
            // The install script sees the candidate, while the inventory keeps the last good selection.
            return install(candidate, true, environment.isInstalled(current));
        }
    }

    public boolean hyprlandRuntimeInstalled(DesktopDistro distro) { return environment.hyprlandRuntimeInstalled(distro); }
    public boolean hyprlandThemeInstalled(DesktopDistro distro) { return environment.hyprlandThemeInstalled(distro); }

    static void requireNotStopping(DesktopSessionState state) throws IOException {
        if (state == DesktopSessionState.STOPPING) throw new IOException("desktop_stopping");
    }

    private void requireNotStoppingForLaunch(DesktopDistro distro) throws LaunchHostException {
        requireNoInstallation(distro);
        try { requireNotStopping(DesktopSessionStateResolver.STATUS_STOPPING.equals(
                readStatus(paths.getDesktopStatusFile(distro.getId())))
            ? DesktopSessionState.STOPPING : DesktopSessionState.INSTALLED); }
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
            requireNoInstallation(distro);
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
            new File(paths.getDesktopPidFile(distro.getId()).getPath() + ".hyprland-ready").delete();
            new File(paths.getDesktopPidFile(distro.getId()).getPath() + ".plasma-ready").delete();
            new File(paths.getDesktopPidFile(distro.getId()).getPath() + ".plasma-session").delete();

        }
    }

    public boolean isInstalled(@NonNull DesktopDistro distro) {
        return environment.isInstalled(distro);
    }

    @NonNull
    public DesktopSessionState state(@NonNull DesktopDistro distro) {
        if (activeInstallTask(distro) != null) return DesktopSessionState.INSTALLING;
        if (hasTerminalFailureStatus(distro)) return DesktopSessionState.FAILED;
        boolean installed = environment.isInstalled(distro);
        if (!installed) return DesktopSessionState.NOT_INSTALLED;
        return sessionState(distro);
    }

    /** Live session facts only: installation inventories are not a launch-time preflight. */
    @NonNull
    public DesktopSessionState sessionState(@NonNull DesktopDistro distro) {
        if (activeInstallTask(distro) != null) return DesktopSessionState.INSTALLING;
        File pidFile = paths.getDesktopPidFile(distro.getId());
        long pid = readPid(pidFile);
        boolean alive = isAlive(pid);
        boolean socket;
        boolean ready;
        if (distro.getDe() == DesktopDe.KWIN_PLASMA) {
            socket = alive && environment.waylandSocket(Process.myUid()).exists();
            if (distro.getRunMode() == DesktopRunMode.PROOT) {
                long containerPid = readPid(new File(pidFile.getPath() + ".container"));
                ready = socket && isAlive(containerPid)
                    && DesktopEnvironment.plasmaReadyMarkerMatches(pidFile, pid);
            } else {
                ready = socket && DesktopEnvironment.logShowsReady(
                    paths.getDesktopLogFile(distro.getId()));
            }
        } else if (distro.getDe() == DesktopDe.HYPRLAND) {
            socket = alive && environment.hyprlandWaylandSocket(distro).exists();
            ready = socket && DesktopEnvironment.hyprlandReadyMarkerMatches(pidFile, pid);
        } else if (distro.getDe().isGnomeSession()) {
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
        return install(distro, false, false);
    }

    private String install(DesktopDistro distro, boolean componentSelection, boolean themeOnly) throws LaunchHostException {
        synchronized (DesktopOperationGate.LOCK) {
            String existing = activeInstallTask(distro);
            if (existing != null) return existing;
            try { DesktopOperationGate.requireAvailable(); } catch (IOException busy) {
                throw new LaunchHostException("desktop_archive_busy", true, busy);
            }
            requireNotStoppingForLaunch(distro);
            String taskId = "desktop-install-" + java.util.UUID.randomUUID();
            LaunchScriptInstaller.DesktopScripts scripts = deploy(distro);
            commandStatus(distro, "installing");
            try {
                if (componentSelection) {
                    if (themeOnly) writeSpec(distro, true);
                    selections.stage(distro, taskId);
                }
                com.termux.ui.desktop.runtime.DesktopInstallState.writeTask(installRecord(distro), taskId);
                com.termux.ui.wine.runtime.RuntimeEditingStop.register(taskId, "desktop:" + distro.getId());
                LocalGames.requireHost(context).startTermuxPackageInstall(new RuntimeSetupRequest(
                    taskId, scripts.install.getPath(), paths.getDesktopSpecFile(distro.getId()).getPath(),
                    paths.getRuntimeDirectory().getPath()));
            } catch (LaunchHostException | IOException | RuntimeException error) {
                installRecord(distro).delete();
                if (componentSelection) {
                    try { selections.discard(distro.getId()); }
                    catch (IOException cleanup) { error.addSuppressed(cleanup); }
                }
                commandStatus(distro, "exit:1");
                if (error instanceof LaunchHostException) throw (LaunchHostException) error;
                throw new LaunchHostException("desktop_install_submit_failed", true, error);
            }
            return taskId;
        }
    }

    private File installRecord(DesktopDistro distro) {
        return new File(paths.getDesktopStatusFile(distro.getId()).getPath() + ".install-task");
    }

    /** Resolves the original installation, including the host's terminal creation interval. */
    @Nullable public String activeInstallTask(@NonNull DesktopDistro distro) {
        synchronized (DesktopOperationGate.LOCK) {
            File record = installRecord(distro);
            String id;
            try { id = com.termux.ui.desktop.runtime.DesktopInstallState.readTask(record); }
            catch (IOException error) { throw new IllegalStateException("desktop_install_identity_read_failed", error); }
            if (id == null) return null;
            LocalGamesHost host = LocalGames.requireHost(context);
            TerminalSession terminal = host.getRuntimeSetupTerminal(id);
            boolean pending = host.isRuntimeSetupPending(id);
            // Registration publishes the terminal before clearing pending; re-read across that handoff.
            if (terminal == null && !pending) terminal = host.getRuntimeSetupTerminal(id);
            if (com.termux.ui.desktop.runtime.DesktopInstallState.isActive(terminal != null,
                terminal != null && terminal.isRunning(), pending)) return id;
            // A dead/missing terminal must not leave a permanent spinner or be resubmitted on click.
            if ("installing".equals(readStatus(paths.getDesktopStatusFile(distro.getId())))) {
                try {
                    com.termux.ui.wine.runtime.RuntimeEditingStop.cancelOwner(context.getFilesDir(), "desktop:" + distro.getId());
                    commandStatus(distro, "exit:1");
                } catch (IOException | LaunchHostException error) {
                    throw new IllegalStateException("desktop_install_reconcile_failed", error);
                }
            }
            try {
                selections.finish(distro.getId(), id,
                    "exit:0".equals(readStatus(paths.getDesktopStatusFile(distro.getId()))), environment::isInstalled);
            } catch (IOException error) {
                throw new IllegalStateException("hyprland_selection_commit_failed", error);
            }
            record.delete();
            return null;
        }
    }

    private void requireNoInstallation(DesktopDistro distro) throws LaunchHostException {
        if (activeInstallTask(distro) != null)
            throw new LaunchHostException("desktop_installing", true, null);
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

    /** Editing and deletion stop runtime sessions after protecting any active installation. */
    public void forceStop(@NonNull DesktopDistro distro) throws IOException {
        synchronized (DesktopOperationGate.LOCK) {
            try { requireNoInstallation(distro); }
            catch (LaunchHostException busy) { throw new IOException(busy.getErrorCode(), busy); }
            new DesktopArchiveTasks(context).stopForMutation(distro);
            synchronized (com.termux.ui.wine.runtime.RuntimeEditingStop.LOCK) {
                com.termux.ui.wine.runtime.RuntimeEditingStop.cancelOwner(context.getFilesDir(), "desktop:" + distro.getId());
                java.util.List<String> scopes = new java.util.ArrayList<>();
                scopes.add(paths.getDesktopSpecFile(distro.getId()).getAbsolutePath());
                scopes.add(paths.getDesktopPidFile(distro.getId()).getAbsolutePath());
                scopes.add(paths.getDesktopPidFile(distro.getId()).getAbsolutePath() + ".gnome-ready");
                scopes.add(paths.getDesktopLogFile(distro.getId()).getAbsolutePath());
                if (distro.getDe() == DesktopDe.HYPRLAND)
                    scopes.add(new File(new File(context.getFilesDir(), "home"), ".termux/hyprland-" + distro.getId()).getAbsolutePath());
                if (distro.getRunMode() == DesktopRunMode.PROOT) {
                    scopes.add(new File(paths.getTermuxPrefixDirectory(), "var/lib/proot-distro/installed-rootfs/" + distro.getProotDistro()).getAbsolutePath());
                    scopes.add(new File(paths.getTermuxPrefixDirectory(), "var/lib/proot-distro/containers/" + distro.getProotDistro()).getAbsolutePath());
                }
                try { LocalGames.requireHost(context).forceStopRuntimeProcesses(scopes); }
                catch (LaunchHostException error) { throw new IOException(error.getErrorCode(), error); }
                File pid = paths.getDesktopPidFile(distro.getId());
                for (File file : new File[] {pid, new File(pid + ".container"), new File(pid + ".gnome-ready"),
                        new File(pid + ".hyprland-ready"), new File(pid + ".plasma-ready"),
                        new File(pid + ".plasma-session"), new File(pid + ".owned-session")})
                    if (file.exists() && !file.delete()) throw new IOException("desktop_force_stop_cleanup_failed");
                File status = paths.getDesktopStatusFile(distro.getId());
                if (!status.getParentFile().isDirectory() && !status.getParentFile().mkdirs()) throw new IOException("desktop_state_directory_failed");
                try (FileOutputStream output = new FileOutputStream(status)) {
                    output.write("stopped\n".getBytes(StandardCharsets.US_ASCII));
                }
            }
        }
    }

    /** Runs only a fixed Hyprland control action through the existing Termux host. */
    public void hyprlandAction(@NonNull DesktopDistro distro, @NonNull String action)
        throws LaunchHostException {
        if (distro.getDe() != DesktopDe.HYPRLAND || !java.util.Arrays.asList(
                "terminal", "files", "close", "next", "float").contains(action))
            throw new IllegalArgumentException("unsupported_hyprland_action");
        synchronized (DesktopOperationGate.LOCK) {
            try { DesktopOperationGate.requireAvailable(); } catch (IOException busy) {
                throw new LaunchHostException("desktop_archive_busy", true, busy);
            }
            if (state(distro) != DesktopSessionState.RUNNING)
                throw new LaunchHostException("desktop_not_running", true, null);
            deploy(distro);
            String task = "hyprland-action-" + distro.getId() + "-" + System.currentTimeMillis();
            File spec = new File(paths.getRuntimeDirectory(), task + ".spec");
            String content = DesktopLaunchSpec.encode(distro,
                paths.getDesktopPidFile(distro.getId()).getPath(),
                paths.getDesktopStatusFile(distro.getId()).getPath(),
                paths.getDesktopLogFile(distro.getId()).getPath());
            try (FileOutputStream output = new FileOutputStream(spec)) {
                output.write((content + "DESKTOP_HYPRLAND_ACTION=" + action + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                throw new LaunchHostException("desktop_prepare_failed", true, error);
            }
            LocalGames.requireHost(context).startLaunch(new LaunchRequest(task,
                new File(paths.getRuntimeDirectory(), "desktop_hyprland_action.sh").getPath(),
                spec.getPath(), paths.getRuntimeDirectory().getPath(), LaunchExecutionMode.APP_SHELL));
        }
    }

    /** Stops the compositor and, in proot mode, the container's Plasma processes. */
    public void stop(@NonNull DesktopDistro distro) throws LaunchHostException {
        synchronized (DesktopOperationGate.LOCK) {
            try { DesktopOperationGate.requireAvailable(); } catch (IOException busy) {
                throw new LaunchHostException("desktop_archive_busy", true, busy);
            }
            requireNoInstallation(distro);
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
        writeSpec(distro, false);
    }

    private void writeSpec(DesktopDistro distro, boolean componentSelection) throws IOException {
        File spec = paths.getDesktopSpecFile(distro.getId());
        File directory = spec.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("desktop_spec_directory_failed");
        }
        String content = DesktopLaunchSpec.encode(distro,
            paths.getDesktopPidFile(distro.getId()).getPath(),
            paths.getDesktopStatusFile(distro.getId()).getPath(),
            paths.getDesktopLogFile(distro.getId()).getPath());
        if (componentSelection) content += "DESKTOP_HYPRLAND_COMPONENT_SWITCH=1\n";
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
