package com.termux.localgames.components;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

public class ComponentDownloaderTest {

    private static final byte[] ORIGINAL = bytes("abcdef");
    private static final byte[] REPLACEMENT = bytes("UVWXYZ");

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void freshDownloadPublishesOnlyVerifiedArtifact() throws Exception {
        Fixture fixture = fixture(ORIGINAL);
        FakeConnection response = response(200, ORIGINAL)
            .header("ETag", "\"v1\"");
        fixture.connections.add(response);

        ComponentDownloadResult result = fixture.downloader.download(
            fixture.task, DownloadControl.CONTINUE, DownloadProgressListener.NONE);

        assertEquals(ComponentTaskState.VERIFIED, result.getTask().getState());
        assertArrayEquals(ORIGINAL, read(result.getArtifact()));
        assertFalse(fixture.partial().exists());
        assertNull(response.requestHeader("Range"));
    }

    @Test
    public void resumesOnlyFromMatchingContentRange() throws Exception {
        Fixture fixture = fixture(ORIGINAL);
        write(fixture.partial(), bytes("abc"));
        fixture.task = fixture.task.transition(ComponentTaskState.DOWNLOADING, 3,
            "\"v1\"", "Wed, 27 Aug 2026 00:00:00 GMT", "", "");
        FakeConnection response = response(206, bytes("def"))
            .header("Content-Range", "bytes 3-5/6")
            .header("ETag", "\"v1\"");
        fixture.connections.add(response);

        ComponentDownloadResult result = fixture.downloader.download(
            fixture.task, DownloadControl.CONTINUE, DownloadProgressListener.NONE);

        assertArrayEquals(ORIGINAL, read(result.getArtifact()));
        assertEquals("bytes=3-", response.requestHeader("Range"));
        assertEquals("\"v1\"", response.requestHeader("If-Range"));
    }

    @Test
    public void weakEtagUsesLastModifiedForIfRange() throws Exception {
        Fixture fixture = fixture(ORIGINAL);
        String lastModified = "Wed, 27 Aug 2026 00:00:00 GMT";
        write(fixture.partial(), bytes("abc"));
        fixture.task = fixture.task.transition(ComponentTaskState.DOWNLOADING, 3,
            "W/\"v1\"", lastModified, "", "");
        FakeConnection response = response(206, bytes("def"))
            .header("Content-Range", "bytes 3-5/6")
            .header("ETag", "W/\"v1\"")
            .header("Last-Modified", lastModified);
        fixture.connections.add(response);

        fixture.downloader.download(fixture.task, DownloadControl.CONTINUE,
            DownloadProgressListener.NONE);

        assertEquals(lastModified, response.requestHeader("If-Range"));
    }

    @Test
    public void rangeIgnoredByServerOverwritesPartial() throws Exception {
        Fixture fixture = fixture(REPLACEMENT);
        write(fixture.partial(), bytes("abc"));
        fixture.task = fixture.task.transition(ComponentTaskState.DOWNLOADING, 3,
            "\"v1\"", "", "", "");
        fixture.connections.add(response(200, REPLACEMENT).header("ETag", "\"v2\""));

        ComponentDownloadResult result = fixture.downloader.download(
            fixture.task, DownloadControl.CONTINUE, DownloadProgressListener.NONE);

        assertArrayEquals(REPLACEMENT, read(result.getArtifact()));
    }

    @Test
    public void changedEtagRestartsWithSecondFullRequest() throws Exception {
        Fixture fixture = fixture(REPLACEMENT);
        write(fixture.partial(), bytes("abc"));
        fixture.task = fixture.task.transition(ComponentTaskState.DOWNLOADING, 3,
            "\"v1\"", "", "", "");
        FakeConnection staleRange = response(206, bytes("def"))
            .header("Content-Range", "bytes 3-5/6")
            .header("ETag", "\"v2\"");
        FakeConnection full = response(200, REPLACEMENT).header("ETag", "\"v2\"");
        fixture.connections.add(staleRange);
        fixture.connections.add(full);

        ComponentDownloadResult result = fixture.downloader.download(
            fixture.task, DownloadControl.CONTINUE, DownloadProgressListener.NONE);

        assertArrayEquals(REPLACEMENT, read(result.getArtifact()));
        assertEquals("bytes=3-", staleRange.requestHeader("Range"));
        assertNull(full.requestHeader("Range"));
        assertTrue(staleRange.disconnected);
    }

    @Test
    public void invalidContentRangeFailsWithoutAppending() throws Exception {
        Fixture fixture = fixture(ORIGINAL);
        write(fixture.partial(), bytes("abc"));
        fixture.task = fixture.task.transition(ComponentTaskState.DOWNLOADING, 3,
            "\"v1\"", "", "", "");
        fixture.connections.add(response(206, bytes("def"))
            .header("Content-Range", "bytes 2-4/6")
            .header("ETag", "\"v1\""));

        try {
            fixture.downloader.download(fixture.task, DownloadControl.CONTINUE,
                DownloadProgressListener.NONE);
            fail("invalid Content-Range must fail");
        } catch (ComponentDownloadException expected) {
            assertEquals("invalid_content_range", expected.getErrorCode());
        }

        assertArrayEquals(bytes("abc"), read(fixture.partial()));
        assertEquals(ComponentTaskState.FAILED,
            fixture.repository.find("task-1").get().getState());
    }

    @Test
    public void shaMismatchNeverPublishesArtifact() throws Exception {
        Fixture fixture = fixture(REPLACEMENT);
        fixture.connections.add(response(200, ORIGINAL));

        try {
            fixture.downloader.download(fixture.task, DownloadControl.CONTINUE,
                DownloadProgressListener.NONE);
            fail("SHA-256 mismatch must fail");
        } catch (ComponentDownloadException expected) {
            assertEquals("sha256_mismatch", expected.getErrorCode());
        }

        assertFalse(fixture.artifact().exists());
        assertEquals(ComponentTaskState.FAILED,
            fixture.repository.find("task-1").get().getState());
    }

    @Test
    public void retryAfterShaMismatchRedownloadsFromZero() throws Exception {
        Fixture fixture = fixture(REPLACEMENT);
        fixture.connections.add(response(200, ORIGINAL));
        try {
            fixture.downloader.download(fixture.task, DownloadControl.CONTINUE,
                DownloadProgressListener.NONE);
            fail("first corrupt response must fail");
        } catch (ComponentDownloadException expected) {
            assertEquals("sha256_mismatch", expected.getErrorCode());
        }
        ComponentTask failed = fixture.repository.find("task-1").get();
        FakeConnection retry = response(200, REPLACEMENT).header("ETag", "\"v2\"");
        fixture.connections.add(retry);

        ComponentDownloadResult result = fixture.downloader.download(failed,
            DownloadControl.CONTINUE, DownloadProgressListener.NONE);

        assertEquals(ComponentTaskState.VERIFIED, result.getTask().getState());
        assertArrayEquals(REPLACEMENT, read(result.getArtifact()));
        assertNull(retry.requestHeader("Range"));
    }

    @Test
    public void pausePersistsOffsetAndKeepsPartial() throws Exception {
        Fixture fixture = fixture(ORIGINAL);
        fixture.connections.add(new FakeConnection(200,
            new ChunkedInputStream(ORIGINAL, 3)).contentLength(ORIGINAL.length));
        DownloadControl control = new DownloadControl() {
            private int calls;

            @Override
            public Decision currentDecision() {
                return calls++ == 0 ? Decision.CONTINUE : Decision.PAUSE;
            }
        };

        ComponentDownloadResult result = fixture.downloader.download(
            fixture.task, control, DownloadProgressListener.NONE);

        assertEquals(ComponentTaskState.PAUSED, result.getTask().getState());
        assertEquals(3, result.getTask().getDownloadedBytes());
        assertEquals(3, fixture.partial().length());
        assertNull(result.getArtifact());
    }

    @Test
    public void cancelBeforeNetworkPersistsTerminalState() throws Exception {
        Fixture fixture = fixture(ORIGINAL);

        ComponentDownloadResult result = fixture.downloader.download(fixture.task,
            () -> DownloadControl.Decision.CANCEL, DownloadProgressListener.NONE);

        assertEquals(ComponentTaskState.CANCELLED, result.getTask().getState());
        assertEquals(ComponentTaskState.CANCELLED,
            fixture.repository.find("task-1").get().getState());
        assertNull(result.getArtifact());
    }

    private Fixture fixture(byte[] expectedBytes) throws Exception {
        File root = temporaryFolder.newFolder();
        File tasks = new File(root, "tasks");
        File downloads = new File(root, "downloads");
        FileComponentTaskRepository repository = new FileComponentTaskRepository(tasks);
        QueueFactory connections = new QueueFactory();
        ComponentDownloader downloader = new ComponentDownloader(
            repository, downloads, connections);
        ComponentTask task = ComponentTask.queued("task-1", "wine", 1,
            "https://example.invalid/wine.tar.xz", expectedBytes.length,
            sha256(expectedBytes));
        repository.save(task);
        return new Fixture(repository, connections, downloader, task, downloads);
    }

    private static FakeConnection response(int status, byte[] body) throws Exception {
        return new FakeConnection(status, new ByteArrayInputStream(body))
            .contentLength(body.length);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) {
            result.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
        }
        return result.toString();
    }

    private static void write(File file, byte[] value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value);
        }
    }

    private static byte[] read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static final class Fixture {
        final FileComponentTaskRepository repository;
        final QueueFactory connections;
        final ComponentDownloader downloader;
        final File downloads;
        ComponentTask task;

        Fixture(FileComponentTaskRepository repository, QueueFactory connections,
                ComponentDownloader downloader, ComponentTask task, File downloads) {
            this.repository = repository;
            this.connections = connections;
            this.downloader = downloader;
            this.task = task;
            this.downloads = downloads;
        }

        File partial() { return new File(downloads, "wine-1.part"); }
        File artifact() { return new File(downloads, "wine-1.archive"); }
    }

    private static final class QueueFactory implements HttpConnectionFactory {
        final Queue<FakeConnection> responses = new ArrayDeque<>();

        void add(FakeConnection response) {
            responses.add(response);
        }

        @Override
        public HttpURLConnection open(URL url) throws IOException {
            FakeConnection response = responses.poll();
            if (response == null) {
                throw new IOException("No fake response configured");
            }
            return response;
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int status;
        private final InputStream input;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, String> requestHeaders = new LinkedHashMap<>();
        boolean disconnected;

        FakeConnection(int status, InputStream input) throws Exception {
            super(new URL("https://example.invalid/component"));
            this.status = status;
            this.input = input;
        }

        FakeConnection header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        FakeConnection contentLength(long value) {
            headers.put("Content-Length", String.valueOf(value));
            return this;
        }

        String requestHeader(String name) {
            return requestHeaders.get(name);
        }

        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return input; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public void setRequestProperty(String key, String value) {
            requestHeaders.put(key, value);
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    private static final class ChunkedInputStream extends InputStream {
        private final byte[] value;
        private final int chunkSize;
        private int offset;

        ChunkedInputStream(byte[] value, int chunkSize) {
            this.value = value;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() {
            return offset < value.length ? value[offset++] & 0xff : -1;
        }

        @Override
        public int read(byte[] buffer, int start, int length) {
            if (offset >= value.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, chunkSize), value.length - offset);
            System.arraycopy(value, offset, buffer, start, count);
            offset += count;
            return count;
        }
    }
}
