package com.termux.localgames.components.index;

import com.termux.localgames.domain.GameRuntimeBackendType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ComponentIndex {

    private final int schemaVersion;
    private final String generatedAt;
    private final List<ComponentDescriptor> components;
    private final Map<String, ComponentDescriptor> byId;

    ComponentIndex(int schemaVersion, String generatedAt,
                   List<ComponentDescriptor> components) {
        this.schemaVersion = schemaVersion;
        this.generatedAt = generatedAt;
        this.components = Collections.unmodifiableList(new ArrayList<>(components));
        Map<String, ComponentDescriptor> index = new LinkedHashMap<>();
        for (ComponentDescriptor descriptor : components) {
            if (index.put(descriptor.getId(), descriptor) != null) {
                throw new IllegalArgumentException("duplicate component id: " + descriptor.getId());
            }
        }
        if (index.isEmpty()) {
            throw new IllegalArgumentException("component index is empty");
        }
        this.byId = Collections.unmodifiableMap(index);
    }

    public int getSchemaVersion() { return schemaVersion; }
    public String getGeneratedAt() { return generatedAt; }
    public List<ComponentDescriptor> getComponents() { return components; }
    public Optional<ComponentDescriptor> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<ComponentDescriptor> selectable(ComponentType type,
                                                GameRuntimeBackendType backend) {
        List<ComponentDescriptor> result = new ArrayList<>();
        for (ComponentDescriptor descriptor : components) {
            if (descriptor.getType() == type && descriptor.isSelectable() &&
                descriptor.supportsBackend(backend)) {
                result.add(descriptor);
            }
        }
        result.sort(ComponentIndex::compareForPresentation);
        return Collections.unmodifiableList(result);
    }

    public static int compareForPresentation(ComponentDescriptor left,
                                             ComponentDescriptor right) {
        int recommended = Boolean.compare(right.isRecommended(), left.isRecommended());
        if (recommended != 0) return recommended;
        int version = right.getVersionName().compareToIgnoreCase(left.getVersionName());
        if (version != 0) return version;
        return left.getDisplayName().compareToIgnoreCase(right.getDisplayName());
    }
}
