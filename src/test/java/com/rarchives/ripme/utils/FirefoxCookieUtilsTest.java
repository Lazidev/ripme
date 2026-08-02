package com.rarchives.ripme.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for Firefox cookie DB access helpers.
 */
public class FirefoxCookieUtilsTest {

    @Test
    void sqliteJdbcUrlUsesPlainPathWithoutQueryParams() {
        Path dbFile = Paths.get("C:", "Users", "DARREN~1", "AppData", "Local", "Temp",
                "ripme-firefox-cookies123.sqlite");
        String jdbcUrl = FirefoxCookieUtils.buildSqliteJdbcUrl(dbFile);

        assertTrue(jdbcUrl.startsWith("jdbc:sqlite:"), jdbcUrl);
        assertFalse(jdbcUrl.contains("?"),
                "sqlite-jdbc on Windows treats ?query as part of the filename: " + jdbcUrl);
        assertFalse(jdbcUrl.contains("mode=ro"), jdbcUrl);
    }
}
