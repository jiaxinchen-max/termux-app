package com.termux.ui.api;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;

import com.termux.ui.domain.DesktopDe;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopRunMode;
import com.termux.ui.domain.DesktopUsbStorageMode;
import com.termux.usb.EmbeddedUsbApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Same-UID control of the real, read-only coordinator owned by one desktop session. */
public final class DesktopUsbStorageTasks {
    public enum ApiState { READY, UNAVAILABLE }
    public static final class Dependencies {
        public final ApiState api;
        public final List<String> missing;
        Dependencies(ApiState api, List<String> missing) {
            this.api = api; this.missing = Collections.unmodifiableList(missing);
        }
        public boolean ready() { return api == ApiState.READY && missing.isEmpty(); }
    }
    public static final class Device {
        public final String path, name, status, error;
        Device(String path, String name, String status, String error) {
            this.path = path; this.name = name; this.status = status; this.error = error;
        }
    }
    public static final class Health {
        public final String enumerationError;
        public final List<Device> devices;
        Health(String enumerationError, List<Device> devices) {
            this.enumerationError = enumerationError;
            this.devices = Collections.unmodifiableList(devices);
        }
    }
    private final Context context;
    private final File prefix;
    public DesktopUsbStorageTasks(Context context) {
        this.context = context.getApplicationContext();
        prefix = new File(this.context.getFilesDir(), "usr");
    }
    public static boolean supported(DesktopDistro distro) {
        return distro.getDe() == DesktopDe.GNOME || distro.getDe() == DesktopDe.XFCE4;
    }
    public File runtime(DesktopDistro distro) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(distro.getId().getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < 8; i++) key.append(String.format(java.util.Locale.ROOT, "%02x", hash[i] & 255));
            return new File(prefix, "tmp/tusb-" + key + "/desktop");
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
    }
    public Dependencies dependencies(DesktopDistro distro) {
        String clientError = EmbeddedUsbApi.prepareClient(context);
        ApiState api = EmbeddedUsbApi.isReady() && clientError == null ? ApiState.READY : ApiState.UNAVAILABLE;
        List<String> missing = new ArrayList<>();
        if (clientError != null) missing.add("embedded-usb: " + clientError);
        for (String relative : new String[] {"libexec/termux-usb-app", "bin/termux-usb-bridge", "bin/tusb-desktop",
            "bin/tusb-files", "bin/tusb-inspect", "bin/tusb-gio-mount", "bin/python3",
            "lib/gio/modules/libtusb-volume-monitor.so"}) {
            if (!new File(prefix, relative).isFile()) missing.add(relative);
        }
        if (distro.getRunMode() == DesktopRunMode.PROOT) {
            File root = new File(prefix, "var/lib/proot-distro/containers/" + distro.getProotDistro() + "/rootfs");
            for (String relative : new String[] {"usr/local/libexec/tusb-gio-mount",
                "usr/local/lib/termux-usb-bridge/gio/modules/libtusb-volume-monitor.so"}) {
                if (!new File(root, relative).isFile()) missing.add("container:/" + relative);
            }
        }
        return new Dependencies(api, missing);
    }
    public void setEnabled(DesktopDistro distro, boolean enabled) throws IOException {
        if (enabled && !supported(distro)) throw new IOException("desktop_usb_environment_unsupported");
        new DesktopTasks(context).save(distro.withUsbStorage(enabled
            ? DesktopUsbStorageMode.READ_ONLY : DesktopUsbStorageMode.OFF));
    }
    public Health health(DesktopDistro distro) throws IOException {
        try {
            JSONObject value = new JSONObject(request(distro, "health", ""));
            if (value.getInt("schemaVersion") != 1 || !"read-only".equals(value.getString("storageMode"))
                || !distro.getRunMode().getStorageValue().equals(value.getString("runMode"))) {
                throw new IOException("desktop_usb_protocol_mismatch");
            }
            JSONArray entries = value.getJSONArray("devices");
            if (entries.length() > 256) throw new IOException("desktop_usb_device_limit");
            List<Device> devices = new ArrayList<>();
            for (int index = 0; index < entries.length(); index++) {
                JSONObject device = entries.getJSONObject(index);
                JSONObject capabilities = device.optJSONObject("capabilities");
                if (capabilities != null && !capabilities.optBoolean("storage", false)) continue;
                String path = device.getString("path");
                requireDevice(path);
                JSONObject info = device.optJSONObject("info");
                String label = info == null ? path : info.optString("label", path);
                devices.add(new Device(path, label.isEmpty() ? path : label,
                    device.getString("status"), device.optString("error", "")));
            }
            return new Health(value.isNull("enumerationError") ? null : value.getString("enumerationError"), devices);
        } catch (org.json.JSONException error) { throw new IOException("desktop_usb_invalid_response", error); }
    }
    public String diagnostics(DesktopDistro distro) {
        try {
            return com.termux.ui.runtime.DesktopEnvironment.tail(new File(runtime(distro), "last-error.txt"), 4096).trim();
        } catch (IOException error) { return error.getMessage(); }
    }
    public void action(DesktopDistro distro, String action, String path) throws IOException {
        synchronized (DesktopOperationGate.LOCK) {
            DesktopTasks.requireNotStopping(new DesktopTasks(context).state(distro));
        }
        if (!java.util.Arrays.asList("authorize", "mount", "unmount", "eject").contains(action))
            throw new IOException("desktop_usb_invalid_action");
        requireDevice(path);
        request(distro, action, path);
    }
    private static void requireDevice(String path) throws IOException {
        if (!path.matches("/dev/bus/usb/[0-9]{3}/[0-9]{3}")) throw new IOException("desktop_usb_invalid_device");
    }
    private String request(DesktopDistro distro, String command, String key) throws IOException {
        if (!supported(distro) || distro.getUsbStorage() != DesktopUsbStorageMode.READ_ONLY)
            throw new IOException("desktop_usb_disabled");
        File control = new File(runtime(distro), "control.sock");
        if (!control.exists()) throw new IOException("desktop_usb_coordinator_not_running");
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(control.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            // Authorization (90s), inspection (30s), file service (45s) and GVfs
            // mount (40s) can occur sequentially in one real-device operation.
            socket.setSoTimeout("health".equals(command) ? 5000 : 225000);
            if (socket.getPeerCredentials().getUid() != Process.myUid())
                throw new IOException("desktop_usb_unexpected_peer");
            socket.getOutputStream().write((command + " " + key + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            while (true) {
                int b = input.read();
                if (b < 0) throw new IOException("desktop_usb_response_truncated");
                if (b == '\n') break;
                if (response.size() >= 262144) throw new IOException("desktop_usb_response_limit");
                response.write(b);
            }
            String line = new String(response.toByteArray(), StandardCharsets.UTF_8);
            if (line.equals("OK")) return "";
            if (line.startsWith("OK ")) return line.substring(3);
            if (line.startsWith("ERR ")) throw new IOException(line.substring(4));
            throw new IOException("desktop_usb_invalid_response");
        }
    }
}
