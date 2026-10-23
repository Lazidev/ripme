package com.rarchives.ripme.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Byte32SetTest {

    @Test
    void storesAndFindsKeysAcrossGrowth() {
        Byte32Set set = new Byte32Set();
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            byte[] key = new byte[32];
            byte[] raw = UUID.randomUUID().toString().getBytes();
            System.arraycopy(raw, 0, key, 0, Math.min(raw.length, key.length));
            key[31] = (byte) (i & 0xff);
            key[30] = (byte) ((i >> 8) & 0xff);
            keys.add(key);
            assertTrue(set.add(key));
            assertFalse(set.add(key));
        }
        assertEquals(5000, set.size());
        for (byte[] key : keys) {
            assertTrue(set.contains(key));
        }
        byte[] missing = new byte[32];
        missing[0] = 1;
        assertFalse(set.contains(missing));
    }

    @Test
    void remembersAnAllZeroKey() {
        Byte32Set set = new Byte32Set();
        byte[] zero = new byte[32];
        assertTrue(set.add(zero));
        assertFalse(set.add(zero));
        assertTrue(set.contains(zero));
        assertEquals(1, set.size());
    }
}
