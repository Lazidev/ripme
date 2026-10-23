package com.rarchives.ripme.utils;

/**
 * Open-addressed set of 32-byte keys.
 * <p>
 * Used for URL and file-hash history so millions of entries stay in a compact table
 * instead of one {@link String} object per line. Not thread-safe; callers synchronize.
 */
final class Byte32Set {

    private long[] table;
    private int capacity;
    private int size;
    private boolean hasZero;

    Byte32Set() {
        capacity = 16;
        table = new long[capacity * 4];
    }

    boolean contains(byte[] key) {
        long w0 = word(key, 0);
        long w1 = word(key, 8);
        long w2 = word(key, 16);
        long w3 = word(key, 24);
        if (w0 == 0L && w1 == 0L && w2 == 0L && w3 == 0L) {
            return hasZero;
        }
        int mask = capacity - 1;
        int slot = bucket(w0, mask);
        while (true) {
            int base = slot * 4;
            long a = table[base];
            long b = table[base + 1];
            long c = table[base + 2];
            long d = table[base + 3];
            if (a == 0L && b == 0L && c == 0L && d == 0L) {
                return false;
            }
            if (a == w0 && b == w1 && c == w2 && d == w3) {
                return true;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** @return {@code true} when the key was not already present */
    boolean add(byte[] key) {
        long w0 = word(key, 0);
        long w1 = word(key, 8);
        long w2 = word(key, 16);
        long w3 = word(key, 24);
        if (w0 == 0L && w1 == 0L && w2 == 0L && w3 == 0L) {
            if (hasZero) {
                return false;
            }
            hasZero = true;
            size++;
            return true;
        }
        ensureCapacity();
        return insertNew(w0, w1, w2, w3);
    }

    int size() {
        return size;
    }

    private void ensureCapacity() {
        int occupied = size - (hasZero ? 1 : 0);
        if ((occupied + 1) * 4 <= capacity * 3) {
            return;
        }
        if (capacity >= (1 << 26)) {
            return;
        }
        long[] old = table;
        capacity *= 2;
        table = new long[capacity * 4];
        for (int base = 0; base < old.length; base += 4) {
            long a = old[base];
            long b = old[base + 1];
            long c = old[base + 2];
            long d = old[base + 3];
            if (a != 0L || b != 0L || c != 0L || d != 0L) {
                place(a, b, c, d);
            }
        }
    }

    private boolean insertNew(long w0, long w1, long w2, long w3) {
        int mask = capacity - 1;
        int slot = bucket(w0, mask);
        while (true) {
            int base = slot * 4;
            long a = table[base];
            long b = table[base + 1];
            long c = table[base + 2];
            long d = table[base + 3];
            if (a == 0L && b == 0L && c == 0L && d == 0L) {
                table[base] = w0;
                table[base + 1] = w1;
                table[base + 2] = w2;
                table[base + 3] = w3;
                size++;
                return true;
            }
            if (a == w0 && b == w1 && c == w2 && d == w3) {
                return false;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void place(long w0, long w1, long w2, long w3) {
        int mask = capacity - 1;
        int slot = bucket(w0, mask);
        while (true) {
            int base = slot * 4;
            if (table[base] == 0L && table[base + 1] == 0L && table[base + 2] == 0L && table[base + 3] == 0L) {
                table[base] = w0;
                table[base + 1] = w1;
                table[base + 2] = w2;
                table[base + 3] = w3;
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static int bucket(long w0, int mask) {
        long x = w0 * 0x9E3779B97F4A7C15L;
        x ^= x >>> 32;
        return (int) x & mask;
    }

    private static long word(byte[] key, int offset) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("key must be 32 bytes");
        }
        return ((long) key[offset] & 0xffL) << 56
                | ((long) key[offset + 1] & 0xffL) << 48
                | ((long) key[offset + 2] & 0xffL) << 40
                | ((long) key[offset + 3] & 0xffL) << 32
                | ((long) key[offset + 4] & 0xffL) << 24
                | ((long) key[offset + 5] & 0xffL) << 16
                | ((long) key[offset + 6] & 0xffL) << 8
                | ((long) key[offset + 7] & 0xffL);
    }
}
