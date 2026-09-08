package com.termux.localgames.api;

/** Stable app-host submission/termination failure exposed to Games orchestration. */
public final class LaunchHostException extends Exception {

    private final String errorCode;
    private final boolean recoverable;

    public LaunchHostException(String errorCode, boolean recoverable, Throwable cause) {
        super(errorCode, cause);
        if (errorCode == null || !errorCode.matches("[a-z0-9_]{1,128}")) {
            throw new IllegalArgumentException("invalid launch host errorCode");
        }
        this.errorCode = errorCode;
        this.recoverable = recoverable;
    }

    public String getErrorCode() { return errorCode; }
    public boolean isRecoverable() { return recoverable; }
}
