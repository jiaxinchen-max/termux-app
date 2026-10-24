package com.termux.ui.api;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.XServerBackendType;
import com.termux.ui.runtime.XServerBackendSelection;
import com.termux.ui.runtime.XServerPackageManifest;
import com.termux.ui.service.LaunchScriptInstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Command surface for choosing and installing the X server that game launches use. The legacy
 * in-process X server and the independent Xlorie package are both always available to the launch
 * scripts; this only records the choice and installs the Xlorie packages on demand.
 */
public final class XServerTasks {

    private static final String MANIFEST_ASSET = "local-games/x-server-packages.txt";
    private static final String BASE_URL =
        "https://github.com/jiaxinchen-max/xserver/releases/download/termux-xlorie-21.1.16-r5";

    private final Context context;
    private final GameStoragePaths paths;

    public XServerTasks(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.paths = new GameStoragePaths(this.context.getFilesDir());
    }

    @NonNull public XServerBackendType selected() { return XServerBackendSelection.read(paths); }

    public void select(@NonNull XServerBackendType type) throws IOException {
        XServerBackendSelection.write(paths, type);
    }

    /** Whether packages for this device's ABI are published; the legacy server always is. */
    public boolean isSupported(@NonNull XServerBackendType type) {
        if (type == XServerBackendType.LEGACY_IN_PROCESS) return true;
        try {
            return loadManifest().isSupported(primaryAbi());
        } catch (IOException error) {
            return false;
        }
    }

    /** Whether the selected implementation's executable is present in the Termux prefix. */
    public boolean isInstalled(@NonNull XServerBackendType type) {
        File bin = new File(context.getFilesDir(), "usr/bin");
        File executable = new File(bin, type == XServerBackendType.XLORIE_EXTERNAL
            ? "Xlorie" : "termux-x11");
        return executable.isFile() && executable.canExecute();
    }

    /** Runs the Xlorie package installer in a visible Termux terminal; returns the task id. */
    @NonNull
    public String installXlorie() throws LaunchHostException {
        String arch = XServerPackageManifest.termuxArchFor(primaryAbi());
        if (arch == null || !isSupported(XServerBackendType.XLORIE_EXTERNAL)) {
            throw new LaunchHostException("x_server_abi_unsupported", false, null);
        }
        String taskId = "xlorie-install-" + System.currentTimeMillis();
        try {
            File script = new LaunchScriptInstaller(context, paths).installXlorieInstaller();
            File runtime = paths.getRuntimeDirectory();
            File spec = new File(runtime, "x-server-install.spec");
            String content = "XSERVER_ARCH=" + arch + "\n"
                + "XSERVER_BASE_URL=" + BASE_URL + "\n"
                + "XSERVER_MANIFEST=" + new File(runtime, "x-server-packages.txt").getPath() + "\n"
                + "XSERVER_LOG_FILE=" + new File(runtime, "x-server-install.log").getPath() + "\n";
            File temporary = new File(runtime, spec.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (!temporary.renameTo(spec)) throw new IOException("x_server_spec_publish_failed");
            LocalGames.requireHost(context).startTermuxPackageInstall(new RuntimeSetupRequest(
                taskId, script.getPath(), spec.getPath(), runtime.getPath()));
        } catch (IOException | RuntimeException error) {
            throw new LaunchHostException("x_server_prepare_failed", true, error);
        }
        return taskId;
    }

    @Nullable
    public TerminalSession installTerminal(@NonNull String taskId) {
        return LocalGames.requireHost(context).getRuntimeSetupTerminal(taskId);
    }

    private XServerPackageManifest loadManifest() throws IOException {
        try (InputStream input = context.getAssets().open(MANIFEST_ASSET)) {
            return XServerPackageManifest.parse(input);
        }
    }

    private static String primaryAbi() {
        return Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0];
    }
}
