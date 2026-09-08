package com.termux.localgames.runtime;

/** Runtime trees that can be exported independently without game content. */
public enum RuntimeBackupType {
    GLIBC("glibc"),
    ROOTFS_PROOT("rootfs-proot");

    private final String storageValue;

    RuntimeBackupType(String storageValue) {
        this.storageValue = storageValue;
    }

    public String getStorageValue() { return storageValue; }

    public static RuntimeBackupType fromStorageValue(String value) {
        for (RuntimeBackupType type : values()) {
            if (type.storageValue.equals(value)) return type;
        }
        throw new IllegalArgumentException("runtime_backup_type_invalid");
    }
}
