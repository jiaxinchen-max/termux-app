package com.termux.localgames.components.index;

/** Product-facing component slot compatible with the GameHub PC engine model. */
public enum ComponentType {
    IMAGE_FS("image_fs"),
    CONTAINER("container"),
    GPU_DRIVER("gpu_driver"),
    DX_WRAPPER("dx_wrapper"),
    TRANSLATOR("translator"),
    GENERAL_COMPONENT("general_component"),
    RUNTIME_SUPPORT("runtime_support");

    private final String storageValue;

    ComponentType(String storageValue) {
        this.storageValue = storageValue;
    }

    public String getStorageValue() { return storageValue; }

    public static ComponentType fromStorageValue(String value) {
        for (ComponentType type : values()) {
            if (type.storageValue.equals(value)) return type;
        }
        throw new IllegalArgumentException("unknown component type: " + value);
    }
}
