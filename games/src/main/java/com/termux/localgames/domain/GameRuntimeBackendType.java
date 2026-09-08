package com.termux.localgames.domain;

/** Stable per-game runtime implementation identifier. */
public enum GameRuntimeBackendType {
    GLIBC_TERMUX_BOX("glibc_termux_box"),
    ROOTFS_PROOT("rootfs_proot");

    private final String storageValue;

    GameRuntimeBackendType(String storageValue) {
        this.storageValue = storageValue;
    }

    public String getStorageValue() { return storageValue; }

    public static GameRuntimeBackendType fromStorageValue(String value) {
        for (GameRuntimeBackendType type : values()) {
            if (type.storageValue.equals(value)) return type;
        }
        throw new IllegalArgumentException("unknown_runtime_backend:" + value);
    }
}
