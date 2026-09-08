package com.termux.localgames.runtime;

import com.termux.localgames.domain.GameRuntimeBackendType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Immutable backend lookup; launch attempts never fall through to another backend. */
public final class GameRuntimeBackendRegistry {

    private final Map<GameRuntimeBackendType, GameRuntimeBackend> backends;

    public GameRuntimeBackendRegistry(Iterable<GameRuntimeBackend> values) {
        if (values == null) throw new IllegalArgumentException("backends required");
        EnumMap<GameRuntimeBackendType, GameRuntimeBackend> copy =
            new EnumMap<>(GameRuntimeBackendType.class);
        for (GameRuntimeBackend backend : values) {
            if (backend == null || copy.put(backend.getType(), backend) != null) {
                throw new IllegalArgumentException("duplicate_or_null_runtime_backend");
            }
        }
        this.backends = Collections.unmodifiableMap(copy);
    }

    public static GameRuntimeBackendRegistry createDefault() {
        return new GameRuntimeBackendRegistry(java.util.Arrays.asList(
            new GlibcTermuxBoxBackend(), new RootfsProotBackend()));
    }

    public GameRuntimeBackend require(GameRuntimeBackendType type) {
        GameRuntimeBackend backend = backends.get(type);
        if (backend == null) throw new IllegalArgumentException("runtime_backend_unsupported");
        return backend;
    }

    public Map<GameRuntimeBackendType, GameRuntimeBackend> snapshot() { return backends; }
}
