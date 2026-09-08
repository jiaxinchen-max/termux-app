package com.termux.localgames.domain;

public final class RuntimeProfileChange {
    private final String field;
    private final String before;
    private final String after;

    public RuntimeProfileChange(String field, String before, String after) {
        this.field = field;
        this.before = before;
        this.after = after;
    }

    public String getField() { return field; }
    public String getBefore() { return before; }
    public String getAfter() { return after; }
}
