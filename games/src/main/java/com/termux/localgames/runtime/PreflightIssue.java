package com.termux.localgames.runtime;

public final class PreflightIssue {
    private final PreflightIssueCode code;
    private final String subject;

    public PreflightIssue(PreflightIssueCode code, String subject) {
        if (code == null) throw new IllegalArgumentException("code must not be null");
        this.code = code;
        this.subject = subject == null ? "" : subject;
    }

    public PreflightIssueCode getCode() { return code; }
    public String getSubject() { return subject; }
}
