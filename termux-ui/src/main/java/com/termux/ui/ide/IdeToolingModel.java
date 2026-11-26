package com.termux.ui.ide;

import org.json.*;

/** Queries original model services sequentially: upstream selection is shared mutable state. */
public final class IdeToolingModel {
    public interface Requests { Object call(String method, Object params) throws Exception; }
    private IdeToolingModel() { }
    static String projectType(Object value) throws java.io.IOException {
        if (value instanceof Number) {
            int ordinal = ((Number) value).intValue();
            if (((Number) value).doubleValue() == ordinal && ordinal >= 0 && ordinal < 4)
                return new String[] {"Gradle", "Android", "Java", "Unknown"}[ordinal];
        } else if (value instanceof String) {
            for (String type : new String[] {"Gradle", "Android", "Java", "Unknown"}) if (type.equals(value)) return type;
        }
        throw new java.io.IOException("Unsupported tooling project type: " + value);
    }
    public static JSONObject read(Requests server, String project) throws Exception {
        JSONArray projects = (JSONArray) server.call("root/getProjects", null);
        JSONArray modules = new JSONArray();
        for (int i = 0; i < projects.length(); i++) {
            JSONObject metadata = projects.getJSONObject(i);
            Object selection = server.call("selectProject", new JSONObject().put("value", metadata.getString("projectPath")));
            if (!(selection instanceof JSONObject) || !((JSONObject) selection).optBoolean("isSuccessful"))
                throw new java.io.IOException("Cannot select Gradle module: " + metadata);
            String type = projectType(server.call("getType", null));
            JSONObject module = new JSONObject().put("metadata", metadata).put("type", type)
                .put("tasks", server.call("gradle/getTasks", null));
            if ("Android".equals(type)) {
                Object variant = server.call("android/getConfiguredVariant", null);
                module.put("variant", variant).put("variants", server.call("android/getVariants", null))
                    .put("bootClasspaths", server.call("android/getBootClasspaths", null))
                    .put("sources", server.call("android/getMainSourceSet", null))
                    .put("libraries", server.call("android/getLibraryMap", null))
                    .put("artifact", server.call("android/getVariant", new JSONObject().put("value", variant)));
            } else if ("Java".equals(type)) {
                module.put("sources", server.call("java/getContentRoots", null))
                    .put("dependencies", server.call("java/getDependencies", null));
            }
            modules.put(module);
        }
        return new JSONObject().put("project", project).put("syncedAt", System.currentTimeMillis())
            .put("modules", modules).put("issues", server.call("root/getProjectSyncIssues", null));
    }
}
