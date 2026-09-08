package com.termux.localgames.runtime;

import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.data.GameAccessState;
import com.termux.localgames.domain.RuntimeProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure launch gate shared by configuration UI and the future launch orchestrator. */
public final class LaunchPreflightEvaluator {

    public static final long DEFAULT_PREFIX_RESERVE_BYTES = 1024L * 1024L * 1024L;
    private static final long COMPONENT_PEAK_MULTIPLIER = 3L;
    private final ComponentIndex index;
    private final long prefixReserveBytes;
    private final GameRuntimeBackend backend;

    public LaunchPreflightEvaluator(ComponentIndex index) {
        this(index, DEFAULT_PREFIX_RESERVE_BYTES, new GlibcTermuxBoxBackend());
    }

    public LaunchPreflightEvaluator(ComponentIndex index, long prefixReserveBytes) {
        this(index, prefixReserveBytes, new GlibcTermuxBoxBackend());
    }

    public LaunchPreflightEvaluator(ComponentIndex index, long prefixReserveBytes,
                                    GameRuntimeBackend backend) {
        if (index == null || prefixReserveBytes < 0 || backend == null) {
            throw new IllegalArgumentException("invalid preflight configuration");
        }
        this.index = index;
        this.prefixReserveBytes = prefixReserveBytes;
        this.backend = backend;
    }

    public LaunchPreflightResult evaluate(RuntimeProfile profile, GameAccessState accessState,
                                          boolean runtimeAvailable, long availableBytes,
                                          Map<String, InstalledComponentVersion> installed) {
        if (profile == null || accessState == null || installed == null || availableBytes < 0) {
            throw new IllegalArgumentException("invalid preflight input");
        }
        List<PreflightIssue> issues = new ArrayList<>();
        if (accessState == GameAccessState.PERMISSION_LOST) {
            issues.add(new PreflightIssue(PreflightIssueCode.PERMISSION_LOST, "gameRoot"));
        } else if (accessState == GameAccessState.PROVIDER_UNAVAILABLE) {
            issues.add(new PreflightIssue(PreflightIssueCode.PROVIDER_UNAVAILABLE, "gameRoot"));
        }
        if (!runtimeAvailable) {
            issues.add(new PreflightIssue(PreflightIssueCode.RUNTIME_UNAVAILABLE, "termux"));
        }

        Set<String> componentIds = new LinkedHashSet<>();
        try {
            componentIds.addAll(backend.requiredComponentIds(profile));
            for (String capability : backend.requiredHostCapabilityIds(profile)) {
                if (!installed.containsKey(capability)) {
                    issues.add(new PreflightIssue(PreflightIssueCode.COMPONENT_MISSING,
                        capability));
                }
            }
        } catch (IllegalArgumentException error) {
            String subject = error.getMessage();
            if (subject == null || subject.isEmpty()) subject = "runtimeBackend";
            issues.add(new PreflightIssue(PreflightIssueCode.UNSUPPORTED_PROFILE_SELECTION,
                subject));
        }

        long requiredBytes = prefixReserveBytes;
        List<ComponentRequirement> requirements = new ArrayList<>();
        for (String componentId : componentIds) {
            ComponentDescriptor descriptor = index.find(componentId).orElse(null);
            if (descriptor == null) {
                issues.add(new PreflightIssue(PreflightIssueCode.COMPONENT_UNKNOWN, componentId));
                continue;
            }
            String pinned = profile.getComponentVersions().get(componentId);
            int expectedVersion = pinned == null ? descriptor.getVersion() : Integer.parseInt(pinned);
            requirements.add(new ComponentRequirement(componentId, expectedVersion,
                descriptor.getSha256(), descriptor.getSize()));
            if (expectedVersion != descriptor.getVersion()) {
                issues.add(new PreflightIssue(
                    PreflightIssueCode.COMPONENT_VERSION_UNAVAILABLE, componentId));
                continue;
            }
            InstalledComponentVersion active = installed.get(componentId);
            if (active == null) {
                issues.add(new PreflightIssue(PreflightIssueCode.COMPONENT_MISSING, componentId));
                requiredBytes = addSaturated(requiredBytes,
                    multiplySaturated(descriptor.getSize(), COMPONENT_PEAK_MULTIPLIER));
            } else if (active.getVersion() != expectedVersion ||
                !active.getSha256().equals(descriptor.getSha256())) {
                issues.add(new PreflightIssue(
                    PreflightIssueCode.COMPONENT_VERSION_MISMATCH, componentId));
                requiredBytes = addSaturated(requiredBytes,
                    multiplySaturated(descriptor.getSize(), COMPONENT_PEAK_MULTIPLIER));
            }
        }
        StorageBudget budget = new StorageBudget(requiredBytes, availableBytes);
        if (!budget.isSufficient()) {
            issues.add(new PreflightIssue(PreflightIssueCode.STORAGE_INSUFFICIENT, "privateStorage"));
        }
        return new LaunchPreflightResult(requirements, issues, budget);
    }

    private static long addSaturated(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static long multiplySaturated(long value, long multiplier) {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }
}
