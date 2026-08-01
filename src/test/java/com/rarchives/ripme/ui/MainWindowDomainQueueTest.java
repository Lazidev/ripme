package com.rarchives.ripme.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.DefaultListModel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rarchives.ripme.utils.Utils;

public class MainWindowDomainQueueTest {

    @AfterEach
    public void restoreDefaultConcurrency() {
        Utils.setConfigInteger("queue.max_per_domain", 1);
    }

    @Test
    public void queuedAlbumsStartAfterDomainIsFree() throws IOException, InterruptedException {
        Utils.setConfigInteger("queue.max_per_domain", 1);
        MainWindow mainWindow = new MainWindow(true);

        DefaultListModel<Object> queue = MainWindow.getQueueListModel();
        queue.clear();
        mainWindow.getActiveDomainCounts().clear();

        List<String> startedDomains = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startLatch = new CountDownLatch(3);
        CountDownLatch finishLatch = new CountDownLatch(3);

        // Domain slots are reserved by ripNextAlbum before the launcher runs.
        mainWindow.setRipperLauncher((url, domain, forceRip, maxDownloads) -> {
            startedDomains.add(domain + ":" + url);
            startLatch.countDown();

            new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                mainWindow.onRipperFinished(domain, null);
                finishLatch.countDown();
            }).start();
        });

        queue.addElement("http://example.com/first");
        queue.addElement("http://other.com/other");
        queue.addElement("http://example.com/second");

        mainWindow.ripNextAlbum();

        assertTrue(startLatch.await(5, TimeUnit.SECONDS), "All rippers should have started");
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS), "All rippers should have finished");

        List<String> expectedOrder = List.of(
                "example.com:http://example.com/first",
                "other.com:http://other.com/other",
                "example.com:http://example.com/second");
        assertEquals(expectedOrder, startedDomains, "Queue should start unrelated domains while waiting for same-domain completion");
        assertTrue(mainWindow.getActiveDomainCounts().isEmpty(), "All active domains should be cleared after completion");
        assertEquals(0, queue.getSize(), "Queue should be empty after processing");
    }

    @Test
    public void allowsConfiguredConcurrentRipsPerDomain() throws IOException, InterruptedException {
        Utils.setConfigInteger("queue.max_per_domain", 2);
        MainWindow mainWindow = new MainWindow(true);

        DefaultListModel<Object> queue = MainWindow.getQueueListModel();
        queue.clear();
        mainWindow.getActiveDomainCounts().clear();

        List<String> startedUrls = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger peakSameDomain = new AtomicInteger();
        CountDownLatch bothSameDomainStarted = new CountDownLatch(2);
        CountDownLatch finishLatch = new CountDownLatch(3);

        mainWindow.setRipperLauncher((url, domain, forceRip, maxDownloads) -> {
            startedUrls.add(url);
            int inFlight = mainWindow.getActiveDomainCounts()
                    .getOrDefault(domain, new AtomicInteger())
                    .get();
            peakSameDomain.accumulateAndGet(inFlight, Math::max);
            if ("example.com".equals(domain)) {
                bothSameDomainStarted.countDown();
            }

            new Thread(() -> {
                try {
                    // Hold the first two same-domain rips long enough for both to be in flight.
                    Thread.sleep("http://example.com/first".equals(url) || "http://example.com/second".equals(url)
                            ? 200
                            : 50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                mainWindow.onRipperFinished(domain, null);
                finishLatch.countDown();
            }).start();
        });

        queue.addElement("http://example.com/first");
        queue.addElement("http://example.com/second");
        queue.addElement("http://example.com/third");

        mainWindow.ripNextAlbum();

        assertTrue(bothSameDomainStarted.await(5, TimeUnit.SECONDS),
                "Two same-domain rips should start concurrently when max_per_domain is 2");
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS), "All rippers should have finished");
        assertEquals(3, startedUrls.size());
        assertTrue(peakSameDomain.get() <= 2, "Should never exceed configured max per domain");
        assertTrue(mainWindow.getActiveDomainCounts().isEmpty());
        assertEquals(0, queue.getSize());
    }

    @Test
    public void treatsWwwAndBareHostAsSameDomain() throws IOException, InterruptedException {
        Utils.setConfigInteger("queue.max_per_domain", 1);
        MainWindow mainWindow = new MainWindow(true);

        DefaultListModel<Object> queue = MainWindow.getQueueListModel();
        queue.clear();
        mainWindow.getActiveDomainCounts().clear();

        List<String> startedUrls = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);
        AtomicInteger maxInFlight = new AtomicInteger();

        mainWindow.setRipperLauncher((url, domain, forceRip, maxDownloads) -> {
            startedUrls.add(url);
            int inFlight = mainWindow.getActiveDomainCounts()
                    .getOrDefault(domain, new AtomicInteger())
                    .get();
            maxInFlight.accumulateAndGet(inFlight, Math::max);
            firstStarted.countDown();

            new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                mainWindow.onRipperFinished(domain, null);
                finishLatch.countDown();
            }).start();
        });

        queue.addElement("http://www.example.com/first");
        queue.addElement("http://example.com/second");

        mainWindow.ripNextAlbum();

        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS));
        assertEquals(2, startedUrls.size());
        assertEquals(1, maxInFlight.get(), "www and bare host must share the concurrency limit");
    }
}
