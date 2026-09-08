package com.termux.localgames.components;

import java.io.IOException;

public final class ComponentDownloadException extends IOException {

    private final String errorCode;

    public ComponentDownloadException(String errorCode, String message) {
        super(message);
        this.errorCode = requireCode(errorCode);
    }

    public ComponentDownloadException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = requireCode(errorCode);
    }

    public String getErrorCode() {
        return errorCode;
    }

    private static String requireCode(String errorCode) {
        if (errorCode == null || errorCode.trim().isEmpty()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        return errorCode;
    }
}
