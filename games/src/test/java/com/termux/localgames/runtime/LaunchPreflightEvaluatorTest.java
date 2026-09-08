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

    @Test
    public void readyWhenAccessRuntimeComponentsAndStorageAreSatisfied() throws Exception {
        ComponentIndex index = index();
        RuntimeProfile profile = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED);

        Map<String, InstalledComponentVersion> installed = installed(index);
        addHostCapability(installed, "termux-x11");
        LaunchPreflightResult result = new LaunchPreflightEvaluator(index).evaluate(profile,
            GameAccessState.ACCESSIBLE, true, 2L * 1024 * 1024 * 1024, installed);

        assertTrue(result.isReady());
        assertEquals(9, result.getRequirements().size());
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
        String[] ids = {"hangover-11.9-debian13-source"};
        ComponentIndex index = index(ids);
        RuntimeProfile profile = new RuntimeProfile("game-1", "hangover-11.9",
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
        assertEquals(1, result.getRequirements().size());
    }

    @Test
    public void rootfsBackendBlocksWhenHostProotIsMissing() throws Exception {
        String[] ids = {"hangover-11.9-debian13-source"};
        ComponentIndex index = index(ids);
        RuntimeProfile profile = new RuntimeProfile("game-1", "hangover-11.9",
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
        String[] ids = {"hangover-11.9-debian13-source"};
        ComponentIndex index = index(ids);
        RuntimeProfile profile = new RuntimeProfile("game-1", "hangover-11.9",
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
        String[] ids = {"scripts", "glibc-prefix", "box64-binaries", "prefix-apps",
            "libudev", "en-ru-locale", "turnip", "dxvk", "wine-9.3-vanilla-wow64"};
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
            String backend = "hangover-11.9-debian13-source".equals(ids[index])
                ? "rootfs_proot" : "glibc_termux_box";
            json.append("{\"id\":\"").append(ids[index]).append("\",\"category\":\"")
                .append(category).append("\",\"type\":\"").append(type)
                .append("\",\"displayName\":\"Component ").append(ids[index])
                .append("\",\"versionName\":\"v1\",\"summary\":\"test component\",")
                .append("\"framework\":\"Termux GLIBC\",\"base\":false,")
                .append("\"recommended\":false,\"profileValue\":\"")
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
