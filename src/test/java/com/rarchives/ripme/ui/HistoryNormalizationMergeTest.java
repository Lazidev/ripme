package com.rarchives.ripme.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

public class HistoryNormalizationMergeTest {

    @Test
    public void mergesEntriesThatNormalizeToSameUrl() {
        History history = new History();

        HistoryEntry first = new HistoryEntry();
        first.url = "https://example.com/post?id=42&utm_source=twitter";
        first.count = 7;
        first.latestCount = 2;
        first.timesDownloaded = 2;
        first.skipped = false;
        first.startDate = new Date(1000);
        first.modifiedDate = new Date(2000);
        first.title = "Album";
        first.dir = "/tmp/one";
        history.add(first);

        HistoryEntry second = new HistoryEntry();
        second.url = "https://example.com/post?id=42&fbclid=abc";
        second.count = 3;
        second.latestCount = 1;
        second.timesDownloaded = 3;
        second.skipped = true;
        second.startDate = new Date(500);
        second.modifiedDate = new Date(3000);
        second.selected = true;
        history.add(second);

        history.normalizeAndMergeUrls(MainWindow::normalizeQueueUrl);

        assertEquals(1, history.toList().size());
        HistoryEntry merged = history.toList().get(0);
        assertEquals("https://example.com/post?id=42", merged.url);
        assertEquals(10, merged.count);
        assertEquals(3, merged.latestCount);
        assertEquals(5, merged.timesDownloaded);
        assertTrue(merged.skipped);
        assertEquals(new Date(500), merged.startDate);
        assertEquals(new Date(3000), merged.modifiedDate);
    }

    @Test
    public void removesEntryThatNeverDownloadedFiles() {
        History history = new History();
        HistoryEntry entry = new HistoryEntry();
        entry.url = "https://example.com/empty";
        entry.count = 0;
        history.add(entry);

        assertTrue(history.removeIfNeverDownloaded(entry.url));
        assertTrue(history.isEmpty());
    }

    @Test
    public void preservesEntryWithPriorDownloads() {
        History history = new History();
        HistoryEntry entry = new HistoryEntry();
        entry.url = "https://example.com/existing";
        entry.count = 3;
        history.add(entry);

        assertFalse(history.removeIfNeverDownloaded(entry.url));
        assertEquals(1, history.toList().size());
    }

    @Test
    public void hasDownloadedRequiresSuccessfulCount() {
        History history = new History();
        HistoryEntry empty = new HistoryEntry();
        empty.url = "https://example.com/empty";
        empty.count = 0;
        history.add(empty);

        HistoryEntry downloaded = new HistoryEntry();
        downloaded.url = "https://example.com/done";
        downloaded.count = 2;
        history.add(downloaded);

        assertFalse(history.hasDownloaded("https://example.com/empty"));
        assertFalse(history.hasDownloaded("https://example.com/missing"));
        assertTrue(history.hasDownloaded("https://example.com/done"));
    }

    @Test
    public void roundTripsSkippedAndTimesDownloaded() {
        HistoryEntry entry = new HistoryEntry();
        entry.url = "https://example.com/album";
        entry.skipped = true;
        entry.timesDownloaded = 4;
        entry.count = 12;
        entry.latestCount = 0;

        HistoryEntry restored = new HistoryEntry().fromJSON(entry.toJSON());
        assertTrue(restored.skipped);
        assertEquals(4, restored.timesDownloaded);
        assertEquals(12, restored.count);
        assertEquals(0, restored.latestCount);
    }
}
