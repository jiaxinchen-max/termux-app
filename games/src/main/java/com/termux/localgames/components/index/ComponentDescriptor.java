package com.termux.localgames.components.index;

import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.GameRuntimeBackendType;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class ComponentDescriptor {

    private final String id;
    private final String category;
    private final int version;
    private final String url;
    private final long size;
    private final String sha256;
    private final ComponentType type;
    private final String displayName;
    private final String versionName;
    private final String summary;
    private final String framework;
    private final boolean base;
    private final boolean recommended;
    private final String profileValue;
    private final Set<GameRuntimeBackendType> runtimeBackends;

    public ComponentDescriptor(String id, String category, int version, String url,
                               long size, String sha256, ComponentType type,
                               String displayName, String versionName, String summary,
                               String framework, boolean base, boolean recommended,
                               String profileValue,
                               Set<GameRuntimeBackendType> runtimeBackends) {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid component id");
        }
        if (!("runtime".equals(category) || "wine".equals(category))) {
            throw new IllegalArgumentException("invalid component category");
        }
        if (version < 1 || size < 1) {
            throw new IllegalArgumentException("invalid component version or size");
        }
        validateUrl(url);
        String normalizedSha = sha256 == null ? "" : sha256.toLowerCase(Locale.US);
        if (!normalizedSha.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid component SHA-256");
        }
        this.id = id;
        this.category = category;
        this.version = version;
        this.url = url;
        this.size = size;
        this.sha256 = normalizedSha;
        if (type == null) throw new IllegalArgumentException("component type required");
        this.type = type;
        this.displayName = requireText(displayName, "displayName");
        this.versionName = requireText(versionName, "versionName");
        this.summary = requireText(summary, "summary");
        this.framework = requireText(framework, "framework");
        this.base = base;
        this.recommended = recommended;
        this.profileValue = profileValue == null ? "" : profileValue.trim();
        if (!this.profileValue.isEmpty() && !this.profileValue.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid component profileValue");
        }
        if (runtimeBackends == null || runtimeBackends.isEmpty()) {
            throw new IllegalArgumentException("runtimeBackends must not be empty");
        }
        if (runtimeBackends.contains(null)) {
            throw new IllegalArgumentException("runtimeBackends contains null");
        }
        this.runtimeBackends = Collections.unmodifiableSet(
            new LinkedHashSet<>(runtimeBackends));
        if (!this.profileValue.isEmpty() && !isSelectableType(type)) {
            throw new IllegalArgumentException("profileValue not supported for component type");
        }
    }

    public ComponentTask createTask(String taskId) {
        return ComponentTask.queued(taskId, id, version, url, size, sha256);
    }

    public String getId() { return id; }
    public String getCategory() { return category; }
    public int getVersion() { return version; }
    public String getUrl() { return url; }
    public long getSize() { return size; }
    public String getSha256() { return sha256; }
    public ComponentType getType() { return type; }
    public String getDisplayName() { return displayName; }
    public String getVersionName() { return versionName; }
    public String getSummary() { return summary; }
    public String getFramework() { return framework; }
    public boolean isBase() { return base; }
    public boolean isRecommended() { return recommended; }
    public boolean isSelectable() { return !profileValue.isEmpty(); }
    public String getProfileValue() { return profileValue; }
    public Set<GameRuntimeBackendType> getRuntimeBackends() { return runtimeBackends; }
    public boolean supportsBackend(GameRuntimeBackendType backend) {
        return backend != null && runtimeBackends.contains(backend);
    }

    private static void validateUrl(String value) {
        try {
            URL parsed = new URL(value);
            if (!"https".equalsIgnoreCase(parsed.getProtocol()) ||
                parsed.getHost() == null || parsed.getHost().isEmpty()) {
                throw new IllegalArgumentException("component source must use HTTPS");
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("invalid component source", e);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty() || value.length() > 240) {
            throw new IllegalArgumentException("invalid component " + field);
        }
        return value.trim();
    }

    private static boolean isSelectableType(ComponentType type) {
        return type == ComponentType.CONTAINER || type == ComponentType.GPU_DRIVER ||
            type == ComponentType.DX_WRAPPER || type == ComponentType.TRANSLATOR;
    }

}
