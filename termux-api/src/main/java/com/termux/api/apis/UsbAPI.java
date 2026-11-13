package com.termux.api.apis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import com.termux.api.util.ResultReturner;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Standard CLI adapter for the existing same-UID, permission-aware USB broker. */
public final class UsbAPI {
    public static void onReceive(BroadcastReceiver receiver, Context context, Intent intent) {
        ResultReturner.returnData(receiver, intent, new ResultReturner.WithAncillaryFd() {
            @Override public void writeResult(PrintWriter out) throws Exception {
                try (LocalSocket socket = new LocalSocket()) {
                    socket.connect(new LocalSocketAddress("com.termux.usb.v1." + android.os.Process.myUid()));
                    if (socket.getPeerCredentials().getUid() != android.os.Process.myUid())
                        throw new java.io.IOException("USB broker UID mismatch");
                    socket.setSoTimeout(65000);
                    String device = intent.getStringExtra("device");
                    if (device == null && intent.hasExtra("vendorId") && intent.hasExtra("productId")) {
                        int vendor = Integer.decode(intent.getStringExtra("vendorId"));
                        int product = Integer.decode(intent.getStringExtra("productId"));
                        android.hardware.usb.UsbManager manager = (android.hardware.usb.UsbManager) context.getSystemService(Context.USB_SERVICE);
                        for (android.hardware.usb.UsbDevice candidate : manager.getDeviceList().values())
                            if (candidate.getVendorId() == vendor && candidate.getProductId() == product) { device = candidate.getDeviceName(); break; }
                        if (device == null) throw new java.io.IOException("No USB device with this vendor/product ID");
                    }
                    JSONObject request = new JSONObject().put("version", 1)
                        .put("action", intent.getAction() == null ? "list" : intent.getAction())
                        .put("request", intent.getBooleanExtra("request", false))
                        .put("device", device != null ? device : JSONObject.NULL);
                    socket.getOutputStream().write((request + "\n").getBytes(StandardCharsets.UTF_8));
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    FileDescriptor fd = null;
                    try {
                        int value;
                        while ((value = socket.getInputStream().read()) != -1) {
                            FileDescriptor[] incoming = socket.getAncillaryFileDescriptors();
                            if (incoming != null) for (FileDescriptor item : incoming) {
                                if (fd == null) fd = item; else Os.close(item);
                            }
                            if (value == '\n') break;
                            if (bytes.size() >= 65536) throw new java.io.IOException("USB response too large");
                            bytes.write(value);
                        }
                        JSONObject reply = new JSONObject(bytes.toString("UTF-8"));
                        if (reply.getInt("errno") != 0) throw new java.io.IOException(reply.optString("error"));
                        if ("open".equals(intent.getAction())) {
                            if (fd == null) throw new java.io.IOException("USB broker returned no descriptor");
                            try (ParcelFileDescriptor copy = ParcelFileDescriptor.dup(fd)) { sendFd(out, copy.detachFd()); }
                        } else if ("permission".equals(intent.getAction())) out.println("Permission granted.");
                        else out.println(reply.get("result"));
                    } finally { if (fd != null) Os.close(fd); }
                }
            }
        });
    }
}
