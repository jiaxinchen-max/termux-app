package com.termux.localgames.components.install;

import java.io.File;
import java.io.IOException;

public interface ArchiveFileOperations {
    void createSymbolicLink(String target, File link) throws IOException;
    void createHardLink(File target, File link) throws IOException;
    void applyMode(File file, int mode) throws IOException;
    boolean isSymbolicLink(File file) throws IOException;
}
