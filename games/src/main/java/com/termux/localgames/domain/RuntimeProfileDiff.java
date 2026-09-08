package com.termux.localgames.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class RuntimeProfileDiff {

    private RuntimeProfileDiff() {}

    public static List<RuntimeProfileChange> between(RuntimeProfile before,
                                                     RuntimeProfile after) {
        if (before == null || after == null) {
            throw new IllegalArgumentException("profiles must not be null");
        }
        List<RuntimeProfileChange> changes = new ArrayList<>();
        add(changes, "runtimeBackendType",
            before.getRuntimeBackendType().getStorageValue(),
            after.getRuntimeBackendType().getStorageValue());
        add(changes, "rootfsPackage", before.getRootfsPackage(), after.getRootfsPackage());
        add(changes, "winePackage", before.getWinePackage(), after.getWinePackage());
        add(changes, "graphicsDriver", before.getGraphicsDriver(), after.getGraphicsDriver());
        add(changes, "dxWrapper", before.getDxWrapper(), after.getDxWrapper());
        add(changes, "audioDriver", before.getAudioDriver(), after.getAudioDriver());
        add(changes, "resolution", before.getResolution(), after.getResolution());
        add(changes, "box64Preset", before.getBox64Preset(), after.getBox64Preset());
        add(changes, "inputProfileId", before.getInputProfileId(), after.getInputProfileId());
        add(changes, "launchExecutionMode",
            before.getLaunchExecutionMode().getStorageValue(),
            after.getLaunchExecutionMode().getStorageValue());
        addMap(changes, "environment.", before.getEnvironment(), after.getEnvironment());
        addMap(changes, "componentVersions.", before.getComponentVersions(),
            after.getComponentVersions());
        return Collections.unmodifiableList(changes);
    }

    private static void addMap(List<RuntimeProfileChange> changes, String prefix,
                               Map<String, String> before, Map<String, String> after) {
        Map<String, String> keys = new TreeMap<>();
        for (String key : before.keySet()) keys.put(key, key);
        for (String key : after.keySet()) keys.put(key, key);
        for (String key : keys.keySet()) {
            add(changes, prefix + key, before.getOrDefault(key, ""),
                after.getOrDefault(key, ""));
        }
    }

    private static void add(List<RuntimeProfileChange> changes, String field,
                            String before, String after) {
        if (!before.equals(after)) changes.add(new RuntimeProfileChange(field, before, after));
    }
}
