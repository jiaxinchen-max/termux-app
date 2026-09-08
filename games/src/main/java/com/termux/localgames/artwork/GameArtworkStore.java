package com.termux.localgames.artwork;

import android.graphics.BitmapFactory;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded private cover storage with replace/remove rollback. */
public final class GameArtworkStore {

    public static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_IMAGE_EDGE = 16_384;

    private final File directory;

    public GameArtworkStore(File filesDirectory) {
        directory = new File(new File(filesDirectory, "games"), "artwork");
    }

    public Mutation stageReplacement(String gameId, InputStream source) throws IOException {
        GameArtworkReference.validateGameId(gameId);
        ensureDirectory();
        recover(gameId);
        File target = target(gameId);
        File temporary = temporary(gameId);
        File backup = backup(gameId);
        copyBounded(source, temporary);
        validateImage(temporary);

        boolean hadPrevious = target.isFile();
        if (hadPrevious && !target.renameTo(backup)) {
            temporary.delete();
            throw new IOException("artwork_backup_failed");
        }
        if (!temporary.renameTo(target)) {
            if (hadPrevious) backup.renameTo(target);
            temporary.delete();
            throw new IOException("artwork_publish_failed");
        }
        return new Mutation(target, backup, hadPrevious, false,
            GameArtworkReference.forGame(gameId));
    }

    public Mutation stageRemoval(String gameId) throws IOException {
        GameArtworkReference.validateGameId(gameId);
        ensureDirectory();
        recover(gameId);
        File target = target(gameId);
        File backup = backup(gameId);
        boolean hadPrevious = target.isFile();
        if (hadPrevious && !target.renameTo(backup)) {
            throw new IOException("artwork_remove_stage_failed");
        }
        return new Mutation(target, backup, hadPrevious, true, "");
    }

    public File resolve(String reference) {
        return GameArtworkReference.gameId(reference).map(this::target).orElse(null);
    }

    public void delete(String gameId) throws IOException {
        GameArtworkReference.validateGameId(gameId);
        for (File file : new File[] {target(gameId), temporary(gameId), backup(gameId)}) {
            if (file.exists() && !file.delete()) throw new IOException("artwork_delete_failed");
        }
    }

    public void recover(String gameId) throws IOException {
        File target = target(gameId);
        File backup = backup(gameId);
        File temporary = temporary(gameId);
        if (temporary.exists() && !temporary.delete()) throw new IOException("artwork_temp_cleanup_failed");
        if (backup.exists()) {
            if (target.exists()) {
                if (!backup.delete()) throw new IOException("artwork_backup_cleanup_failed");
            } else if (!backup.renameTo(target)) {
                throw new IOException("artwork_recovery_failed");
            }
        }
    }

    private static void copyBounded(InputStream source, File target) throws IOException {
        long total = 0;
        byte[] buffer = new byte[32 * 1024];
        try (FileOutputStream output = new FileOutputStream(target)) {
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (count == 0) {
                    int value = source.read();
                    if (value < 0) break;
                    if (total >= MAX_SOURCE_BYTES) throw new IOException("artwork_too_large");
                    output.write(value);
                    total++;
                    continue;
                }
                if (total > MAX_SOURCE_BYTES - count) throw new IOException("artwork_too_large");
                output.write(buffer, 0, count);
                total += count;
            }
            output.getFD().sync();
        } catch (IOException error) {
            target.delete();
            throw error;
        }
        if (total == 0) {
            target.delete();
            throw new IOException("artwork_empty");
        }
    }

    private static void validateImage(File file) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (FileInputStream input = new FileInputStream(file)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            file.delete();
            throw new IOException("artwork_invalid_image");
        }
        if (options.outWidth > MAX_IMAGE_EDGE || options.outHeight > MAX_IMAGE_EDGE) {
            file.delete();
            throw new IOException("artwork_dimensions_too_large");
        }
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("artwork_directory_create_failed");
        }
    }

    private File target(String gameId) { return new File(directory, gameId + ".cover"); }
    private File temporary(String gameId) { return new File(directory, gameId + ".tmp"); }
    private File backup(String gameId) { return new File(directory, gameId + ".bak"); }

    public static final class Mutation implements Closeable {
        private final File target;
        private final File backup;
        private final boolean hadPrevious;
        private final boolean removal;
        private final String reference;
        private boolean closed;

        Mutation(File target, File backup, boolean hadPrevious, boolean removal,
                 String reference) {
            this.target = target;
            this.backup = backup;
            this.hadPrevious = hadPrevious;
            this.removal = removal;
            this.reference = reference;
        }

        public String getReference() { return reference; }

        public void commit() {
            if (closed) return;
            backup.delete();
            closed = true;
        }

        public void rollback() throws IOException {
            if (closed) return;
            if (!removal && target.exists() && !target.delete()) {
                throw new IOException("artwork_rollback_delete_failed");
            }
            if (hadPrevious && !backup.renameTo(target)) {
                throw new IOException("artwork_rollback_restore_failed");
            }
            closed = true;
        }

        @Override
        public void close() throws IOException {
            rollback();
        }
    }
}
