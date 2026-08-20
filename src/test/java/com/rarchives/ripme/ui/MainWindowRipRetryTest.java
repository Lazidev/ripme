package com.rarchives.ripme.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rarchives.ripme.utils.Utils;

public class MainWindowRipRetryTest {

    @AfterEach
    public void restoreRetryDefaults() {
        Utils.setConfigInteger("rip.retries", 2);
        Utils.setConfigInteger("rip.retry.delay", 30000);
        Utils.setConfigInteger("queue.max_per_domain", 1);
        Utils.setConfigInteger("queue.rate_limit.recovery", 300000);
    }

    @Test
    public void recognisesTemporaryFailures() {
        assertTrue(MainWindow.looksLikeTransientRipError("java.net.SocketTimeoutException: Read timed out"));
        assertTrue(MainWindow.looksLikeTransientRipError("Connection reset"));
        assertTrue(MainWindow.looksLikeTransientRipError("HTTP status code 503 for URL http://example.com/a"));
        assertTrue(MainWindow.looksLikeTransientRipError("HTTP status code 429 for URL http://example.com/a"));
        assertTrue(MainWindow.looksLikeTransientRipError("Too Many Requests"));
    }

    @Test
    public void leavesPermanentFailuresAlone() {
        assertFalse(MainWindow.looksLikeTransientRipError(null));
        assertFalse(MainWindow.looksLikeTransientRipError("No images found at http://example.com/album"));
        assertFalse(MainWindow.looksLikeTransientRipError("HTTP status code 404 for URL http://example.com/a"));
        assertFalse(MainWindow.looksLikeTransientRipError("No ripper for URL http://example.com/a"));
    }

    @Test
    public void doesNotRetryDeliberateStops() {
        assertFalse(MainWindow.looksLikeTransientRipError("Ripping interrupted"),
                "A user cancel or download limit must not be retried");
    }

    @Test
    public void backsOffExponentiallyBetweenRetries() {
        Utils.setConfigInteger("rip.retry.delay", 10000);
        assertEquals(10000, MainWindow.getRipRetryDelayMillis(1));
        assertEquals(20000, MainWindow.getRipRetryDelayMillis(2));
        assertEquals(40000, MainWindow.getRipRetryDelayMillis(3));
    }

    @Test
    public void retriesCanBeDisabled() {
        Utils.setConfigInteger("rip.retries", 0);
        assertEquals(0, MainWindow.getMaxRipRetries());
    }

    @Test
    public void wwwAndBareHostShareOneConcurrencyBucket() {
        assertEquals("example.com", MainWindow.normalizeDomain("WWW.Example.com"));
        assertEquals("example.com", MainWindow.normalizeDomain("example.com"));
        assertEquals("cdn.example.com", MainWindow.normalizeDomain("cdn.example.com"));
    }

    @Test
    public void rateLimitThrottlesOnlyTheOffendingDomain() throws IOException {
        Utils.setConfigInteger("queue.max_per_domain", 4);
        MainWindow mainWindow = new MainWindow(true);

        mainWindow.throttleDomainAfterRateLimit("example.com");

        assertEquals(2, mainWindow.getMaxRipsForDomain("example.com"), "Rate limit should halve the allowance");
        assertEquals(4, mainWindow.getMaxRipsForDomain("other.com"), "Other domains must be unaffected");
        assertEquals(4, mainWindow.getMaxRipsPerDomain(), "The user's configured ceiling must not be rewritten");
        assertEquals(4, Utils.getConfigInteger("queue.max_per_domain", 1),
                "Throttling is session-scoped and must not be persisted to config");
    }

    @Test
    public void repeatedRateLimitsBottomOutAtOneRip() throws IOException {
        Utils.setConfigInteger("queue.max_per_domain", 4);
        MainWindow mainWindow = new MainWindow(true);

        mainWindow.throttleDomainAfterRateLimit("example.com");
        mainWindow.throttleDomainAfterRateLimit("example.com");
        mainWindow.throttleDomainAfterRateLimit("example.com");

        assertEquals(1, mainWindow.getMaxRipsForDomain("example.com"),
                "The allowance must never drop below one, or the domain would stall forever");
    }

    @Test
    public void throttleRecoversOneSlotPerQuietInterval() throws IOException {
        Utils.setConfigInteger("queue.max_per_domain", 4);
        Utils.setConfigInteger("queue.rate_limit.recovery", 60000);
        MainWindow mainWindow = new MainWindow(true);
        AtomicLong now = new AtomicLong(0);
        mainWindow.setClock(now::get);

        mainWindow.throttleDomainAfterRateLimit("example.com");
        mainWindow.throttleDomainAfterRateLimit("example.com");
        assertEquals(1, mainWindow.getMaxRipsForDomain("example.com"));

        now.addAndGet(30_000);
        assertEquals(1, mainWindow.getMaxRipsForDomain("example.com"), "Recovery should wait out the full interval");

        now.addAndGet(30_000);
        assertEquals(2, mainWindow.getMaxRipsForDomain("example.com"));

        now.addAndGet(120_000);
        assertEquals(4, mainWindow.getMaxRipsForDomain("example.com"), "Should climb back to the configured ceiling");
    }

    @Test
    public void aFreshRateLimitRestartsTheRecoveryClock() throws IOException {
        Utils.setConfigInteger("queue.max_per_domain", 4);
        Utils.setConfigInteger("queue.rate_limit.recovery", 60000);
        MainWindow mainWindow = new MainWindow(true);
        AtomicLong now = new AtomicLong(0);
        mainWindow.setClock(now::get);

        mainWindow.throttleDomainAfterRateLimit("example.com");
        now.addAndGet(59_000);
        mainWindow.throttleDomainAfterRateLimit("example.com");

        now.addAndGet(59_000);
        assertEquals(1, mainWindow.getMaxRipsForDomain("example.com"),
                "A second rate limit should reset the cooldown rather than let recovery continue");
    }

    @Test
    public void ceilingOfOneNeedsNoThrottling() throws IOException {
        Utils.setConfigInteger("queue.max_per_domain", 1);
        MainWindow mainWindow = new MainWindow(true);

        mainWindow.throttleDomainAfterRateLimit("example.com");

        assertEquals(1, mainWindow.getMaxRipsForDomain("example.com"));
    }
}
