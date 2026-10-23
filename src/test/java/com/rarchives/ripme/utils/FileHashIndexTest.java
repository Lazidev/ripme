package com.rarchives.ripme.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileHashIndexTest {

    private static final String HASH_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @BeforeEach
    void resetIndex() {
        FileHashIndex.clear();
    }

    @Test
    void treatsExistingHashesAsDuplicatesAndAppendsNewOnes() throws Exception {
        Path file = Files.createTempFile("ripme-hash-history", ".txt");
        Files.writeString(file, HASH_A.toUpperCase() + System.lineSeparator());

        assertFalse(FileHashIndex.addIfAbsent(file, HASH_A));
        assertTrue(FileHashIndex.addIfAbsent(file, HASH_B));
        assertFalse(FileHashIndex.addIfAbsent(file, HASH_B));

        String stored = Files.readString(file);
        assertTrue(stored.toLowerCase().contains(HASH_A));
        assertTrue(stored.contains(HASH_B));
    }
}
