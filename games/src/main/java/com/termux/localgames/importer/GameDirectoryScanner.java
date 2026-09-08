package com.termux.localgames.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded, read-only traversal of a provider-neutral game directory tree. */
public final class GameDirectoryScanner {

    private final GameScanLimits limits;
    private final PeExecutableInspector inspector;
    private final ExecutableCandidateRanker ranker;

    public GameDirectoryScanner(GameScanLimits limits) {
        this(limits, new PeExecutableInspector(), new ExecutableCandidateRanker());
    }

    GameDirectoryScanner(GameScanLimits limits, PeExecutableInspector inspector,
                         ExecutableCandidateRanker ranker) {
        this.limits = limits;
        this.inspector = inspector;
        this.ranker = ranker;
    }

    public GameScanResult scan(GameDocumentTree tree) throws IOException {
        GameDocument root = tree.root();
        if (!root.isDirectory()) throw new IOException("selected_document_is_not_directory");

        Deque<DirectoryFrame> pending = new ArrayDeque<>();
        pending.add(new DirectoryFrame(root, "", 0));
        Set<String> visited = new HashSet<>();
        visited.add(root.getUri());
        EnumSet<ScanLimit> reached = EnumSet.noneOf(ScanLimit.class);
        List<ExecutableCandidate> candidates = new ArrayList<>();
        int documents = 0;
        int directories = 0;
        int unreadable = 0;
        long declaredBytes = 0;
        boolean stop = false;

        while (!pending.isEmpty() && !stop) {
            ensureNotInterrupted();
            if (directories >= limits.getMaxDirectories()) {
                reached.add(ScanLimit.DIRECTORIES);
                break;
            }
            DirectoryFrame frame = pending.removeFirst();
            directories++;
            List<GameDocument> children = new ArrayList<>(tree.listChildren(frame.document));
            children.sort(Comparator.comparing(GameDocument::getName,
                String.CASE_INSENSITIVE_ORDER).thenComparing(GameDocument::getUri));

            for (GameDocument child : children) {
                ensureNotInterrupted();
                if (documents >= limits.getMaxDocuments()) {
                    reached.add(ScanLimit.DOCUMENTS);
                    stop = true;
                    break;
                }
                documents++;
                String name = safeSegment(child.getName());
                String relativePath = frame.relativePath.isEmpty()
                    ? name : frame.relativePath + "/" + name;

                if (child.isDirectory()) {
                    if (frame.depth >= limits.getMaxDepth()) {
                        reached.add(ScanLimit.DEPTH);
                    } else if (visited.add(child.getUri())) {
                        pending.addLast(new DirectoryFrame(child, relativePath, frame.depth + 1));
                    }
                    continue;
                }

                long size = Math.max(0, child.getSize());
                if (declaredBytes > limits.getMaxTotalBytes() - Math.min(
                    size, limits.getMaxTotalBytes())) {
                    reached.add(ScanLimit.TOTAL_BYTES);
                    stop = true;
                    break;
                }
                declaredBytes += size;
                if (declaredBytes > limits.getMaxTotalBytes()) {
                    reached.add(ScanLimit.TOTAL_BYTES);
                    stop = true;
                    break;
                }
                if (!name.toLowerCase(Locale.US).endsWith(".exe")) continue;
                try (InputStream input = tree.open(child)) {
                    if (inspector.isPortableExecutable(input)) {
                        if (candidates.size() >= limits.getMaxCandidates()) {
                            reached.add(ScanLimit.CANDIDATES);
                            stop = true;
                            break;
                        }
                        candidates.add(ranker.rank(child, relativePath, root.getName()));
                    }
                } catch (InterruptedIOException error) {
                    throw error;
                } catch (IOException error) {
                    unreadable++;
                }
            }
        }

        candidates.sort(Comparator.comparingInt(ExecutableCandidate::getScore).reversed()
            .thenComparing(ExecutableCandidate::getRelativePath,
                String.CASE_INSENSITIVE_ORDER));
        return new GameScanResult(root.getName(), candidates, reached, documents,
            directories, unreadable, declaredBytes);
    }

    private static String safeSegment(String name) throws IOException {
        if (name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 ||
            name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("invalid_document_name");
        }
        return name;
    }

    private static void ensureNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("scan_interrupted");
        }
    }

    private static final class DirectoryFrame {
        final GameDocument document;
        final String relativePath;
        final int depth;

        DirectoryFrame(GameDocument document, String relativePath, int depth) {
            this.document = document;
            this.relativePath = relativePath;
            this.depth = depth;
        }
    }
}
