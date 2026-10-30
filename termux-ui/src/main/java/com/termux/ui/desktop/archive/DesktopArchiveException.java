package com.termux.ui.desktop.archive;

import java.io.IOException;

public final class DesktopArchiveException extends IOException {
    private final String code;
    public DesktopArchiveException(String code) { super(code); this.code = code; }
    public DesktopArchiveException(String code, Throwable cause) {
        super(code, cause); this.code = code;
    }
    public String getCode() { return code; }
}
