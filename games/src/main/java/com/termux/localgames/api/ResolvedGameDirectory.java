package com.termux.localgames.api;

/** Host result for mapping a persisted SAF tree to a Wine-readable POSIX directory. */
public final class ResolvedGameDirectory {

    private final String path;
    private final String errorCode;

    private ResolvedGameDirectory(String path, String errorCode) {
        this.path = path == null ? "" : path;
        this.errorCode = errorCode == null ? "" : errorCode;
    }

    public static ResolvedGameDirectory resolved(String path) {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("path required");
        return new ResolvedGameDirectory(path, "");
    }

    public static ResolvedGameDirectory blocked(String errorCode) {
        if (errorCode == null || errorCode.isEmpty()) {
            throw new IllegalArgumentException("errorCode required");
        }
        return new ResolvedGameDirectory("", errorCode);
    }

    public boolean isResolved() { return errorCode.isEmpty(); }
    public String getPath() { return path; }
    public String getErrorCode() { return errorCode; }
}
