package com.rarchives.ripme.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Answers "has this URL already been downloaded?" from one shared digest table.
 * <p>
 * {@code url_history.txt} is append-only and can be hundreds of megabytes. Scanning it
 * for every candidate URL allocates a new string per line and pushes the Java heap up
 * by gigabytes, which G1 then keeps after the rip goes idle. The file stays the record
 * on disk; this index keeps a 32-byte SHA-256 of each line.
 */
public final class UrlHistoryIndex {

    private static final Logger logger = LogManager.getLogger(UrlHistoryIndex.class);
    private static final UrlHistoryIndex INSTANCE = new UrlHistoryIndex();

    private final MessageDigest digest;
    private Byte16Set digests = new Byte16Set();
    private Path indexedPath;
    private long indexedLength = -1L;
    private long indexedModified = -1L;
    private boolean loaded;

    private UrlHistoryIndex() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static boolean contains(Path file, String url) {
        if (file == null || url == null || url.isEmpty()) {
            return false;
        }
        return INSTANCE.containsLine(file, url);
    }

    /**
     * Records a line that has already been appended to {@code file}.
     * A trailing newline, if present, is ignored so the key matches {@link BufferedReader#readLine()}.
     */
    public static void remember(Path file, String writtenLine) {
        if (file == null || writtenLine == null) {
            return;
        }
        String line = stripLineEnding(writtenLine);
        if (line.isEmpty()) {
            return;
        }
        INSTANCE.rememberLine(file, line);
    }

    static void clear() {
        INSTANCE.reset();
    }

    private synchronized void reset() {
        digests = new Byte16Set();
        indexedPath = null;
        indexedLength = -1L;
        indexedModified = -1L;
        loaded = false;
    }

    private synchronized boolean containsLine(Path file, String url) {
        Path path = file.toAbsolutePath().normalize();
        try {
            refresh(path);
        } catch (IOException e) {
            logger.warn("Could not read URL history {}: {}", path, e.getMessage());
        }
        return digests.contains(sha256(url));
    }

    private synchronized void rememberLine(Path file, String line) {
        Path path = file.toAbsolutePath().normalize();
        try {
            if (!path.equals(indexedPath) || !loaded) {
                loadAll(path);
            } else if (currentLength(path) < indexedLength) {
                loadAll(path);
            }
            digests.add(sha256(line));
            indexedPath = path;
            indexedLength = currentLength(path);
            indexedModified = currentModified(path);
            loaded = true;
        } catch (IOException e) {
            logger.warn("Could not update URL history index for {}: {}", path, e.getMessage());
            digests.add(sha256(line));
        }
    }

    private void refresh(Path path) throws IOException {
        if (!path.equals(indexedPath) || !loaded) {
            loadAll(path);
            return;
        }
        long length = currentLength(path);
        long modified = currentModified(path);
        if (length != indexedLength || modified != indexedModified) {
            loadAll(path);
        }
    }

    private void loadAll(Path path) throws IOException {
        Byte16Set loadedDigests = new Byte16Set();
        long length = currentLength(path);
        if (length >= 1024L * 1024L) {
            logger.info("Indexing URL history ({} MB) for duplicate checks", length / (1024L * 1024L));
        }
        if (Files.exists(path)) {
            // FileWriter and the previous line scanner both use the platform charset.
            Charset charset = Charset.defaultCharset();
            try (BufferedReader reader = Files.newBufferedReader(path, charset)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        loadedDigests.add(sha256(line));
                    }
                }
            }
        }
        digests = loadedDigests;
        indexedPath = path;
        indexedLength = length;
        indexedModified = currentModified(path);
        loaded = true;
    }

    private byte[] sha256(String line) {
        digest.reset();
        digest.update(line.getBytes(StandardCharsets.UTF_8));
        return Arrays.copyOf(digest.digest(), 16);
    }

    private static long currentLength(Path path) throws IOException {
        return Files.exists(path) ? Files.size(path) : 0L;
    }

    private static long currentModified(Path path) throws IOException {
        return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
    }

    private static String stripLineEnding(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n") || value.endsWith("\r")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
