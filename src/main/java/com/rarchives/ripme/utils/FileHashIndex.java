package com.rarchives.ripme.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * Shared index of SHA-256 file hashes stored in {@code hash_history.txt}.
 * <p>
 * Each ripper used to copy every hex line into its own {@code HashSet} of strings
 * (about 60 MB of text, several times that as Java objects). One shared table of 16-byte
 * prefixes is enough, and concurrent rips now see each other's new hashes.
 */
public final class FileHashIndex {

    private static final Logger logger = LogManager.getLogger(FileHashIndex.class);
    private static final FileHashIndex INSTANCE = new FileHashIndex();

    private Byte16Set digests = new Byte16Set();
    private Path indexedPath;
    private long indexedLength = -1L;
    private long indexedModified = -1L;
    private boolean loaded;

    private FileHashIndex() {
    }

    /**
     * @return {@code true} when {@code hexHash} was not already recorded. The download should be kept.
     *         {@code false} when it is a duplicate.
     */
    public static boolean addIfAbsent(Path file, String hexHash) {
        if (file == null || hexHash == null) {
            return true;
        }
        byte[] key = decodeHex(hexHash.trim());
        if (key == null) {
            return true;
        }
        return INSTANCE.addNew(file, hexHash.trim().toLowerCase(Locale.ROOT), Arrays.copyOf(key, 16));
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

    private synchronized boolean addNew(Path file, String hexLine, byte[] key) {
        Path path = file.toAbsolutePath().normalize();
        try {
            refresh(path);
        } catch (IOException e) {
            logger.warn("Could not read hash history {}: {}", path, e.getMessage());
        }
        if (!digests.add(key)) {
            return false;
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, hexLine + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            indexedPath = path;
            indexedLength = Files.size(path);
            indexedModified = Files.getLastModifiedTime(path).toMillis();
            loaded = true;
        } catch (IOException e) {
            logger.warn("Failed to append hash to {}: {}", path, e.getMessage());
        }
        return true;
    }

    private void refresh(Path path) throws IOException {
        if (!path.equals(indexedPath) || !loaded) {
            loadAll(path);
            return;
        }
        long length = Files.exists(path) ? Files.size(path) : 0L;
        long modified = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        if (length != indexedLength || modified != indexedModified) {
            loadAll(path);
        }
    }

    private void loadAll(Path path) throws IOException {
        Byte16Set loadedDigests = new Byte16Set();
        long length = Files.exists(path) ? Files.size(path) : 0L;
        if (length >= 1024L * 1024L) {
            logger.info("Indexing file-hash history ({} MB)", length / (1024L * 1024L));
        }
        if (Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    byte[] key = decodeHex(line.trim());
                    if (key != null) {
                        loadedDigests.add(Arrays.copyOf(key, 16));
                    }
                }
            }
        }
        digests = loadedDigests;
        indexedPath = path;
        indexedLength = length;
        indexedModified = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        loaded = true;
    }

    static byte[] decodeHex(String hex) {
        if (hex.length() != 64) {
            return null;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) + lo);
        }
        return out;
    }
}
