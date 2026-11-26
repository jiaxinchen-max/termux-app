package com.termux.ui.ide;

import org.junit.Test;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class IdeToolingRpcTest {
    private static byte[] frame(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(bytes);
        return out.toByteArray();
    }
    @Test public void framingUsesBytesAndLeavesNextFrameIntact() throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(frame("中文😀")); data.write(frame("second"));
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(data.toByteArray())) {
            @Override public int read(byte[] b, int o, int l) throws IOException { return super.read(b, o, Math.min(1, l)); }
        };
        assertEquals("中文😀", new String(IdeToolingRpc.readFrame(fragmented), StandardCharsets.UTF_8));
        assertEquals("second", new String(IdeToolingRpc.readFrame(fragmented), StandardCharsets.UTF_8));
    }
    @Test public void malformedOrOversizedFramesAreRejectedBeforeAllocation() throws Exception {
        for (String text : new String[] {"Content-Length: -1\r\n\r\n", "Content-Length: 16777217\r\n\r\n",
            "Content-Length: nope\r\n\r\n", "Other: a\r\n\r\n", "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
            "Content-Length: 4\r\n\r\nx"}) {
            try { IdeToolingRpc.readFrame(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII))); fail(text); }
            catch (IOException expected) { }
        }
    }
    @Test public void handlesServerCallbackWhileRequestIsPendingAndFailsOnDisconnect() throws Exception {
        PipedInputStream clientInput = new PipedInputStream();
        PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        PipedInputStream serverInput = new PipedInputStream();
        PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (IdeToolingRpc rpc = new IdeToolingRpc(clientInput, clientOutput, new IdeToolingRpc.Client() {
            public Object request(String method, Object params) { return new JSONArray().put("--offline"); }
            public void notification(String method, Object params) { }
        })) {
            Future<Object> response = executor.submit(() -> rpc.call("server/initialize", null, 5));
            JSONObject request = new JSONObject(new String(IdeToolingRpc.readFrame(serverInput), StandardCharsets.UTF_8));
            serverOutput.write(frame("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"client/getBuildArguments\"}")); serverOutput.flush();
            JSONObject callback = new JSONObject(new String(IdeToolingRpc.readFrame(serverInput), StandardCharsets.UTF_8));
            assertEquals(7, callback.getInt("id")); assertEquals("--offline", callback.getJSONArray("result").getString(0));
            serverOutput.write(frame(new JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", true).toString())); serverOutput.flush();
            assertEquals(true, response.get(5, TimeUnit.SECONDS));
            Future<Object> disconnected = executor.submit(() -> rpc.call("server/metadata", null, 5));
            IdeToolingRpc.readFrame(serverInput); serverOutput.close();
            try { disconnected.get(5, TimeUnit.SECONDS); fail(); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
        } finally { executor.shutdownNow(); serverOutput.close(); serverInput.close(); }
    }
    @Test public void aTimedOutRequestDoesNotPoisonNextRequest() throws Exception {
        PipedInputStream input = new PipedInputStream(); PipedOutputStream out = new PipedOutputStream(input);
        PipedInputStream requests = new PipedInputStream(); PipedOutputStream sender = new PipedOutputStream(requests);
        try (IdeToolingRpc rpc = new IdeToolingRpc(input, sender, new IdeToolingRpc.Client() {
            public Object request(String m, Object p) { return null; }
            public void notification(String m, Object p) { }
        })) {
            try { rpc.call("server/slow", null, 0); fail(); } catch (TimeoutException expected) { }
            IdeToolingRpc.readFrame(requests);
        } finally { out.close(); requests.close(); }
    }
}
