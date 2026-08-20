package com.rarchives.ripme.ripper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rarchives.ripme.ui.RipStatusHandler;
import com.rarchives.ripme.ui.RipStatusMessage;
import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.Utils;

/**
 * A rip that fails partway through must still report the files it already downloaded, and the
 * normal end of pagination must not be reported as a failure at all.
 */
public class RipFailureResilienceTest {

    private Path tempRipsDir;
    private String originalRipsDirectory;

    @BeforeEach
    public void useTemporaryRipsDirectory() throws Exception {
        originalRipsDirectory = Utils.getConfigString("rips.directory", Utils.getWorkingDirectory().toString());
        tempRipsDir = Files.createTempDirectory("ripme-resilience-test");
        Utils.setConfigString("rips.directory", tempRipsDir.toString());
        // Another test in this JVM may have flipped the global "test rip" flag, which truncates
        // every page to a single image and stops after page one.
        Field testFlag = AbstractRipper.class.getDeclaredField("thisIsATest");
        testFlag.setAccessible(true);
        testFlag.setBoolean(null, false);
    }

    @AfterEach
    public void restoreRipsDirectory() throws IOException {
        Utils.setConfigString("rips.directory", originalRipsDirectory);
        if (tempRipsDir != null && Files.exists(tempRipsDir)) {
            try (Stream<Path> paths = Files.walk(tempRipsDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort cleanup.
                    }
                });
            }
        }
    }

    @Test
    public void runningOutOfImagesMidPaginationCompletesTheRip() throws Exception {
        StubHtmlRipper ripper = new StubHtmlRipper(List.of(
                List.of("http://example.com/1.jpg", "http://example.com/2.jpg"),
                List.of()));
        RecordingObserver observer = attach(ripper);

        ripper.run();

        assertEquals(STATUS.RIP_COMPLETE, observer.terminalStatus(),
                "An empty page after downloading images is the end of the album, not a failure");
        assertEquals(2, ripper.getCount(), "Files downloaded before the last page must be reported");
    }

    @Test
    public void emptyFirstPageStillFailsTheRip() throws Exception {
        StubHtmlRipper ripper = new StubHtmlRipper(List.of(List.of()));
        RecordingObserver observer = attach(ripper);

        ripper.run();

        assertEquals(STATUS.RIP_ERRORED, observer.terminalStatus(),
                "An album with no images at all is still a failed rip");
    }

    @Test
    public void stoppedRipReportsWhatItDownloaded() throws Exception {
        StubHtmlRipper ripper = new StubHtmlRipper(List.of(
                List.of("http://example.com/1.jpg", "http://example.com/2.jpg")));
        // Mimics hitting the maxdownloads limit: stop() is requested and the rip loop then
        // unwinds through stopCheck()'s exception.
        ripper.afterDownload = index -> {
            ripper.stop();
            throw new IllegalStateException("Ripping interrupted");
        };
        RecordingObserver observer = attach(ripper);

        ripper.run();

        assertEquals(STATUS.RIP_COMPLETE, observer.terminalStatus(),
                "A deliberately stopped rip should not be recorded as failed");
        assertEquals(1, ripper.getCount(), "The file downloaded before the stop must still be counted");
        assertFalse(observer.hasStatus(STATUS.RIP_ERRORED));
    }

    private RecordingObserver attach(StubHtmlRipper ripper) throws IOException, URISyntaxException {
        ripper.setup();
        RecordingObserver observer = new RecordingObserver();
        ripper.setObserver(observer);
        return observer;
    }

    private static final class RecordingObserver implements RipStatusHandler {
        private final List<RipStatusMessage> messages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void update(AbstractRipper ripper, RipStatusMessage message) {
            messages.add(message);
        }

        private STATUS terminalStatus() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                STATUS status = messages.get(i).getStatus();
                if (status == STATUS.RIP_COMPLETE || status == STATUS.RIP_ERRORED) {
                    return status;
                }
            }
            return null;
        }

        private boolean hasStatus(STATUS status) {
            return messages.stream().anyMatch(message -> message.getStatus() == status);
        }

    }

    /**
     * Serves a fixed list of pages without touching the network; downloads are recorded as
     * completed without writing anything to disk.
     */
    private static final class StubHtmlRipper extends AbstractHTMLRipper {
        private final List<List<String>> pages;
        private int pageIndex;
        private IntConsumer afterDownload = index -> {
        };

        private StubHtmlRipper(List<List<String>> pages) throws IOException, URISyntaxException {
            super(new URI("http://example.com/album").toURL());
            this.pages = pages;
        }

        @Override
        protected String getDomain() {
            return "example.com";
        }

        @Override
        public String getHost() {
            return "ripme-resilience-stub";
        }

        @Override
        public String getGID(URL url) {
            return "album";
        }

        @Override
        protected Document getFirstPage() {
            pageIndex = 1;
            return page(pageIndex);
        }

        @Override
        public Document getNextPage(Document doc) throws IOException {
            if (pageIndex >= pages.size()) {
                throw new IOException("No more pages");
            }
            pageIndex += 1;
            return page(pageIndex);
        }

        @Override
        protected List<String> getURLsFromPage(Document page) {
            return pages.get(pageIndex - 1);
        }

        @Override
        protected void downloadURL(URL url, int index) {
            downloadCompleted(url, Paths.get(getWorkingDir().getAbsolutePath(), "file" + index + ".jpg"));
            afterDownload.accept(index);
        }

        private static Document page(int number) {
            return Jsoup.parse("<html><body></body></html>", "http://example.com/page" + number);
        }
    }
}
