package com.termux.localgames.components;

import com.termux.localgames.data.ComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Blocking component download primitive. Call only from an orchestrator worker. */
public final class ComponentDownloader {

    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long PROGRESS_PERSIST_INTERVAL_BYTES = 1024 * 1024;
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private final ComponentTaskRepository repository;
    private final File downloadDirectory;
    private final HttpConnectionFactory connectionFactory;

    public ComponentDownloader(ComponentTaskRepository repository, File downloadDirectory,
                               HttpConnectionFactory connectionFactory) throws IOException {
        if (repository == null || downloadDirectory == null || connectionFactory == null) {
            throw new IllegalArgumentException("downloader dependencies must not be null");
        }
        this.repository = repository;
        this.downloadDirectory = downloadDirectory;
        this.connectionFactory = connectionFactory;
        ensureDirectory(downloadDirectory);
    }

    public synchronized ComponentDownloadResult download(ComponentTask initialTask,
                                                         DownloadControl control,
                                                         DownloadProgressListener listener)
        throws IOException {
        if (initialTask == null || control == null || listener == null) {
            throw new IllegalArgumentException("download arguments must not be null");
        }
        File partial = partialFile(initialTask);
        File artifact = artifactFile(initialTask);
        ComponentTask task = initialTask;

        try {
            validateSource(task.getUrl());
            if (task.getState() == ComponentTaskState.CANCELLED) {
                throw new ComponentDownloadException("task_cancelled",
                    "Cancelled component task cannot resume");
            }
            if (artifact.isFile() && verify(artifact, task)) {
                if (task.getState() == ComponentTaskState.INSTALLED) {
                    return new ComponentDownloadResult(task, artifact);
                }
                if (task.getState() != ComponentTaskState.VERIFIED) {
                    task = transition(task, ComponentTaskState.VERIFYING,
                        task.getExpectedSize(), task.getEtag(), task.getLastModified(),
                        "", "", listener);
                    task = transition(task, ComponentTaskState.VERIFIED,
                        task.getExpectedSize(), task.getEtag(), task.getLastModified(),
                        "", "", listener);
                }
                return new ComponentDownloadResult(task, artifact);
            }
            if (task.getState() == ComponentTaskState.VERIFIED ||
                task.getState() == ComponentTaskState.INSTALLED) {
                throw new ComponentDownloadException("verified_artifact_missing",
                    "Verified component artifact is missing or invalid");
            }

            long offset = normalizePartial(partial, task.getExpectedSize());
            if (offset == task.getExpectedSize()) {
                task = transition(task, ComponentTaskState.VERIFYING, offset,
                    task.getEtag(), task.getLastModified(), "", "", listener);
                if (verify(partial, task)) {
                    File verified = moveVerified(partial, artifact);
                    task = transition(task, ComponentTaskState.VERIFIED, offset,
                        task.getEtag(), task.getLastModified(), "", "", listener);
                    return new ComponentDownloadResult(task, verified);
                }
                truncate(partial);
                offset = 0;
            }

            task = transition(task, ComponentTaskState.DOWNLOADING, offset,
                offset == 0 ? "" : task.getEtag(),
                offset == 0 ? "" : task.getLastModified(), "", "", listener);
            ComponentDownloadResult stopped = applyControl(task, partial, control, listener);
            if (stopped != null) {
                return stopped;
            }

            Response response = open(task, offset);
            if (response.completePartial) {
                response.close();
                task = transition(task, ComponentTaskState.VERIFYING, offset,
                    task.getEtag(), task.getLastModified(), "", "", listener);
                if (!verify(partial, task)) {
                    throw new ComponentDownloadException("sha256_mismatch",
                        "Complete partial failed SHA-256 verification");
                }
                File verified = moveVerified(partial, artifact);
                task = transition(task, ComponentTaskState.VERIFIED, offset,
                    task.getEtag(), task.getLastModified(), "", "", listener);
                return new ComponentDownloadResult(task, verified);
            }

            if (response.restartRequired) {
                response.close();
                truncate(partial);
                offset = 0;
                task = transition(task, ComponentTaskState.DOWNLOADING, 0,
                    "", "", "", "", listener);
                response = open(task, 0);
            }

            task = transition(task, ComponentTaskState.DOWNLOADING, response.offset,
                response.etag, response.lastModified, "", "", listener);
            task = transfer(task, partial, response, control, listener);
            if (task.getState() == ComponentTaskState.PAUSED ||
                task.getState() == ComponentTaskState.CANCELLED) {
                return new ComponentDownloadResult(task, null);
            }

            task = transition(task, ComponentTaskState.VERIFYING,
                task.getExpectedSize(), task.getEtag(), task.getLastModified(),
                "", "", listener);
            if (!verify(partial, task)) {
                throw new ComponentDownloadException("sha256_mismatch",
                    "Downloaded component failed SHA-256 verification");
            }
            File verified = moveVerified(partial, artifact);
            task = transition(task, ComponentTaskState.VERIFIED,
                task.getExpectedSize(), task.getEtag(), task.getLastModified(),
                "", "", listener);
            return new ComponentDownloadResult(task, verified);
        } catch (IOException error) {
            if (!task.getState().isTerminal() && task.getState() != ComponentTaskState.PAUSED) {
                String code = error instanceof ComponentDownloadException
                    ? ((ComponentDownloadException) error).getErrorCode()
                    : "io_error";
                try {
                    task = transition(task, ComponentTaskState.FAILED,
                        Math.min(partial.length(), task.getExpectedSize()),
                        task.getEtag(), task.getLastModified(), code,
                        safeMessage(error), listener);
                } catch (IOException persistenceError) {
                    error.addSuppressed(persistenceError);
                }
            }
            throw error;
        }
    }

    private ComponentTask transfer(ComponentTask task, File partial, Response response,
                                   DownloadControl control,
                                   DownloadProgressListener listener) throws IOException {
        long total = response.offset;
        long persistedBytes = response.offset;
        try (InputStream input = new BufferedInputStream(response.connection.getInputStream());
             FileOutputStream fileOutput = new FileOutputStream(partial, response.append);
             BufferedOutputStream output = new BufferedOutputStream(fileOutput)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > task.getExpectedSize()) {
                    throw new ComponentDownloadException("size_mismatch",
                        "Download exceeds declared component size");
                }
                output.write(buffer, 0, read);
                output.flush();
                task = task.transition(ComponentTaskState.DOWNLOADING, total,
                    response.etag, response.lastModified, "", "");
                if (total - persistedBytes >= PROGRESS_PERSIST_INTERVAL_BYTES) {
                    publish(task, listener);
                    persistedBytes = total;
                }
                ComponentDownloadResult stopped = applyControl(task, partial, control, listener);
                if (stopped != null) {
                    fileOutput.getFD().sync();
                    return stopped.getTask();
                }
            }
            output.flush();
            fileOutput.getFD().sync();
        } finally {
            response.close();
        }
        if (total != task.getExpectedSize()) {
            throw new ComponentDownloadException("size_mismatch",
                "Downloaded component size does not match declaration");
        }
        return task;
    }

    private Response open(ComponentTask task, long offset) throws IOException {
        HttpURLConnection connection = connectionFactory.open(new URL(task.getUrl()));
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) {
            connection.setRequestProperty("Range", "bytes=" + offset + "-");
            String ifRange = strongValidator(task.getEtag(), task.getLastModified());
            if (!ifRange.isEmpty()) {
                connection.setRequestProperty("If-Range", ifRange);
            }
        }

        int status = connection.getResponseCode();
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
            connection.disconnect();
            throw new ComponentDownloadException("insecure_redirect",
                "Component download redirected to a non-HTTPS source");
        }
        String etag = header(connection, "ETag");
        String lastModified = header(connection, "Last-Modified");
        String contentEncoding = header(connection, "Content-Encoding");
        if (!contentEncoding.isEmpty() && !"identity".equalsIgnoreCase(contentEncoding)) {
            connection.disconnect();
            throw new ComponentDownloadException("unsupported_content_encoding",
                "Component response must use identity content encoding");
        }
        if (offset > 0 && status == HTTP_RANGE_NOT_SATISFIABLE &&
            offset == task.getExpectedSize()) {
            return Response.completePartial(connection, offset);
        }
        if (offset > 0 && status == HttpURLConnection.HTTP_PARTIAL) {
            ContentRange range = ContentRange.parse(connection.getHeaderField("Content-Range"));
            if (range.start != offset || range.total != task.getExpectedSize()) {
                connection.disconnect();
                throw new ComponentDownloadException("invalid_content_range",
                    "Content-Range does not match local offset or declared size");
            }
            validateResponseLength(connection, range.length());
            boolean changed = changed(task.getEtag(), etag) ||
                changed(task.getLastModified(), lastModified);
            return new Response(connection, offset, true,
                etag.isEmpty() ? task.getEtag() : etag,
                lastModified.isEmpty() ? task.getLastModified() : lastModified,
                changed, false);
        }
        if (status == HttpURLConnection.HTTP_OK) {
            validateResponseLength(connection, task.getExpectedSize());
            return new Response(connection, 0, false, etag, lastModified,
                false, false);
        }
        if (offset == 0 && status == HttpURLConnection.HTTP_PARTIAL) {
            ContentRange range = ContentRange.parse(connection.getHeaderField("Content-Range"));
            if (range.start != 0 || range.total != task.getExpectedSize()) {
                connection.disconnect();
                throw new ComponentDownloadException("invalid_content_range",
                    "Initial partial response does not cover declared component");
            }
            validateResponseLength(connection, range.length());
            return new Response(connection, 0, false, etag, lastModified,
                false, false);
        }
        connection.disconnect();
        throw new ComponentDownloadException("http_error", "HTTP " + status);
    }

    private ComponentDownloadResult applyControl(ComponentTask task, File partial,
                                                 DownloadControl control,
                                                 DownloadProgressListener listener)
        throws IOException {
        DownloadControl.Decision decision = control.currentDecision();
        if (decision == DownloadControl.Decision.PAUSE) {
            ComponentTask paused = transition(task, ComponentTaskState.PAUSED,
                Math.min(partial.length(), task.getExpectedSize()), task.getEtag(),
                task.getLastModified(), "", "", listener);
            return new ComponentDownloadResult(paused, null);
        }
        if (decision == DownloadControl.Decision.CANCEL) {
            ComponentTask cancelled = transition(task, ComponentTaskState.CANCELLED,
                Math.min(partial.length(), task.getExpectedSize()), task.getEtag(),
                task.getLastModified(), "", "", listener);
            return new ComponentDownloadResult(cancelled, null);
        }
        return null;
    }

    private ComponentTask transition(ComponentTask task, ComponentTaskState state,
                                     long downloadedBytes, String etag,
                                     String lastModified, String errorCode,
                                     String errorMessage,
                                     DownloadProgressListener listener) throws IOException {
        ComponentTask updated = task.transition(state, downloadedBytes, etag,
            lastModified, errorCode, errorMessage);
        publish(updated, listener);
        return updated;
    }

    private void publish(ComponentTask task, DownloadProgressListener listener)
        throws IOException {
        repository.save(task);
        try {
            listener.onTaskUpdated(task);
        } catch (RuntimeException ignored) {
            // Observer failures must not change the persisted task outcome.
        }
    }

    private static void validateResponseLength(HttpURLConnection connection,
                                               long maximumLength)
        throws ComponentDownloadException {
        long length = -1;
        String header = connection.getHeaderField("Content-Length");
        if (header != null && !header.trim().isEmpty()) {
            try {
                length = Long.parseLong(header.trim());
            } catch (NumberFormatException e) {
                connection.disconnect();
                throw new ComponentDownloadException("invalid_content_length",
                    "Invalid Content-Length", e);
            }
        }
        if (length > maximumLength) {
            connection.disconnect();
            throw new ComponentDownloadException("size_mismatch",
                "HTTP response exceeds expected byte count");
        }
    }

    private static boolean changed(String persisted, String remote) {
        return !persisted.isEmpty() && !remote.isEmpty() && !persisted.equals(remote);
    }

    private static String strongValidator(String etag, String lastModified) {
        if (!etag.isEmpty() && !etag.startsWith("W/")) {
            return etag;
        }
        return lastModified;
    }

    private static String header(HttpURLConnection connection, String name) {
        String value = connection.getHeaderField(name);
        return value == null ? "" : value;
    }

    private static void validateSource(String source) throws IOException {
        URL url;
        try {
            url = new URL(source);
        } catch (MalformedURLException e) {
            throw new ComponentDownloadException("invalid_source",
                "Component source URL is invalid", e);
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new ComponentDownloadException("insecure_source",
                "Component source must use HTTPS");
        }
    }

    private static long normalizePartial(File partial, long expectedSize) throws IOException {
        if (!partial.exists()) {
            return 0;
        }
        if (!partial.isFile() || partial.length() > expectedSize) {
            truncate(partial);
            return 0;
        }
        return partial.length();
    }

    private static boolean verify(File file, ComponentTask task) throws IOException {
        return file.isFile() && file.length() == task.getExpectedSize() &&
            task.getSha256().equals(sha256(file));
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) {
            value.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private static File moveVerified(File partial, File artifact) throws IOException {
        if (artifact.exists() && !artifact.delete()) {
            throw new IOException("Unable to replace old component artifact");
        }
        if (!partial.renameTo(artifact)) {
            throw new IOException("Unable to publish verified component artifact");
        }
        return artifact;
    }

    private File partialFile(ComponentTask task) {
        return new File(downloadDirectory,
            task.getPackageName() + "-" + task.getVersion() + ".part");
    }

    private File artifactFile(ComponentTask task) {
        return new File(downloadDirectory,
            task.getPackageName() + "-" + task.getVersion() + ".archive");
    }

    private static void truncate(File file) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.getFD().sync();
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IOException("Unable to create component download directory");
        }
    }

    private static String safeMessage(IOException error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName() : message;
    }

    private static final class Response {
        final HttpURLConnection connection;
        final long offset;
        final boolean append;
        final String etag;
        final String lastModified;
        final boolean restartRequired;
        final boolean completePartial;

        Response(HttpURLConnection connection, long offset, boolean append,
                 String etag, String lastModified, boolean restartRequired,
                 boolean completePartial) {
            this.connection = connection;
            this.offset = offset;
            this.append = append;
            this.etag = etag;
            this.lastModified = lastModified;
            this.restartRequired = restartRequired;
            this.completePartial = completePartial;
        }

        static Response completePartial(HttpURLConnection connection, long offset) {
            return new Response(connection, offset, true, "", "", false, true);
        }

        void close() {
            connection.disconnect();
        }
    }
}
