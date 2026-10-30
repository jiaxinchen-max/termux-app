package com.termux.usb;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** App-owned USB enumeration, foreground authorization and same-UID FD transfer. */
public final class EmbeddedUsbApi implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "EmbeddedUsbApi";
    private static final int MAX_REQUEST = 4096;
    private static volatile EmbeddedUsbApi instance;
    private final Application application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "termux-usb-client");
            thread.setDaemon(true);
            return thread;
        });
    private volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    private volatile LocalServerSocket server;
    private volatile String error;

    private EmbeddedUsbApi(Application application) {
        this.application = application;
        application.registerActivityLifecycleCallbacks(this);
        try {
            server = new LocalServerSocket("com.termux.usb.v1." + Process.myUid());
            Thread listener = new Thread(this::acceptClients, "termux-usb-listener");
            listener.setDaemon(true);
            listener.start();
        } catch (IOException failure) {
            error = failure.toString();
            Log.e(TAG, "Cannot start embedded USB broker", failure);
        }
        prepareClient(application);
    }

    public static synchronized void initialize(Application application) {
        // The restart helper has its own process and must not compete for the main broker socket.
        if (!isMainProcess(application)) return;
        if (instance == null) instance = new EmbeddedUsbApi(application);
    }

    private static boolean isMainProcess(Application application) {
        if (Build.VERSION.SDK_INT >= 28) return application.getPackageName().equals(Application.getProcessName());
        try (InputStream input = new FileInputStream("/proc/self/cmdline")) {
            ByteArrayOutputStream name = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) > 0 && name.size() < 256) name.write(value);
            return application.getPackageName().equals(new String(name.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            Log.e(TAG, "Cannot identify USB broker process", failure);
            return false;
        }
    }

    public static boolean isReady() {
        EmbeddedUsbApi current = instance;
        return current != null && current.server != null && current.error == null;
    }

    public static File clientFile(Context context) {
        return new File(context.getFilesDir(), "usr/libexec/termux-usb-app");
    }

    /** Never creates a partial prefix before bootstrap installation. */
    public static synchronized String prepareClient(Context context) {
        File prefix = new File(context.getFilesDir(), "usr");
        if (!new File(prefix, "bin/sh").isFile()) return "Termux bootstrap is not installed";
        File temporary = null;
        try {
            byte[] template;
            try (InputStream asset = context.getAssets().open("termux-usb/termux-usb-app.py")) {
                template = readAll(asset, 65536);
            }
            String python = new File(prefix, "bin/python3").getAbsolutePath();
            byte[] content = new String(template, StandardCharsets.UTF_8)
                .replace("@PYTHON@", python).getBytes(StandardCharsets.UTF_8);
            File target = clientFile(context);
            if (target.isFile()) {
                try (InputStream old = new FileInputStream(target)) {
                    if (Arrays.equals(content, readAll(old, 65536))) {
                        Os.chmod(target.getAbsolutePath(), 0700);
                        return null;
                    }
                }
            }
            File directory = target.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create USB client directory");
            temporary = File.createTempFile(".termux-usb-app-", ".tmp", directory);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(content);
                output.getFD().sync();
            }
            Os.chmod(temporary.getAbsolutePath(), 0700);
            Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            return null;
        } catch (Exception failure) {
            Log.e(TAG, "Cannot install embedded USB client", failure);
            return failure.toString();
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }

    private static byte[] readAll(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("USB data exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void acceptClients() {
        LocalServerSocket listener = server;
        try {
            while (true) {
                LocalSocket client = listener.accept();
                try {
                    if (client.getPeerCredentials().getUid() != Process.myUid()) {
                        client.close();
                        continue;
                    }
                    workers.execute(() -> serve(client));
                } catch (Exception failure) {
                    client.close();
                    Log.w(TAG, "USB client rejected", failure);
                }
            }
        } catch (IOException failure) {
            error = failure.toString();
            server = null;
            Log.e(TAG, "Embedded USB listener stopped", failure);
        } finally {
            try { listener.close(); } catch (IOException failure) { Log.w(TAG, "Closing USB listener", failure); }
            workers.shutdown();
        }
    }

    private void serve(LocalSocket client) {
        try (LocalSocket connection = client) {
            connection.setSoTimeout(5000);
            try {
                JSONObject request = readRequest(connection);
                if (!Integer.valueOf(1).equals(request.opt("version")) || !(request.opt("request") instanceof Boolean))
                    throw new UsbFailure(71, "Invalid embedded USB request");
                String action = request.getString("action");
                boolean authorize = request.getBoolean("request");
                UsbManager manager = (UsbManager) application.getSystemService(Context.USB_SERVICE);
                if (manager == null) throw new UsbFailure(38, "Android USB service unavailable");
                if ("list".equals(action)) {
                    if (authorize || !request.isNull("device")) throw new UsbFailure(22, "List cannot request permission");
                    ArrayList<String> paths = new ArrayList<>(manager.getDeviceList().keySet());
                    Collections.sort(paths);
                    if (paths.size() > 256) throw new UsbFailure(75, "Too many USB devices");
                    for (String path : paths) requireDevice(path);
                    reply(connection, new JSONArray(paths), null);
                    return;
                }
                if (!"permission".equals(action) && !"open".equals(action))
                    throw new UsbFailure(22, "Unknown embedded USB action");
                String path = request.getString("device");
                requireDevice(path);
                UsbDevice device = manager.getDeviceList().get(path);
                if (device == null) throw new UsbFailure(19, "No such USB device");
                if (!manager.hasPermission(device)) {
                    if (!authorize) throw new UsbFailure(13, "Android USB permission required");
                    requestPermission(manager, device);
                }
                if ("permission".equals(action)) {
                    reply(connection, true, null);
                } else {
                    // Only the receiver's duplicate survives this response. Java owns and closes
                    // both its UsbDeviceConnection and its temporary duplicate after sendmsg.
                    UsbDeviceConnection deviceConnection = manager.openDevice(device);
                    if (deviceConnection == null) throw new UsbFailure(5, "Opening USB device failed");
                    try {
                        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.fromFd(deviceConnection.getFileDescriptor())) {
                            reply(connection, true, descriptor.getFileDescriptor());
                        }
                    } finally { deviceConnection.close(); }
                }
            } catch (UsbFailure failure) {
                sendError(connection, failure.errno, failure.getMessage());
            } catch (JSONException | IllegalArgumentException failure) {
                sendError(connection, 71, "Invalid embedded USB request: " + failure.getMessage());
            } catch (SecurityException failure) {
                sendError(connection, 13, failure.toString());
            } catch (Exception failure) {
                sendError(connection, 5, failure.toString());
            }
        } catch (IOException failure) {
            Log.w(TAG, "Embedded USB connection failed", failure);
        }
    }

    private JSONObject readRequest(LocalSocket client) throws IOException, JSONException, UsbFailure {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream input = client.getInputStream();
        int value;
        while ((value = input.read()) != '\n') {
            rejectIncomingDescriptors(client);
            if (value == -1) throw new UsbFailure(71, "Truncated embedded USB request");
            if (output.size() >= MAX_REQUEST) throw new UsbFailure(90, "Embedded USB request too large");
            output.write(value);
        }
        rejectIncomingDescriptors(client);
        return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    private void rejectIncomingDescriptors(LocalSocket client) throws IOException, UsbFailure {
        FileDescriptor[] descriptors = client.getAncillaryFileDescriptors();
        if (descriptors == null || descriptors.length == 0) return;
        for (FileDescriptor descriptor : descriptors) {
            try { Os.close(descriptor); }
            catch (android.system.ErrnoException failure) { Log.w(TAG, "Closing unexpected USB request FD", failure); }
        }
        throw new UsbFailure(71, "Embedded USB requests cannot contain file descriptors");
    }

    private void reply(LocalSocket client, Object result, FileDescriptor descriptor) throws IOException, JSONException {
        JSONObject response = new JSONObject().put("version", 1).put("errno", 0).put("result", result);
        if (descriptor != null) client.setFileDescriptorsForSend(new FileDescriptor[] {descriptor});
        try {
            client.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
        } finally {
            if (descriptor != null) client.setFileDescriptorsForSend(null);
        }
    }

    private void sendError(LocalSocket client, int errno, String message) throws IOException {
        try {
            JSONObject response = new JSONObject().put("version", 1).put("errno", errno).put("error", message);
            client.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
        } catch (JSONException failure) { throw new IOException(failure); }
    }

    private void requestPermission(UsbManager manager, UsbDevice device) throws UsbFailure, InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<UsbFailure> failure = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean registered = new AtomicBoolean();
        AtomicReference<PendingIntent> callbackIntent = new AtomicReference<>();
        String action = application.getPackageName() + ".usb.PERMISSION." + UUID.randomUUID();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                UsbDevice returned = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (!action.equals(intent.getAction()) || returned == null || !device.equals(returned)) return;
                if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || !manager.hasPermission(device))
                    failure.set(new UsbFailure(13, "Android USB permission denied"));
                completed.countDown();
            }
        };
        main.post(() -> {
            if (cancelled.get()) return;
            Activity activity = resumed.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                failure.set(new UsbFailure(13, "Keep the App visible to request Android USB permission"));
                completed.countDown();
                return;
            }
            try {
                if (Build.VERSION.SDK_INT >= 33)
                    application.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
                else application.registerReceiver(receiver, new IntentFilter(action));
                registered.set(true);
                int flags = PendingIntent.FLAG_CANCEL_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent callback = PendingIntent.getBroadcast(application, 0,
                    new Intent(action).setPackage(application.getPackageName()), flags);
                callbackIntent.set(callback);
                manager.requestPermission(device, callback);
            } catch (Exception error) {
                failure.set(new UsbFailure(13, "Cannot request Android USB permission: " + error));
                completed.countDown();
            }
        });
        try {
            if (!completed.await(30, TimeUnit.SECONDS)) throw new UsbFailure(110, "Android USB permission request timed out");
            if (failure.get() != null) throw failure.get();
        } finally {
            cancelled.set(true);
            main.post(() -> {
                if (registered.get()) application.unregisterReceiver(receiver);
                PendingIntent callback = callbackIntent.get();
                if (callback != null) callback.cancel();
            });
        }
    }

    private static void requireDevice(String path) throws UsbFailure {
        if (path == null || !path.matches("/dev/bus/usb/[0-9]{3}/[0-9]{3}"))
            throw new UsbFailure(22, "Invalid USB device path");
        int bus = Integer.parseInt(path.substring(13, 16));
        int device = Integer.parseInt(path.substring(17, 20));
        if (bus < 1 || bus > 255 || device < 1 || device > 127) throw new UsbFailure(22, "Invalid USB device path");
    }

    private static final class UsbFailure extends Exception {
        final int errno;
        UsbFailure(int errno, String message) { super(message); this.errno = errno; }
    }

    @Override public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        prepareClient(application);
    }
    @Override public void onActivityPaused(Activity activity) {
        if (resumed.get() == activity) resumed = new WeakReference<>(null);
    }
    @Override public void onActivityDestroyed(Activity activity) {
        if (resumed.get() == activity) resumed = new WeakReference<>(null);
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
