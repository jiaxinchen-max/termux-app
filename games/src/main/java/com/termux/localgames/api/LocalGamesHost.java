package com.termux.localgames.api;

import java.util.Collections;
import java.util.List;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

/** Narrow main-app capability surface exposed to the games module. */
public interface LocalGamesHost {

    /** Returns the process-frozen top-level interaction mode. */
    default AppExperienceMode getAppExperienceMode() { return AppExperienceMode.TERMINAL; }

    /** Persists a different interaction mode and cold-restarts the host app. */
    default void switchAppExperienceMode(AppExperienceMode mode) {
        throw new UnsupportedOperationException("app_experience_switch_not_supported");
    }

    /** Opens the session-only terminal surface, including while Games is the root mode. */
    default void openTerminal() {
        throw new UnsupportedOperationException("open_terminal_not_supported");
    }

    /** Returns whether the Termux runtime storage is currently accessible. */
    boolean isRuntimeAvailable();

    /** Returns whether the component is active in the runtime consumed by game launches. */
    default boolean isRuntimeComponentAvailable(String componentId) { return false; }

    /** Returns whether the exact catalog version is active in the launcher runtime. */
    default boolean isRuntimeComponentAvailable(String componentId, int version,
                                                String sha256) {
        return isRuntimeComponentAvailable(componentId);
    }

    /**
     * Publishes a verified prepared component into the runtime that game launches actually read.
     *
     * <p>The games module owns its own transactional install tree, but launches consume the
     * host's runtime prefix. Without this step a component is downloaded and verified yet stays
     * invisible to the launcher. Implementations must install in the layout the launch scripts
     * expect and are responsible for their own metadata.
     *
     * @return true when the component is installed and visible to
     *     {@link #isRuntimeComponentAvailable(String)}.
     */
    default boolean activateRuntimeComponent(RuntimeComponentActivation activation) {
        return false;
    }

    /** Returns detached snapshots and never mutates app-owned legacy configurations. */
    default List<LegacyRuntimeConfiguration> listLegacyRuntimeConfigurations() {
        return Collections.emptyList();
    }

    /** Runtime permissions required before a SAF tree can be exposed as a POSIX path. */
    default List<String> requiredGameDirectoryPermissions() {
        return Collections.emptyList();
    }

    /** Maps a SAF tree to a canonical local path or returns a stable blocking reason. */
    default ResolvedGameDirectory resolveGameDirectory(String treeUri) {
        return ResolvedGameDirectory.blocked("game_root_not_posix_accessible");
    }

    /** Ensures host-owned launch bridges are installed before preflight probes them. */
    default void prepareLaunchRuntime() throws LaunchHostException { }

    /** Submits one stable-name launch using the request's existing Termux runner. */
    default void startLaunch(LaunchRequest request) throws LaunchHostException {
        throw new UnsupportedOperationException("launch_host_not_supported");
    }

    /** Runs a trusted Games provisioning script in a visible Termux terminal session. */
    default void startRuntimeProvision(RuntimeProvisionRequest request) throws LaunchHostException {
        throw new UnsupportedOperationException("runtime_provision_host_not_supported");
    }

    /** Returns the visible terminal session owned by an active provisioning task, if ready. */
    @Nullable
    default TerminalSession getRuntimeProvisionTerminal(String taskId) { return null; }

    /** Best-effort same-UID process liveness check used only during reconciliation. */
    default boolean isProcessAlive(long pid) { return false; }

    /** Best-effort termination after a cancel marker has already been persisted. */
    default void stopLaunch(long pid, boolean force) throws LaunchHostException {
        throw new UnsupportedOperationException("launch_host_stop_not_supported");
    }
}
