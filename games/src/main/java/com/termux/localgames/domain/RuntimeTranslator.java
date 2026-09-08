package com.termux.localgames.domain;

/** CPU/Windows translation engine installed inside one isolated game container. */
public enum RuntimeTranslator {
    HANGOVER("hangover"),
    BOX64("box64"),
    FEX("fex");

    private final String storageValue;

    RuntimeTranslator(String storageValue) {
        this.storageValue = storageValue;
    }

    public String getStorageValue() { return storageValue; }

    public static RuntimeTranslator fromStorageValue(String value) {
        for (RuntimeTranslator translator : values()) {
            if (translator.storageValue.equals(value)) return translator;
        }
        throw new IllegalArgumentException("invalid_runtime_translator");
    }
}
