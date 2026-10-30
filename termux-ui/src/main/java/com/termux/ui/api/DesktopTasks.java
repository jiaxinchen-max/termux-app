package com.termux.ui.api;

import android.content.Context;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.ui.data.DesktopDistroRepository;
import com.termux.ui.data.FileDesktopDistroRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.DesktopDe;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopSessionState;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.runtime.DesktopEnvironment;
import com.termux.ui.runtime.DesktopLaunchSpec;
import com.termux.ui.runtime.DesktopSessionStateResolver;
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
        Optional<DesktopDistro> previous = repository.find(distro.getId());
        if (previous.isPresent()) {
            DesktopDistro old = previous.get();
            requireEditableSettings(old, distro, state(old));
        }
        repository.save(distro);
    }

    static void requireEditableSettings(DesktopDistro old, DesktopDistro updated,
                                        DesktopSessionState state) throws IOException {
        boolean changesRuntime = old.getGlMode() != updated.getGlMode()
            || !old.getResolution().equals(updated.getResolution())
            || old.getRefreshRate() != updated.getRefreshRate();
        if (changesRuntime && (state == DesktopSessionState.RUNNING
            || state == DesktopSessionState.STARTING)) {
            throw new IOException("desktop_settings_stop_required");
        }
    }

    /**
     * Uninstalls a container desktop's proot instance in a background shell. Only meaningful for
     * {@link DesktopRunMode#PROOT}; the record must already be deleted or being deleted.
     */
    public void removeContainer(@NonNull DesktopDistro distro) throws LaunchHostException {
        // delete() removes the old spec before this call; deploy a fresh explicit removal spec.
        LaunchScriptInstaller.DesktopScripts scripts = deploy(distro);
        LocalGames.requireHost(context).startLaunch(new LaunchRequest("desktop-remove-"
            + distro.getId() + "-" + System.currentTimeMillis(), scripts.remove.getPath(),
            paths.getDesktopSpecFile(distro.getId()).getPath(),
            paths.getRuntimeDirectory().getPath(), LaunchExecutionMode.APP_SHELL));
    }

    /** Removes the record and its state; a running or starting desktop must be stopped first. */
    public void delete(@NonNull DesktopDistro distro) throws IOException {
        DesktopSessionState state = state(distro);
        if (state == DesktopSessionState.RUNNING || state == DesktopSessionState.STARTING) {
            throw new IOException("desktop_running");
        }
        repository.delete(distro.getId());
        paths.getDesktopSpecFile(distro.getId()).delete();
        paths.getDesktopPidFile(distro.getId()).delete();
        paths.getDesktopStatusFile(distro.getId()).delete();
        File log = paths.getDesktopLogFile(distro.getId());
        log.delete();
        // Companions the start script writes next to the log and the pid file.
        new File(log.getPath() + ".out").delete();
        new File(paths.getDesktopPidFile(distro.getId()).getPath() + ".container").delete();
    }

    public boolean isInstalled(@NonNull DesktopDistro distro) {
        return environment.isInstalled(distro);
    }

    @NonNull
    public DesktopSessionState state(@NonNull DesktopDistro distro) {
        boolean installed = environment.isInstalled(distro);
        if (!installed) return DesktopSessionState.NOT_INSTALLED;
        File pidFile = paths.getDesktopPidFile(distro.getId());
        boolean alive = isAlive(readPid(pidFile));
        boolean socket;
        boolean ready;
        if (distro.getDe() == DesktopDe.KWIN_PLASMA) {
            socket = alive && environment.waylandSocket(Process.myUid()).exists();
            ready = socket && DesktopEnvironment.logShowsReady(
                paths.getDesktopLogFile(distro.getId()));
        } else {
            // Xlorie sessions: the X socket coming up is the ready marker.
            socket = alive && environment.x11Socket().exists();
            ready = socket;
        }
        return DesktopSessionStateResolver.resolve(true, alive, socket, ready,
            readStatus(paths.getDesktopStatusFile(distro.getId())));
    }

    /** Last part of the distribution's log, for the in-app log viewer. */
    @NonNull
    public String logTail(@NonNull DesktopDistro distro, int maxBytes) {
        return DesktopEnvironment.tail(paths.getDesktopLogFile(distro.getId()), maxBytes);
    }

    /** Runs the install script in a visible Termux terminal; returns the task id. */
    @NonNull
    public String install(@NonNull DesktopDistro distro) throws LaunchHostException {
        String taskId = "desktop-install-" + distro.getId() + "-" + System.currentTimeMillis();
        LaunchScriptInstaller.DesktopScripts scripts = deploy(distro);
        LocalGames.requireHost(context).startTermuxPackageInstall(new RuntimeSetupRequest(
            taskId, scripts.install.getPath(), paths.getDesktopSpecFile(distro.getId()).getPath(),
            paths.getRuntimeDirectory().getPath()));
        return taskId;
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
        LaunchScriptInstaller.DesktopScripts scripts = deploy(distro);
        LocalGamesHost host = LocalGames.requireHost(context);
        host.prepareLaunchRuntime();
        host.startLaunch(new LaunchRequest("desktop-start-" + distro.getId() + "-"
            + System.currentTimeMillis(), scripts.start.getPath(),
            paths.getDesktopSpecFile(distro.getId()).getPath(),
            paths.getRuntimeDirectory().getPath(), LaunchExecutionMode.APP_SHELL));
    }

    /** Stops the compositor and, in proot mode, the container's Plasma processes. */
    public void stop(@NonNull DesktopDistro distro) throws LaunchHostException {
        LaunchScriptInstaller.DesktopScripts scripts = deploy(distro);
        LocalGames.requireHost(context).startLaunch(new LaunchRequest("desktop-stop-"
            + distro.getId() + "-" + System.currentTimeMillis(), scripts.stop.getPath(),
            paths.getDesktopSpecFile(distro.getId()).getPath(),
            paths.getRuntimeDirectory().getPath(), LaunchExecutionMode.APP_SHELL));
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
