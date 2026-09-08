package com.termux.localgames.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LaunchPreflightResult {
    private final List<ComponentRequirement> requirements;
    private final List<PreflightIssue> issues;
    private final StorageBudget storageBudget;

    LaunchPreflightResult(List<ComponentRequirement> requirements,
                          List<PreflightIssue> issues, StorageBudget storageBudget) {
        this.requirements = Collections.unmodifiableList(new ArrayList<>(requirements));
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        this.storageBudget = storageBudget;
    }

    public boolean isReady() { return issues.isEmpty(); }
    public List<ComponentRequirement> getRequirements() { return requirements; }
    public List<PreflightIssue> getIssues() { return issues; }
    public StorageBudget getStorageBudget() { return storageBudget; }

    public LaunchPreflightResult withIssue(PreflightIssue issue) {
        if (issue == null) throw new IllegalArgumentException("issue must not be null");
        List<PreflightIssue> updated = new ArrayList<>(issues);
        updated.add(issue);
        return new LaunchPreflightResult(requirements, updated, storageBudget);
    }

    public LaunchPreflightResult withIssueFirst(PreflightIssue issue) {
        if (issue == null) throw new IllegalArgumentException("issue must not be null");
        List<PreflightIssue> updated = new ArrayList<>();
        updated.add(issue);
        updated.addAll(issues);
        return new LaunchPreflightResult(requirements, updated, storageBudget);
    }
}
