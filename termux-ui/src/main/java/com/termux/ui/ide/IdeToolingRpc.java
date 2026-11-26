package com.termux.ui.ide;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded stdio JSON-RPC transport for the original AndroidIDE tooling server. */
public final class IdeToolingRpc implements Closeable {
    static final int MAX_FRAME = 16 * 1024 * 1024;
    public interface Client {
        Object request(String method, Object params) throws Exception;
        void notification(String method, Object params);
    }
    private final InputStream input;
    private final OutputStream output;
    private final Client client;
    private final Map<String, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Thread reader;
    private volatile boolean closed;

    public IdeToolingRpc(InputStream input, OutputStream output, Client client) {
        this.input = input; this.output = output; this.client = client;
        reader = new Thread(this::readLoop, "ide-tooling-rpc");
        reader.setDaemon(true); reader.start();
    }
    public Object call(String method, Object params, long timeoutSeconds) throws Exception {
        String id = Long.toString(sequence.incrementAndGet());
        CompletableFuture<Object> result = new CompletableFuture<>();
        if (closed) throw new EOFException("Tooling connection closed");
        pending.put(id, result);
        try {
            send(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
                .put("params", params == null ? JSONObject.NULL : params));
            return result.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Tooling request failed", cause);
        } finally { pending.remove(id); }
    }
    private synchronized void send(JSONObject message) throws IOException {
        if (closed) throw new EOFException("Tooling connection closed");
        byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) throw new IOException("Tooling message too large");
        output.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes); output.flush();
    }
    private void readLoop() {
        Exception failure = new EOFException("Tooling server disconnected");
        try {
            while (!closed) {
                JSONObject message = new JSONObject(new String(readFrame(input), StandardCharsets.UTF_8));
                if (message.has("method")) {
                    String method = message.getString("method"); Object params = message.opt("params");
                    if (!message.has("id")) client.notification(method, params);
                    else {
                        JSONObject reply = new JSONObject().put("jsonrpc", "2.0").put("id", message.get("id"));
                        try { reply.put("result", nullToJson(client.request(method, params))); }
                        catch (Exception e) { reply.put("error", new JSONObject().put("code", -32601).put("message", e.toString())); }
                        send(reply);
                    }
                } else {
                    CompletableFuture<Object> result = pending.get(message.optString("id"));
                    if (result != null) {
                        if (message.has("error")) result.completeExceptionally(new IOException(message.get("error").toString()));
                        else result.complete(message.opt("result"));
                    }
                }
            }
        } catch (Exception e) { failure = e; }
        finally { closed = true; for (CompletableFuture<Object> result : pending.values()) result.completeExceptionally(failure); }
    }
    private static Object nullToJson(Object value) { return value == null ? JSONObject.NULL : value; }
    static byte[] readFrame(InputStream in) throws IOException {
        int size = -1, headerBytes = 0;
        while (true) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int ch;
            while ((ch = in.read()) != '\n') {
                if (ch == -1) throw new EOFException("Incomplete tooling header");
                if (++headerBytes > 8192) throw new IOException("Tooling header too large");
                line.write(ch);
            }
            if (++headerBytes > 8192) throw new IOException("Tooling header too large");
            String text = new String(line.toByteArray(), StandardCharsets.US_ASCII).trim();
            if (text.isEmpty()) break;
            int colon = text.indexOf(':');
            if (colon <= 0) throw new IOException("Malformed tooling header");
            if (text.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                if (size != -1) throw new IOException("Duplicate Content-Length");
                try { size = Integer.parseInt(text.substring(colon + 1).trim()); }
                catch (NumberFormatException e) { throw new IOException("Invalid Content-Length", e); }
                if (size < 0 || size > MAX_FRAME) throw new IOException("Tooling frame exceeds limit");
            }
        }
        if (size < 0) throw new IOException("Missing Content-Length");
        byte[] bytes = new byte[size]; int offset = 0, n;
        while (offset < size) {
            n = in.read(bytes, offset, size - offset);
            if (n < 0) throw new EOFException("Incomplete tooling message");
            if (n == 0) continue;
            offset += n;
        }
        return bytes;
    }
    @Override public void close() throws IOException {
        closed = true;
        for (CompletableFuture<Object> result : pending.values()) result.completeExceptionally(new EOFException("Tooling connection closed"));
        try { output.close(); } finally { input.close(); }
    }
}
