package com.rarchives.ripme.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.rarchives.ripme.utils.Utils;

public class MainWindowQueueUrlNormalizationTest {

    @Test
    public void normalizesInstagramTrackingParameters() {
        assertEquals(
                "https://www.instagram.com/beccapoppyhaigh",
                MainWindow.normalizeQueueUrl("https://www.instagram.com/beccapoppyhaigh/?g=5"));
        assertEquals(
                "https://www.instagram.com/lyssaurora",
                MainWindow.normalizeQueueUrl(
                        "https://www.instagram.com/lyssaurora/?e=ee8f574d-9a29-4bb3-b0e5-e5a04685a595&g=5"));
    }

    @Test
    public void leavesNonInstagramUrlsUntouched() {
        String reddit = "https://www.reddit.com/search/?q=missy+mae&type=media";
        assertEquals(reddit, MainWindow.normalizeQueueUrl(reddit));
    }

    @Test
    public void stripsCommonTrackingParametersForNonInstagramUrls() {
        assertEquals(
                "https://example.com/post?id=42",
                MainWindow.normalizeQueueUrl("https://example.com/post?id=42&utm_source=twitter&fbclid=abc"));
        assertEquals(
                "https://example.com/post?id=42",
                MainWindow.normalizeQueueUrl("https://example.com/post?id=42&amp;utm_source=twitter&amp;fbclid=abc"));
        assertEquals(
                "https://example.com/post?id=42",
                MainWindow.normalizeQueueUrl("https://example.com/post?id=42&utm%5Fsource=twitter"));
    }

    @Test
    public void addUrlToQueueStoresNormalizedInstagramUrl() throws IOException {
        MainWindow mainWindow = new MainWindow(true);
        MainWindow.getQueueListModel().clear();

        MainWindow.addUrlToQueue("https://www.instagram.com/beccapoppyhaigh/?g=5");

        assertEquals("https://www.instagram.com/beccapoppyhaigh",
                QueueEntry.from(MainWindow.getQueueListModel().get(0)).getUrl());
    }

    @Test
    public void addUrlToQueueDeduplicatesEquivalentUrls() throws IOException {
        MainWindow mainWindow = new MainWindow(true);
        MainWindow.getQueueListModel().clear();

        MainWindow.addUrlToQueue("https://example.com/post?id=42&utm_source=twitter");
        MainWindow.addUrlToQueue("https://example.com/post?id=42");

        assertEquals(1, MainWindow.getQueueListModel().size());
        assertEquals("https://example.com/post?id=42",
                QueueEntry.from(MainWindow.getQueueListModel().get(0)).getUrl());
    }

    @Test
    public void forceFlagSurvivesConfigRoundTripEncoding() {
        QueueEntry forced = new QueueEntry("https://example.com/a", true);
        QueueEntry restored = QueueEntry.fromConfigString(forced.toConfigString());
        assertEquals("https://example.com/a", restored.getUrl());
        assertEquals(true, restored.isForceRip());

        QueueEntry normal = QueueEntry.fromConfigString("https://example.com/b");
        assertEquals("https://example.com/b", normal.getUrl());
        assertEquals(false, normal.isForceRip());
    }

    @Test
    public void maxDownloadsSurvivesConfigRoundTripEncoding() {
        QueueEntry withMax = new QueueEntry("https://example.com/a", false, 42);
        QueueEntry restored = QueueEntry.fromConfigString(withMax.toConfigString());
        assertEquals("https://example.com/a", restored.getUrl());
        assertEquals(false, restored.isForceRip());
        assertEquals(Integer.valueOf(42), restored.getMaxDownloads());

        QueueEntry forcedMax = new QueueEntry("https://example.com/b", true, 10);
        QueueEntry restoredForced = QueueEntry.fromConfigString(forcedMax.toConfigString());
        assertEquals("https://example.com/b", restoredForced.getUrl());
        assertEquals(true, restoredForced.isForceRip());
        assertEquals(Integer.valueOf(10), restoredForced.getMaxDownloads());
        assertEquals("force|max=10|https://example.com/b", forcedMax.toConfigString());
    }

    @Test
    public void addUrlToQueueDefaultsMaxDownloadsFromConfig() throws IOException {
        MainWindow mainWindow = new MainWindow(true);
        MainWindow.getQueueListModel().clear();

        MainWindow.addUrlToQueue("https://example.com/album");

        QueueEntry entry = QueueEntry.from(MainWindow.getQueueListModel().get(0));
        assertEquals(Utils.getConfigInteger("maxdownloads", 250), entry.getEffectiveMaxDownloads());
        assertEquals(Integer.valueOf(Utils.getConfigInteger("maxdownloads", 250)), entry.getMaxDownloads());
    }
}
