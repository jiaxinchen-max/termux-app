package com.termux.ui.desktop.runtime;

/** Installation owns the desktop through host admission and the real terminal's lifetime. */
public final class DesktopInstallState {
    private DesktopInstallState() { }
    public static String readTask(java.io.File file) throws java.io.IOException {
        if (!file.exists()) return null;
        byte[] bytes = new byte[129];
        int count = 0;
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            int read;
            while (count < bytes.length && (read = input.read(bytes, count, bytes.length - count)) != -1)
                count += read;
        }
        String id = new String(bytes, 0, count, java.nio.charset.StandardCharsets.US_ASCII);
        if (!id.startsWith("desktop-install-") || !id.matches("[A-Za-z0-9._-]{1,128}"))
            throw new java.io.IOException("desktop_install_identity_invalid");
        return id;
    }

    public static void writeTask(java.io.File file, String id) throws java.io.IOException {
        if (!id.startsWith("desktop-install-") || !id.matches("[A-Za-z0-9._-]{1,128}"))
            throw new java.io.IOException("desktop_install_identity_invalid");
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
            throw new java.io.IOException("desktop_install_identity_directory_failed");
        java.io.File temporary = new java.io.File(file.getPath() + ".tmp");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {
            output.write(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
        if (!temporary.renameTo(file)) throw new java.io.IOException("desktop_install_identity_publish_failed");
    }

    public static boolean isActive(boolean terminalPresent, boolean terminalRunning, boolean submissionPending) {
        return terminalPresent ? terminalRunning : submissionPending;
    }
}
