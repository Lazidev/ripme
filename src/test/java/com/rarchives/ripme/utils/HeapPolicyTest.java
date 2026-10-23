package com.rarchives.ripme.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class HeapPolicyTest {

    @Test
    void applyDoesNotThrowOnHotSpot() {
        HeapPolicy.apply();
        assertTrue(Runtime.getRuntime().maxMemory() > 0);
    }
}
