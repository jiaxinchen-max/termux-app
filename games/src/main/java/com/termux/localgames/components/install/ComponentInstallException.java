package com.termux.localgames.components.install;

import java.io.IOException;

public class ComponentInstallException extends IOException {
    private final String errorCode;

    public ComponentInstallException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ComponentInstallException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
