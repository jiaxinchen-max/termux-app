package com.termux.app.localgames;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.api.LocalGamesHostFactory;
import com.termux.localgames.api.AppExperienceMode;
import com.termux.localgames.api.LegacyRuntimeConfiguration;
import com.termux.localgames.api.LaunchRequest;
import com.termux.localgames.api.LaunchHostException;
import com.termux.localgames.api.RuntimeProvisionRequest;
import com.termux.localgames.api.RuntimeComponentActivation;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.api.ResolvedGameDirectory;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.importer.SafGameRootUri;
import com.termux.app.TermuxService;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.termuxbox.TermuxBoxContainerSpec;
import com.termux.app.activities.termuxbox.TermuxBoxPackageSpec;
import com.termux.app.activities.termuxbox.TermuxBoxRepository;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.File;
import java.io.IOException;

/**
 * Main-app integration bridge for the standalone games feature.
 *
 * Keep app-owned Termux service and process details behind this factory so the
 * feature module never needs a dependency on {@code :app}.
 */
public final class TermuxLocalGamesHostFactory implements LocalGamesHostFactory {

    private static final String LOG_TAG = "TermuxLocalGamesHost";

    @NonNull
    @Override
    public LocalGamesHost create(@NonNull Context applicationContext) {
        return new TermuxLocalGamesHost(applicationContext);
    }

    private static final class TermuxLocalGamesHost implements LocalGamesHost {

        private final Context applicationContext;
        private final TermuxRuntimeComponentProbe runtimeComponentProbe;
        private final TermuxAppExperienceStore appExperienceStore;

        private TermuxLocalGamesHost(@NonNull Context applicationContext) {
            this.applicationContext = applicationContext.getApplicationContext();
            runtimeComponentProbe = new TermuxRuntimeComponentProbe(
                new File(TermuxConstants.TERMUX_FILES_DIR_PATH));
            appExperienceStore = new TermuxAppExperienceStore(this.applicationContext);
        }

        @Override
        public AppExperienceMode getAppExperienceMode() {
            return appExperienceStore.get();
        }

        @Override
        public void switchAppExperienceMode(AppExperienceMode mode) {
            if (mode == null) throw new IllegalArgumentException("app_experience_mode_required");
            if (mode == appExperienceStore.get()) return;
            if (!appExperienceStore.set(mode)) {
                throw new IllegalStateException("app_experience_persist_failed");
            }
            TermuxAppRestart.coldRestart(applicationContext);
        }

        @Override
        public void openTerminal() {
            applicationContext.startActivity(TermuxActivity.createSessionOnlyIntent(applicationContext));
        }

        @Override
        public boolean isRuntimeAvailable() {
            return TermuxFileUtils.isTermuxFilesDirectoryAccessible(applicationContext, false, false) == null;
        }

        @Override
        public boolean isRuntimeComponentAvailable(String componentId) {
            return runtimeComponentProbe.isAvailable(componentId);
        }

        @Override
        public boolean isRuntimeComponentAvailable(String componentId, int version,
                                                   String sha256) {
            return runtimeComponentProbe.isAvailable(componentId, version);
        }

        @Override
        public boolean activateRuntimeComponent(RuntimeComponentActivation activation) {
            if (activation == null) return false;
            // Reuse the app-owned installer so the on-disk layout and the
            // package-manager metadata the runtime probe reads stay produced by one
            // implementation: extract, require a top-level glibc/, copy into
            // $PREFIX/glibc, then write installed/{name,name_lists,name_md5}.
            TermuxBoxPackageSpec spec = new TermuxBoxPackageSpec(
                activation.getComponentId(), activation.getVersion(), activation.getCategory(),
                activation.getUrl(), activation.getSize(), activation.getSha256());
            try {
                requirePrivatePath(activation.getPreparedDirectory().getPath());
                new TermuxBoxRepository(applicationContext)
                    .installPackageFromPreparedDirectory(spec,
                        activation.getPreparedDirectory(), null);
            } catch (IOException | RuntimeException error) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Unable to activate runtime component " + activation.getComponentId(), error);
                return false;
            }
            return runtimeComponentProbe.isAvailable(activation.getComponentId(),
                activation.getVersion());
        }

        @Override
        public List<LegacyRuntimeConfiguration> listLegacyRuntimeConfigurations() {
            List<LegacyRuntimeConfiguration> result = new ArrayList<>();
            for (TermuxBoxContainerSpec spec :
                new TermuxBoxRepository(applicationContext).getContainers()) {
                try {
                    result.add(new LegacyRuntimeConfiguration(spec.id, spec.name,
                        spec.wineVersion, spec.graphicsDriver, spec.dxwrapper,
                        spec.audioDriver, spec.screenSize, spec.box64Preset,
                        spec.envVars, spec.dinputMapperType == 1 ? "xinput" : "dinput"));
                } catch (IllegalArgumentException ignored) {
                    // Isolate malformed legacy entries without rewriting their source files.
                }
            }
            return Collections.unmodifiableList(result);
        }

        @Override
        public List<String> requiredGameDirectoryPermissions() {
            return Collections.singletonList(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        @Override
        public ResolvedGameDirectory resolveGameDirectory(String treeUri) {
            try {
                Uri uri = Uri.parse(treeUri);
                if ("file".equals(uri.getScheme())) {
                    String path = uri.getPath();
                    if (path == null) return ResolvedGameDirectory.blocked(
                        "game_root_not_posix_accessible");
                    File gamesRoot = new GameStoragePaths(applicationContext.getFilesDir())
                        .getImportedGamesDirectory().getCanonicalFile();
                    File candidate = new File(path).getCanonicalFile();
                    String prefix = gamesRoot.getPath() + File.separator;
                    if (!(candidate.equals(gamesRoot) || candidate.getPath().startsWith(prefix)) ||
                        !candidate.isDirectory() || !candidate.canRead()) {
                        return ResolvedGameDirectory.blocked("game_root_not_posix_accessible");
                    }
                    return ResolvedGameDirectory.resolved(candidate.getPath());
                }
                if (!"content".equals(uri.getScheme()) ||
                    !"com.android.externalstorage.documents".equals(uri.getAuthority())) {
                    return ResolvedGameDirectory.blocked("game_root_not_posix_accessible");
                }
                String documentId = SafGameRootUri.rootDocumentId(uri);
                int split = documentId.indexOf(':');
                if (split < 0) return ResolvedGameDirectory.blocked("game_root_not_posix_accessible");
                String volume = documentId.substring(0, split);
                String relative = documentId.substring(split + 1);
                File volumeRoot;
                if ("primary".equalsIgnoreCase(volume)) {
                    volumeRoot = Environment.getExternalStorageDirectory();
                } else if (volume.matches("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}")) {
                    volumeRoot = new File("/storage", volume);
                } else {
                    return ResolvedGameDirectory.blocked("game_root_not_posix_accessible");
                }
                File canonicalRoot = volumeRoot.getCanonicalFile();
                File candidate = relative.isEmpty() ? canonicalRoot :
                    new File(canonicalRoot, relative).getCanonicalFile();
                String prefix = canonicalRoot.getPath() + File.separator;
                if (!(candidate.equals(canonicalRoot) || candidate.getPath().startsWith(prefix)) ||
                    !candidate.isDirectory() || !candidate.canRead()) {
                    return ResolvedGameDirectory.blocked("game_root_not_posix_accessible");
                }
                return ResolvedGameDirectory.resolved(candidate.getPath());
            } catch (RuntimeException | IOException error) {
                return ResolvedGameDirectory.blocked("game_root_not_posix_accessible");
            }
        }

        @Override
        public void prepareLaunchRuntime() throws LaunchHostException {
            try {
                TermuxGamesX11BridgeInstaller.ensureInstalled(applicationContext);
            } catch (IOException | RuntimeException error) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Unable to install Games X11 bridge", error);
                throw new LaunchHostException("x11_bridge_install_failed", true, error);
            }
        }

        @Override
        public void startLaunch(LaunchRequest request) throws LaunchHostException {
            try {
                requirePrivatePath(request.getScriptPath());
                requirePrivatePath(request.getSpecPath());
                requirePrivatePath(request.getWorkingDirectory());
                String shell = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                if (!new File(shell).isFile()) throw new IOException("termux_shell_missing");
                Intent intent = createLaunchIntent(applicationContext, shell, request);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent);
                } else {
                    applicationContext.startService(intent);
                }
            } catch (IOException | RuntimeException error) {
                String code = "launch_host_start_failed";
                if ("termux_shell_missing".equals(error.getMessage())) code = error.getMessage();
                throw new LaunchHostException(code, true, error);
            }
        }

        @Override
        public void startRuntimeProvision(RuntimeProvisionRequest request)
            throws LaunchHostException {
            prepareLaunchRuntime();
            try {
                requirePrivatePath(request.getScriptPath());
                requirePrivatePath(request.getSpecPath());
                requirePrivatePath(request.getWorkingDirectory());
                String shell = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                if (!new File(shell).isFile()) throw new IOException("termux_shell_missing");
                Intent intent = createProvisionIntent(applicationContext, shell, request);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent);
                } else {
                    applicationContext.startService(intent);
                }
            } catch (IOException | RuntimeException error) {
                String code = "runtime_provision_host_start_failed";
                if ("termux_shell_missing".equals(error.getMessage())) code = error.getMessage();
                throw new LaunchHostException(code, true, error);
            }
        }

        @Override
        public TerminalSession getRuntimeProvisionTerminal(String taskId) {
            return TermuxGamesProvisionTerminalRegistry.find(taskId);
        }

        @Override
        public boolean isProcessAlive(long pid) {
            if (pid <= 0) return false;
            try {
                Os.kill((int) pid, 0);
                return true;
            } catch (ErrnoException error) {
                return error.errno == OsConstants.EPERM;
            }
        }

        @Override
        public void stopLaunch(long pid, boolean force) throws LaunchHostException {
            if (pid <= 0 || pid > Integer.MAX_VALUE) return;
            try {
                Os.kill((int) pid, force ? OsConstants.SIGKILL : OsConstants.SIGTERM);
            } catch (ErrnoException error) {
                if (error.errno != OsConstants.ESRCH) {
                    throw new LaunchHostException("launch_host_stop_failed", true, error);
                }
            }
        }

        private void requirePrivatePath(String path) throws IOException {
            File privateRoot = applicationContext.getFilesDir().getCanonicalFile();
            File candidate = new File(path).getCanonicalFile();
            if (!(candidate.equals(privateRoot) ||
                candidate.getPath().startsWith(privateRoot.getPath() + File.separator))) {
                throw new IOException("hidden_launch_path_outside_private_storage");
            }
        }
    }

    static Intent createLaunchIntent(Context context, String shell, LaunchRequest request) {
        boolean terminal = request.getExecutionMode() == LaunchExecutionMode.TERMINAL_SESSION;
        Intent intent = new Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
        intent.setClass(context, TermuxService.class);
        intent.setData(Uri.parse(shell));
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS,
            new String[] {request.getScriptPath(), request.getSpecPath()});
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR,
            request.getWorkingDirectory());
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER,
            terminal ? ExecutionCommand.Runner.TERMINAL_SESSION.getName()
                : ExecutionCommand.Runner.APP_SHELL.getName());
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, !terminal);
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_NAME,
            request.getShellName());
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE,
            ExecutionCommand.ShellCreateMode.NO_SHELL_WITH_NAME.getMode());
        if (terminal) {
            intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION,
                Integer.toString(TermuxConstants.TERMUX_APP.TERMUX_SERVICE
                    .VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY));
        }
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_LABEL,
            "Games launch " + request.getTaskId());
        return intent;
    }

    static Intent createProvisionIntent(Context context, String shell,
                                        RuntimeProvisionRequest request) {
        Intent intent = new Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
        intent.setClass(context, TermuxService.class);
        intent.setData(Uri.parse(shell));
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS,
            new String[] {request.getScriptPath(), request.getSpecPath()});
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR,
            request.getWorkingDirectory());
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER,
            ExecutionCommand.Runner.TERMINAL_SESSION.getName());
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, false);
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_NAME,
            request.getShellName());
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE,
            ExecutionCommand.ShellCreateMode.NO_SHELL_WITH_NAME.getMode());
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION,
            Integer.toString(TermuxConstants.TERMUX_APP.TERMUX_SERVICE
                .VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY));
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_LABEL,
            "Games runtime provision " + request.getTaskId());
        return intent;
    }
}
