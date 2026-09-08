package com.termux.localgames.runtime;

public final class OrchestrationException extends Exception {

    private final String errorCode;
    private final boolean recoverable;

    public OrchestrationException(String errorCode, String message,
                                  boolean recoverable, Throwable cause) {
        super(message, cause);
        if (errorCode == null || errorCode.trim().isEmpty()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        this.errorCode = errorCode;
        this.recoverable = recoverable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}
