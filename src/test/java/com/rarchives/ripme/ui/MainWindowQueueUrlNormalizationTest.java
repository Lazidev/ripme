package com.rarchives.ripme.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public void folderPrefixSurvivesConfigRoundTripAndIsOneLetter() {
        QueueEntry prefixed = new QueueEntry("https://example.com/a", true, 10, "ab");
        assertEquals("a", prefixed.getFolderPrefix());
        assertEquals("force|prefix=a|max=10|https://example.com/a", prefixed.toConfigString());

        QueueEntry restored = QueueEntry.fromConfigString(prefixed.toConfigString());
        assertEquals("https://example.com/a", restored.getUrl());
        assertEquals(true, restored.isForceRip());
        assertEquals("a", restored.getFolderPrefix());
        assertEquals(Integer.valueOf(10), restored.getMaxDownloads());
        assertEquals(0L, restored.getStartAfterMillis());

        QueueEntry overwrite = new QueueEntry("https://example.com/c", true, 10, "a", 0L, true);
        assertEquals("force|overwrite|prefix=a|max=10|https://example.com/c", overwrite.toConfigString());
        QueueEntry restoredOverwrite = QueueEntry.fromConfigString(overwrite.toConfigString());
        assertEquals(true, restoredOverwrite.isOverwriteExisting());
        assertEquals(true, restoredOverwrite.isForceRip());
        assertEquals("a", restoredOverwrite.getFolderPrefix());
        assertEquals(false, QueueEntry.fromConfigString("https://example.com/d").isOverwriteExisting());

        boolean configuredOverwrite = Utils.getConfigBoolean("file.overwrite", true);
        AtomicBoolean childSawOverwrite = new AtomicBoolean(false);
        Utils.withConfigOverride("file.overwrite", Boolean.TRUE, () -> {
            assertTrue(Utils.getConfigBoolean("file.overwrite", false));
            Runnable child = Utils.propagateConfigOverrides(
                    () -> childSawOverwrite.set(Utils.getConfigBoolean("file.overwrite", false)));
            child.run();
        });
        assertTrue(childSawOverwrite.get());
        assertEquals(configuredOverwrite, Utils.getConfigBoolean("file.overwrite", !configuredOverwrite));

        assertEquals("", new QueueEntry("https://example.com/b", false, null, "9").getFolderPrefix());
        assertEquals("areddit_user_bob", Utils.prefixFolderName(Utils.normalizeFolderName("reddit_user_bob"), "a"));
        assertEquals("reddit_user_bob", Utils.prefixFolderName("reddit_user_bob", "9"));
        assertEquals("E:\\rips\\reddit_user_bob",
                MainWindow.withoutFolderPrefix("E:\\rips\\areddit_user_bob", "a"));
        assertEquals("E:/rips/reddit_user_bob/",
                MainWindow.withoutFolderPrefix("E:/rips/areddit_user_bob/", "a"));
        assertEquals("E:\\rips\\reddit_user_bob",
                MainWindow.withoutFolderPrefix("E:\\rips\\reddit_user_bob", "a"));
    }

    @Test
    public void checkedHistoryReripQueuesWithForceAndAutostartDelay() throws IOException {
        new MainWindow(true);
        MainWindow.getQueueListModel().clear();

        HistoryEntry checked = new HistoryEntry();
        checked.url = "https://www.reddit.com/user/bob";
        checked.selected = true;
        HistoryEntry ignored = new HistoryEntry();
        ignored.url = "https://example.com/nope";
        ignored.selected = false;

        long before = System.currentTimeMillis();
        int added = MainWindow.enqueueCheckedHistory(List.of(checked, ignored));
        long after = System.currentTimeMillis();

        assertEquals(1, added);
        assertEquals(1, MainWindow.getQueueListModel().size());
        QueueEntry entry = QueueEntry.from(MainWindow.getQueueListModel().get(0));
        assertEquals("https://www.reddit.com/user/bob", entry.getUrl());
        assertTrue(entry.isForceRip());
        assertFalse(checked.selected);
        assertFalse(ignored.selected);
        assertEquals("", entry.getFolderPrefix());
        assertTrue(entry.getStartAfterMillis() >= before + MainWindow.QUEUE_AUTOSTART_DELAY_MS);
        assertTrue(entry.getStartAfterMillis() <= after + MainWindow.QUEUE_AUTOSTART_DELAY_MS);
    }

    @Test
    public void restoredQueueItemKeepsPrefixAndCanStartImmediately() throws IOException {
        new MainWindow(true);
        MainWindow.getQueueListModel().clear();

        MainWindow.addUrlToQueue("https://example.com/restored", true, 3, "b", false);

        QueueEntry entry = QueueEntry.from(MainWindow.getQueueListModel().get(0));
        assertEquals("b", entry.getFolderPrefix());
        assertTrue(entry.isForceRip());
        assertEquals(Integer.valueOf(3), entry.getMaxDownloads());
        assertEquals(0L, entry.getStartAfterMillis());

        MainWindow.addUrlToQueue("https://example.com/restored", false, null, null, false);
        assertEquals(1, MainWindow.getQueueListModel().size());
        assertEquals("b", QueueEntry.from(MainWindow.getQueueListModel().get(0)).getFolderPrefix());
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

    @Test
    public void forcedRipWritesEmptyApprovedMarkerAfterTenDownloads() throws IOException {
        Path dir = Files.createTempDirectory("ripme-approved");
        Path marker = dir.resolve(MainWindow.APPROVED_MARKER_FILENAME);
        try {
            assertFalse(MainWindow.writeApprovedMarker(dir.toFile(), true, 9));
            assertFalse(Files.exists(marker));
            assertFalse(MainWindow.writeApprovedMarker(dir.toFile(), false,
                    MainWindow.APPROVED_MARKER_MIN_DOWNLOADS));
            assertFalse(Files.exists(marker));

            assertTrue(MainWindow.writeApprovedMarker(dir.toFile(), true, MainWindow.APPROVED_MARKER_MIN_DOWNLOADS));
            assertTrue(Files.exists(marker));
            assertEquals(0, Files.size(marker));

            Files.writeString(marker, "keep");
            assertTrue(MainWindow.writeApprovedMarker(dir.toFile(), true, 11));
            assertEquals("keep", Files.readString(marker));
        } finally {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(dir);
        }
    }
}
