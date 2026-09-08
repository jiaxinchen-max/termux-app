package com.termux.localgames.service;

import android.content.Context;
import android.os.StatFs;

import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.api.ResolvedGameDirectory;
import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.components.index.ComponentIndexParser;
import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.components.install.ComponentInstallationReader;
import com.termux.localgames.components.install.InstalledComponent;
import com.termux.localgames.data.GameAccessState;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.importer.SafGameAccessProbe;
import com.termux.localgames.runtime.InstalledComponentVersion;
import com.termux.localgames.runtime.LaunchPreflightEvaluator;
import com.termux.localgames.runtime.LaunchPreflightResult;
import com.termux.localgames.runtime.GameRuntimeBackend;
import com.termux.localgames.runtime.GameRuntimeBackendRegistry;
import com.termux.localgames.runtime.PreflightIssue;
import com.termux.localgames.runtime.PreflightIssueCode;
import com.termux.localgames.data.GameStoragePaths;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Android adapter around the same pure launch preflight used by runtime configuration UI. */
public final class AndroidLaunchPreflight {

    private static final String INDEX_ASSET = "termux-box-packages/index-v1.json";
    private final Context context;

    public AndroidLaunchPreflight(Context context) {
        this.context = context.getApplicationContext();
    }

    public LaunchPreflightResult evaluate(Game game, RuntimeProfile profile,
                                          LocalGamesHost host) throws IOException {
        ComponentIndex index;
        try (InputStream input = context.getAssets().open(INDEX_ASSET)) {
            index = new ComponentIndexParser().parse(input);
        }
        ComponentInstallationReader installationReader = new ComponentInstallationReader(
            new ComponentStoragePaths(context.getFilesDir()).getInstallDirectory());
        GameRuntimeBackend backend = GameRuntimeBackendRegistry.createDefault()
            .require(profile.getRuntimeBackendType());
        Map<String, InstalledComponentVersion> installed = resolveInstalledComponents(index,
            installationReader, profile.getRuntimeBackendType(), host);
        for (String capability : backend.requiredHostCapabilityIds(profile)) {
            try {
                if (host.isRuntimeComponentAvailable(capability)) {
                    installed.put(capability, new InstalledComponentVersion(1,
                        "0000000000000000000000000000000000000000000000000000000000000000"));
                }
            } catch (RuntimeException ignored) {
                // Capability remains unavailable.
            }
        }
        GameAccessState access = new SafGameAccessProbe(context.getContentResolver()).check(game);
        boolean runtime;
        try { runtime = host.isRuntimeAvailable(); }
        catch (RuntimeException error) { runtime = false; }
        long available = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        LaunchPreflightResult result = new LaunchPreflightEvaluator(index,
            LaunchPreflightEvaluator.DEFAULT_PREFIX_RESERVE_BYTES, backend)
            .evaluate(profile, access, runtime, available, installed);
        if (access == GameAccessState.ACCESSIBLE) {
            ResolvedGameDirectory resolved;
            try {
                resolved = host.resolveGameDirectory(game.getRootUri());
            } catch (RuntimeException error) {
                resolved = ResolvedGameDirectory.blocked("game_root_not_posix_accessible");
            }
            if (!resolved.isResolved()) {
                result = result.withIssue(new PreflightIssue(
                    PreflightIssueCode.GAME_ROOT_NOT_POSIX_ACCESSIBLE, "gameRoot"));
            }
        }
        if (profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT) {
            try {
                backend.resolveRuntimeRoot(new GameStoragePaths(context.getFilesDir()),
                    new ComponentStoragePaths(context.getFilesDir()), profile);
            } catch (IOException | RuntimeException error) {
                String subject = error.getMessage();
                if (subject == null || !subject.matches("[A-Za-z0-9._:-]{1,128}")) {
                    subject = "rootfs_manifest_invalid";
                }
                PreflightIssueCode code = "rootfs_provision_required".equals(subject)
                    ? PreflightIssueCode.RUNTIME_PROVISION_REQUIRED
                    : PreflightIssueCode.UNSUPPORTED_PROFILE_SELECTION;
                result = result.withIssueFirst(new PreflightIssue(code, subject));
            }
        } else {
            File prefix = new GameStoragePaths(context.getFilesDir())
                .getContainerPrefixDirectory(profile.getContainerId());
            if (!new File(prefix, ".termux-box-bootstrap-done").isFile()) {
                result = result.withIssue(new PreflightIssue(
                    PreflightIssueCode.RUNTIME_PROVISION_REQUIRED, "prefix_provision_required"));
            } else {
                File runtimeMarker = new File(prefix, ".termux-box-wine-package");
                if (runtimeMarker.isFile() &&
                    !profile.getWinePackage().equals(readFirstLine(runtimeMarker))) {
                    result = result.withIssue(new PreflightIssue(
                        PreflightIssueCode.RUNTIME_PROVISION_REQUIRED,
                        "prefix_runtime_mismatch"));
                }
            }
        }
        return result;
    }

    static Map<String, InstalledComponentVersion> resolveInstalledComponents(
        ComponentIndex index, ComponentInstallationReader installationReader,
        GameRuntimeBackendType backendType, LocalGamesHost host) {
        Map<String, InstalledComponentVersion> installed = new LinkedHashMap<>();
        for (ComponentDescriptor descriptor : index.getComponents()) {
            try {
                if (backendType == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
                    if (host.isRuntimeComponentAvailable(descriptor.getId(),
                        descriptor.getVersion(), descriptor.getSha256())) {
                        installed.put(descriptor.getId(), new InstalledComponentVersion(
                            descriptor.getVersion(), descriptor.getSha256()));
                    }
                } else {
                    InstalledComponent active = installationReader.read(descriptor.getId())
                        .getActive().orElse(null);
                    if (active == null) continue;
                    installed.put(descriptor.getId(), new InstalledComponentVersion(
                        active.getVersion(), active.getSha256()));
                }
            } catch (IOException | RuntimeException ignored) {
                // Host probe failures are intentionally equivalent to unavailable capability.
            }
        }
        return installed;
    }

    private static String readFirstLine(File file) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.FileReader(file))) {
            return reader.readLine();
        } catch (IOException error) {
            return null;
        }
    }
}
