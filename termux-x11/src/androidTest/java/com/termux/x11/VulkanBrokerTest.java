package com.termux.x11;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Verifies the Bionic broker endpoint independently of a GLIBC Vulkan ICD. */
@RunWith(AndroidJUnit4.class)
public final class VulkanBrokerTest {
    private static final int MAGIC = 0x564b4252;
    private static final short PROTOCOL_VERSION = 1;
    private static final int HELLO_SIZE = 8;
    private static final int CAPABILITIES_SIZE = 288;
    private static final int CAPABILITY_ANDROID_HARDWARE_BUFFER = 1;

    @Test public void returnsVulkanCapabilitiesAfterVersionedHandshake() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File directory = new File(context.getCacheDir(), "vulkan-broker-test");
        File socket = new File(directory, "probe.sock");
        VulkanBroker broker = new VulkanBroker();
        assertTrue(broker.start(socket));
        assertTrue(broker.isRunning());

        try (LocalSocket client = new LocalSocket()) {
            client.connect(new LocalSocketAddress(socket.getAbsolutePath(),
                LocalSocketAddress.Namespace.FILESYSTEM));
            OutputStream output = client.getOutputStream();
            output.write(ByteBuffer.allocate(HELLO_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC)
                .putShort(PROTOCOL_VERSION)
                .putShort((short) HELLO_SIZE)
                .array());
            output.flush();

            byte[] response = readExactly(client.getInputStream(), CAPABILITIES_SIZE);
            ByteBuffer values = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(MAGIC, values.getInt());
            assertEquals(PROTOCOL_VERSION, values.getShort());
            assertEquals(CAPABILITIES_SIZE, Short.toUnsignedInt(values.getShort()));
            assertEquals(0, values.getInt());
            assertTrue("Vulkan API version was not reported", values.getInt() >= 0x00400000);
            values.getInt(); // vendor ID
            values.getInt(); // device ID
            values.getInt(); // device type
            assertTrue("Vulkan device cannot import Android Hardware Buffers",
                (values.getInt() & CAPABILITY_ANDROID_HARDWARE_BUFFER) != 0);
        } finally {
            broker.close();
        }
        assertTrue(!socket.exists());
    }

    @Test public void createsVortekContextAndTransfersSharedMemoryFds() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File directory = new File(context.getCacheDir(), "vortek-rpc-test");
        File socket = new File(directory, "V0");
        VulkanBroker broker = new VulkanBroker();
        assertTrue(broker.start(socket));

        try (LocalSocket client = new LocalSocket()) {
            client.connect(new LocalSocketAddress(socket.getAbsolutePath(),
                LocalSocketAddress.Namespace.FILESYSTEM));
            OutputStream output = client.getOutputStream();
            output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1) // REQUEST_CODE_CREATE_CONTEXT
                .putInt(0)
                .array());
            output.flush();

            assertEquals(0, client.getInputStream().read()); // SCM_RIGHTS payload byte
            FileDescriptor[] descriptors = client.getAncillaryFileDescriptors();
            assertTrue("Vortek did not return its two shared-memory rings",
                descriptors != null && descriptors.length == 2);
        } finally {
            broker.close();
        }
        assertTrue(!socket.exists());
    }

    private static byte[] readExactly(InputStream input, int length) throws Exception {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) throw new AssertionError("Broker closed before sending capabilities");
            offset += count;
        }
        return data;
    }
}
