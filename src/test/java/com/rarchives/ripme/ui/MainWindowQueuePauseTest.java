package com.rarchives.ripme.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.DefaultListModel;

import org.junit.jupiter.api.Test;

public class MainWindowQueuePauseTest {

    @Test
    public void pausedQueueDoesNotStartUntilResumed() throws IOException {
        MainWindow mainWindow = new MainWindow(true);

        DefaultListModel<Object> queue = MainWindow.getQueueListModel();
        queue.clear();
        mainWindow.getActiveDomainCounts().clear();

        AtomicInteger startedCount = new AtomicInteger(0);
        mainWindow.setRipperLauncher((url, domain, forceRip, maxDownloads, folderPrefix) -> startedCount.incrementAndGet());

        queue.addElement("http://example.com/first");
        mainWindow.setQueuePaused(true);

        mainWindow.ripNextAlbum();
        assertTrue(mainWindow.isQueuePaused(), "Queue should remain paused");
        assertEquals(0, startedCount.get(), "No queued rip should start while paused");
        assertEquals(1, queue.getSize(), "Queue items should remain queued while paused");

        mainWindow.setQueuePaused(false);
        assertEquals(1, startedCount.get(), "Queued rip should start once queue is resumed");
        assertEquals(0, queue.getSize(), "Queue should drain after resume");
    }

    @Test
    public void newQueueItemWaitsTenSecondsUnlessAlreadyEligible() throws IOException {
        MainWindow mainWindow = new MainWindow(true);

        DefaultListModel<Object> queue = MainWindow.getQueueListModel();
        queue.clear();
        mainWindow.getActiveDomainCounts().clear();

        AtomicLong now = new AtomicLong(1_000L);
        mainWindow.setClock(() -> now.get());

        List<String> started = Collections.synchronizedList(new ArrayList<>());
        mainWindow.setRipperLauncher((url, domain, forceRip, maxDownloads, folderPrefix) ->
                started.add(url + "|" + forceRip + "|" + folderPrefix));

        queue.addElement("http://ready.example/album");
        queue.addElement(new QueueEntry("http://wait.example/album", true, null, "a", 5_000L));

        try {
            mainWindow.ripNextAlbum();
            assertEquals(List.of("http://ready.example/album|false|"), started);
            assertEquals(1, queue.getSize(), "Item still inside the grace period stays queued");

            now.set(5_000L);
            mainWindow.ripNextAlbum();
            assertEquals(List.of(
                    "http://ready.example/album|false|",
                    "http://wait.example/album|true|a"), started);
            assertEquals(0, queue.getSize());
        } finally {
            mainWindow.cancelScheduledQueueStart();
        }
    }
}
