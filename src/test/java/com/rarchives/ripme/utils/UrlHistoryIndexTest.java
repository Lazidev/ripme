package com.rarchives.ripme.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UrlHistoryIndexTest {

    @BeforeEach
    void resetIndex() {
        UrlHistoryIndex.clear();
    }

    @Test
    void findsLinesPreviouslyWrittenToTheHistoryFile() throws Exception {
        Path file = Files.createTempFile("ripme-url-history", ".txt");
        Charset charset = Charset.defaultCharset();
        Files.writeString(file, "https://example.com/a\nhttps://example.com/b\n", charset);

        assertTrue(UrlHistoryIndex.contains(file, "https://example.com/a"));
        assertTrue(UrlHistoryIndex.contains(file, "https://example.com/b"));
        assertFalse(UrlHistoryIndex.contains(file, "https://example.com/c"));
    }

    @Test
    void rememberMakesANewlyAppendedUrlVisibleWithoutDroppingOlderOnes() throws Exception {
        Path file = Files.createTempFile("ripme-url-history", ".txt");
        Charset charset = Charset.defaultCharset();
        Files.writeString(file, "https://example.com/a\n", charset);
        assertTrue(UrlHistoryIndex.contains(file, "https://example.com/a"));

        Files.writeString(file, "https://example.com/new\n", charset, java.nio.file.StandardOpenOption.APPEND);
        UrlHistoryIndex.remember(file, "https://example.com/new\n");

        assertTrue(UrlHistoryIndex.contains(file, "https://example.com/a"));
        assertTrue(UrlHistoryIndex.contains(file, "https://example.com/new"));
    }

    @Test
    void reloadsWhenTheHistoryFileShrinks() throws Exception {
        Path file = Files.createTempFile("ripme-url-history", ".txt");
        Charset charset = Charset.defaultCharset();
        Files.writeString(file, "https://example.com/a\nhttps://example.com/b\n", charset);
        assertTrue(UrlHistoryIndex.contains(file, "https://example.com/a"));

        Files.writeString(file, "https://example.com/b\n", charset);
        assertFalse(UrlHistoryIndex.contains(file, "https://example.com/a"));
        assertTrue(UrlHistoryIndex.contains(file, "https://example.com/b"));
    }
}
