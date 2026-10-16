package com.termux.localgames.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.components.index.ComponentIndexParser;
import com.termux.localgames.data.GameAccessState;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.LaunchExecutionMode;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class LaunchPreflightEvaluatorTest {

    private static final String SHA =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER_SHA =
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    public void readyWhenAccessRuntimeComponentsAndStorageAreSatisfied() throws Exception {
        ComponentIndex index = index();
        RuntimeProfile profile = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED);

        Map<String, InstalledComponentVersion> installed = installed(index);
        addHostCapability(installed, "termux-x11");
        addHostCapability(installed, "termux-glibc");
        LaunchPreflightResult result = new LaunchPreflightEvaluator(index).evaluate(profile,
            GameAccessState.ACCESSIBLE, true, 2L * 1024 * 1024 * 1024, installed);

        assertTrue(result.isReady());
        assertEquals(6, result.getRequirements().size());
        assertEquals(LaunchPreflightEvaluator.DEFAULT_PREFIX_RESERVE_BYTES,
            result.getStorageBudget().getRequiredBytes());
    }

    @Test
    public void reportsPermissionRuntimeMissingComponentsAndStorage() throws Exception {
        ComponentIndex index = index();
        RuntimeProfile profile = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED);

        LaunchPreflightResult result = new LaunchPreflightEvaluator(index).evaluate(profile,
            GameAccessState.PERMISSION_LOST, false, 1, Collections.emptyMap());

        assertFalse(result.isReady());
        assertTrue(has(result, PreflightIssueCode.PERMISSION_LOST));
        assertTrue(has(result, PreflightIssueCode.RUNTIME_UNAVAILABLE));
        assertTrue(has(result, PreflightIssueCode.COMPONENT_MISSING));
        assertTrue(has(result, PreflightIssueCode.STORAGE_INSUFFICIENT));
        assertTrue(result.getStorageBudget().getRequiredBytes() >
            LaunchPreflightEvaluator.DEFAULT_PREFIX_RESERVE_BYTES);
    }

    @Test
    public void reportsReceiptMismatchAndUnavailablePinnedVersion() throws Exception {
        ComponentIndex index = index();
        RuntimeProfile base = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED);
        Map<String, String> pins = new LinkedHashMap<>();
        pins.put("dxvk", "99");
        RuntimeProfile pinned = new RuntimeProfile(base.getId(), base.getWinePackage(),
            base.getGraphicsDriver(), base.getDxWrapper(), base.getAudioDriver(),
            base.getResolution(), base.getBox64Preset(), base.getEnvironment(),
            base.getInputProfileId(), pins);
        Map<String, InstalledComponentVersion> installed = installed(index);
        installed.put("turnip", new InstalledComponentVersion(2,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));

        LaunchPreflightResult result = new LaunchPreflightEvaluator(index).evaluate(pinned,
            GameAccessState.ACCESSIBLE, true, Long.MAX_VALUE, installed);

        assertTrue(has(result, PreflightIssueCode.COMPONENT_VERSION_UNAVAILABLE));
        assertTrue(has(result, PreflightIssueCode.COMPONENT_VERSION_MISMATCH));
    }

    @Test
    public void rootfsBackendUsesVerifiedSourceAndTermuxBuilderCapabilities() throws Exception {
        String[] ids = {"rootfs_hangover_11.16", "rootfs_box64_0.4.5", "rootfs_dxvk_2.7", "rootfs_dxvk_3.1",
            "rootfs_dxvk_1.10.3", "rootfs_wine_10.0", "rootfs_proton_11.0-2"};
        ComponentIndex index = index(ids);
        RuntimeProfile profile = new RuntimeProfile("game-1", "rootfs_hangover_11.16",
            "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs");
        Map<String, InstalledComponentVersion> installed = installed(index);
        addHostCapability(installed, "termux-x11");
        addHostCapability(installed, "proot");
        addHostCapability(installed, "proot-distro");

        LaunchPreflightResult result = new LaunchPreflightEvaluator(index,
            LaunchPreflightEvaluator.DEFAULT_PREFIX_RESERVE_BYTES,
            new RootfsProotBackend()).evaluate(profile, GameAccessState.ACCESSIBLE,
            true, Long.MAX_VALUE, installed);

        assertTrue(result.isReady());
        // The whole framework-delivered base set is required before the shared image can build.
        assertEquals(7, result.getRequirements().size());
    }

    @Test
    public void rootfsBackendBlocksWhenHostProotIsMissing() throws Exception {
        String[] ids = {"rootfs_hangover_11.16"};
        ComponentIndex index = index(ids);
        RuntimeProfile profile = new RuntimeProfile("game-1", "rootfs_hangover_11.16",
            "rootfs-virgl-mesa", "rootfs-wined3d", "pulseaudio", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs");

        LaunchPreflightResult result = new LaunchPreflightEvaluator(index,
            LaunchPreflightEvaluator.DEFAULT_PREFIX_RESERVE_BYTES,
            new RootfsProotBackend()).evaluate(profile, GameAccessState.ACCESSIBLE,
            true, Long.MAX_VALUE, installed(index));

        assertFalse(result.isReady());
        assertTrue(hasSubject(result, PreflightIssueCode.COMPONENT_MISSING, "proot"));
    }

    @Test
    public void rootfsVirglBlocksWhenHostServerIsMissing() throws Exception {
        String[] ids = {"rootfs_hangover_11.16"};
        ComponentIndex index = index(ids);
        RuntimeProfile profile = new RuntimeProfile("game-1", "rootfs_hangover_11.16",
            "rootfs-virgl-mesa", "rootfs-wined3d", "pulseaudio", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs");
        Map<String, InstalledComponentVersion> installed = installed(index);
        installed.put("proot", new InstalledComponentVersion(1,
            "0000000000000000000000000000000000000000000000000000000000000000"));
        installed.put("proot-distro", new InstalledComponentVersion(1,
            "0000000000000000000000000000000000000000000000000000000000000000"));

        LaunchPreflightResult result = new LaunchPreflightEvaluator(index,
            LaunchPreflightEvaluator.DEFAULT_PREFIX_RESERVE_BYTES,
            new RootfsProotBackend()).evaluate(profile, GameAccessState.ACCESSIBLE,
            true, Long.MAX_VALUE, installed);

        assertFalse(result.isReady());
        assertTrue(hasSubject(result, PreflightIssueCode.COMPONENT_MISSING,
            "virgl-server"));
    }

    @Test
    public void rootfsAcceptsNewerInstalledVersionForComponentsResolvedAtRebuild() throws Exception {
        // The rebuild resolves these three against upstream's latest release, so what is installed
        // is routinely newer than the catalog's pinned offline-fallback entry. That must not block
        // every launch (it used to fail with preflight_component_version_mismatch).
        ComponentIndex index = rootfsIndex();
        Map<String, InstalledComponentVersion> installed = rootfsInstalled(index);
        for (String id : new String[] {RootfsSetupRecipe.DEFAULT_SOURCE,
            RootfsSetupRecipe.BOX64_WINE_COMPONENT, RootfsSetupRecipe.BOX64_PROTON_COMPONENT}) {
            installed.put(id, new InstalledComponentVersion(7, OTHER_SHA));
        }

        LaunchPreflightResult result = evaluateRootfs(index, installed);

        assertFalse(has(result, PreflightIssueCode.COMPONENT_VERSION_MISMATCH));
        assertTrue(result.isReady());
    }

    @Test
    public void rootfsStillRequiresExactVersionForPinnedComponents() throws Exception {
        ComponentIndex index = rootfsIndex();
        Map<String, InstalledComponentVersion> installed = rootfsInstalled(index);
        installed.put("rootfs_dxvk_2.7", new InstalledComponentVersion(1, OTHER_SHA));

        LaunchPreflightResult result = evaluateRootfs(index, installed);

        assertFalse(result.isReady());
        assertTrue(hasSubject(result, PreflightIssueCode.COMPONENT_VERSION_MISMATCH,
            "rootfs_dxvk_2.7"));
    }

    @Test
    public void rootfsStillReportsMissingComponentsResolvedAtRebuild() throws Exception {
        ComponentIndex index = rootfsIndex();
        Map<String, InstalledComponentVersion> installed = rootfsInstalled(index);
        installed.remove(RootfsSetupRecipe.DEFAULT_SOURCE);

        LaunchPreflightResult result = evaluateRootfs(index, installed);

        assertFalse(result.isReady());
        assertTrue(hasSubject(result, PreflightIssueCode.COMPONENT_MISSING,
            RootfsSetupRecipe.DEFAULT_SOURCE));
    }

    private static ComponentIndex rootfsIndex() throws Exception {
        return index(RootfsSetupRecipe.DEFAULT_BASE_COMPONENTS.toArray(new String[0]));
    }

    private static Map<String, InstalledComponentVersion> rootfsInstalled(ComponentIndex index) {
        Map<String, InstalledComponentVersion> installed = installed(index);
        addHostCapability(installed, "termux-x11");
        addHostCapability(installed, "proot");
        addHostCapability(installed, "proot-distro");
        return installed;
    }

    private static LaunchPreflightResult evaluateRootfs(
            ComponentIndex index, Map<String, InstalledComponentVersion> installed) {
        RuntimeProfile profile = new RuntimeProfile("game-1", "rootfs_hangover_11.16",
            "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs");
        return new LaunchPreflightEvaluator(index,
            LaunchPreflightEvaluator.DEFAULT_PREFIX_RESERVE_BYTES,
            new RootfsProotBackend()).evaluate(profile, GameAccessState.ACCESSIBLE,
            true, Long.MAX_VALUE, installed);
    }

    private static boolean has(LaunchPreflightResult result, PreflightIssueCode code) {
        for (PreflightIssue issue : result.getIssues()) {
            if (issue.getCode() == code) return true;
        }
        return false;
    }

    private static boolean hasSubject(LaunchPreflightResult result, PreflightIssueCode code,
                                      String subject) {
        for (PreflightIssue issue : result.getIssues()) {
            if (issue.getCode() == code && subject.equals(issue.getSubject())) return true;
        }
        return false;
    }

    private static Map<String, InstalledComponentVersion> installed(ComponentIndex index) {
        Map<String, InstalledComponentVersion> result = new LinkedHashMap<>();
        for (ComponentDescriptor descriptor : index.getComponents()) {
            result.put(descriptor.getId(), new InstalledComponentVersion(
                descriptor.getVersion(), descriptor.getSha256()));
        }
        return result;
    }

    private static void addHostCapability(Map<String, InstalledComponentVersion> installed,
                                          String id) {
        installed.put(id, new InstalledComponentVersion(1,
            "0000000000000000000000000000000000000000000000000000000000000000"));
    }

    private static ComponentIndex index() throws Exception {
        String[] ids = {"glibc_box64_0.2.7", "glibc_scripts_29", "glibc_prefix-apps_3", "turnip", "dxvk",
            "glibc_wine_9.3-vanilla"};
        return index(ids);
    }

    private static ComponentIndex index(String[] ids) throws Exception {
        StringBuilder json = new StringBuilder(
            "{\"schemaVersion\":2,\"generatedAt\":\"now\",\"packages\":[");
        for (int index = 0; index < ids.length; index++) {
            if (index > 0) json.append(',');
            boolean wine = ids[index].startsWith("wine-");
            String category = wine ? "wine" : "runtime";
            String type = wine ? "container" : "runtime_support";
            String backend = "rootfs_hangover_11.16".equals(ids[index])
                ? "rootfs_proot" : "glibc_termux_box";
            json.append("{\"id\":\"").append(ids[index]).append("\",\"category\":\"")
                .append(category).append("\",\"type\":\"").append(type)
                .append("\",\"displayName\":\"Component ").append(ids[index])
                .append("\",\"versionName\":\"v1\",\"summary\":\"test component\",")
                .append("\"framework\":\"Termux GLIBC\",\"base\":false,")
                .append("\"recommended\":false,\"hidden\":false,\"profileValue\":\"")
                .append(wine ? ids[index] : "").append("\",\"runtimeBackends\":[\"")
                .append(backend).append("\"],\"version\":1,")
                .append("\"url\":\"https://example.invalid/")
                .append(ids[index]).append(".tar.xz\",\"size\":100,\"sha256\":\"")
                .append(SHA).append("\"}");
        }
        json.append("]}");
        return new ComponentIndexParser().parse(new ByteArrayInputStream(
            json.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
